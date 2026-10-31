# DSVPN Android 客户端踩坑记录

开发与调试过程的真实问题清单：现象 → 排查 → 根因 → 修复。按阶段分组，供后续维护与移植其他平台时参考。

## 一、构建期

### 1. Version catalog 的 group 写错，报错却像"依赖下载失败"

- **现象**：`Could not find androidx.compose:ui:.`（版本号为空），且只在 configuration cache 序列化时报出；单独跑 `:app:dependencies` 却一切正常，极具误导性。
- **排查**：stacktrace 里 `Required by: project ':app'` 说明是项目自己声明的依赖；对照 TOML 发现 group 写成了 `androidx.compose`。
- **根因**：手写 `libs.versions.toml` 时把三个库的 group 写错——`ui`/`ui-tooling-preview` 属于 `androidx.compose.ui`，`material3` 属于 `androidx.compose.material3`，BOM 无版本约束的写法一错就解析为空版本。
- **修复**：改正 group。教训：**catalog 手写后必须过一次 `dependencies` 任务**，且注意 config-cache 的报错路径和普通解析路径行为不同。

### 2. JNI 局部变量遮蔽参数，NDK 编译期才暴露

- **现象**：`nativeTest` 里 `unsigned char key[32]` 与参数 `jbyteArray key` 重名，clang 报 redefinition。
- **修复**：局部改名 `key_bytes`。桌面 `make` 不编译 Android 段，这类错误只有 NDK 构建能抓到。

### 3. `-DNO_DEFAULT_ROUTES` 下 `ext_gw_ip` 未使用警告

- **修复**：把 `client_connect()` 里网关变更检测整块包进独立作用域，变量声明移入其中。保持桌面与 Android 两个编译组合都零警告。

## 二、连接与握手

### 4. 忘记 `System.loadLibrary("dsvpn")`

- **现象**：点 Test 直接 `UnsatisfiedLinkError: is the library loaded...`。
- **根因**：JNI 方法集中在 `DsvpnNative` object，但库加载写在别处（Service 里），而 Service 在点 Test 前从未启动。
- **修复**：`DsvpnNative` 的 `init {}` 中加载。**JNI 入口对象自身负责 loadLibrary** 是最稳妥的位置。

### 5. protect() 守卫误伤"未开 VPN 的连接"——分层隔离法典型案例

- **现象**：握手独立二进制（NDK 交叉编译、直接在模拟器 shell 跑）成功，App 里点 Test 必失败，服务端日志 "Authentication failed"。
- **排查**（分层隔离，逐层排除）：
  1. macOS 上用仓库 `charm.c` 写 32 行握手 harness 直连 server → OK，排除协议理解/密钥错误；
  2. 同一 harness 用 NDK 编成 Android 二进制在模拟器跑 → OK，排除 NDK/加密实现；
  3. 剩下唯一差异是 App 的 JNI 路径 → 读代码发现 `android_protect_fd()` 在 service 未绑定时返回 -1，`tcp_client()` 在 `socket()` 与 `connect()` 之间被掐死。
- **修复**：无绑定 service = 尚无 VPN 接口 = 无环路风险 → 直接放行返回 0。
- **方法论**：**协议类问题用"同一份核心代码 + 逐层替换宿主"的方式二分**，比读代码猜测快得多。

## 三、数据面

### 6. log_pump 线程 SIGSEGV——只崩在"多行输出"时

- **现象**：Test（握手）正常，正式 Connect 秒崩。backtrace 指向 `__memchr_aarch64` ← `libdsvpn.so`。
- **根因**：日志泵内层循环消耗一行时**既前移 `p` 又扣减 `len`**，而下次 `memchr` 长度按 `len - (p - line)` 计算——同一份消耗被计了两次。单行输出无感，C 核心一次吐出多行（Connecting/Interface/Connected）时 size 下溢成巨型值，`memchr` 越界。
- **修复**：统一为单一记账（`len` 永远是从 `line[0]` 起的总数，只移动 `p`，循环后一次 `memmove` 收尾）。顺带 `setvbuf(stdout, NULL, _IOLBF, 0)`——stdout 变管道后退化为全缓冲，日志会延迟。
- **教训**：**指针 + 长度双记账的代码，务必保证只有一套消耗口径**；且"单次调用没事、批量才崩"通常就是状态累计类 bug。

### 7. macOS 服务器没有 NAT——流量进得去、回不来

- **现象**：隧道字节计数器只有发送在涨（118KB↑ / 4KB↓）；Chrome 报 `DNS_PROBE_FINISHED_NO_INTERNET`；Android 内核 `InDestUnreachs` 涨了 41 个、`InEchoReps=0`。
- **排查**：计数器证明加密/解密双向都在工作，回来的全是 ICMP 不可达——是**服务器侧出口**问题，不是客户端。
- **根因**：dsvpn 的 macOS 服务端只开 `net.inet.ip.forwarding`，**不配 NAT**（Linux 版才有 iptables MASQUERADE）。以 `192.168.192.1` 为源转发的包，回包到不了。
- **修复**（测试环境）：`echo "nat on en0 inet from 192.168.192.0/24 to any -> (en0)" | sudo pfctl -ef -`。真实部署用 Linux 服务器则自带 MASQUERADE，无此问题。

### 8. MTU 9000 → PMTU 黑洞，Chrome 卡在 TLS 握手

- **现象**：NAT 修好后小包全通（DNS 解析、TCP 握手成功、连接 ESTABLISHED），但页面永远转圈；`ss -tni` 看到 ClientHello（~1.7KB）滞留 Send-Q。
- **排查**：`ping -s` 二分探测——**payload 1240 通（1268 ≤ 1280），1260 丢（1288 > 1280）**，阈值严丝合缝对上 1280；回头看 Mac：`en0` MTU=1280，还有个 `utun4`（另一层 VPN，MTU 1280）抢了第二条默认路由。
- **根因**：App tun MTU 沿用了桌面默认 9000，>服务器出口 PMTU(1280) 且带 DF 的内层包在出口被丢弃，PMTU ICMP 又没能纠正。
- **修复**：客户端 MTU 固定 **1280**（IPv6 最小值，任何嵌套网络环境下都安全）。移动端 VPN 不应照抄桌面大 MTU。

### 9. Chrome"无互联网"恐龙页——环境假象

- **现象**：数据面修好后 Chrome 仍显示 `DNS_PROBE_FINISHED_NO_INTERNET`。
- **排查**：`ss -tn state syn-sent` 列表里卡住的全是 Google IP 和 `8.8.8.8:443`（DoH）——**测试出口是中国网络，gstatic 联网检测与 dns.google 本来就不通**；而 baidu 的 443/80 连接全部 ESTABLISHED。
- **结论**：Chrome 的离线判定依赖 Google 服务，在中国出口下必然误报。**验证数据面要用与出口环境匹配的目标**（baidu/自建服务），不要被浏览器 UX 误导。

### 10. ping 服务器自身 tun 地址不通（未解但不阻塞）

macOS 对自己 utun 点对点地址的 ICMP 应答在内核输入直方图里完全无记录（`netstat -s -p icmp` 无 echo request 项）。未深究——浏览器/公网连通才是 E2E 判据。若遇到，别在"ping 网关地址"上浪费时间。

## 四、发布链路

### 11. `.gitignore` 的 `dsvpn` 模式吞掉 Kotlin 包目录——CI 包缺类的元凶

- **现象**：本地构建一切正常；CI 发布的 APK 在真机上启动即崩 `ClassNotFoundException: MainActivity`。
- **排查**：
  1. `dexdump` 解剖 CI 的 APK——**第一次 grep 用了点号（`io.github...`），漏判**；dexdump 的类描述符是**斜杠**（`Lio/github/...`）；
  2. 改用斜杠后列出全部类：只有 `BuildConfig` + `R`，**所有 Kotlin 类缺失**；
  3. 回看 android 提交的文件清单——25 个文件里没有一个 `.kt`；`git status` 也不显示未跟踪 → 被 ignore；`git check-ignore -v` 定位到根 `.gitignore:41` 的 `dsvpn`。
- **根因**：给编译二进制写的 `dsvpn` 无斜杠模式，匹配了**任意层级同名目录**——包括 Kotlin 包目录 `io/github/dongyuwei/dsvpn/`。
- **修复**：锚定根目录 `/dsvpn`，补交源码。
- **教训**（本项目最贵的一课）：**"本地能跑"证明不了"提交的内容能跑"**。发版后务必对 CI 产物做最小冒烟（哪怕只是 dexdump 查一下入口类）；另注意 gitignore 无斜杠模式会匹配任意深度的同名目录，包名与项目名相同时极易踩中。

### 12. CodeQL 语言自动探测扫到 Kotlin 即失败

- **现象**：CodeQL run 失败：`detected code written in Java/Kotlin but could not process any of it`。
- **根因**：`init` 未指定语言，自动探测把新加的 Kotlin 扫上；`autobuild` 只会根目录 `make`，编不了 Android 工程，java 数据库 finalize 失败。
- **修复**：`languages: c-cpp`——安全价值本来就在 C 加密核心；App 的构建验证由 android.yml 覆盖。

### 13. adb 多设备 + MIUI 安装确认

- 不指定 `-s` 时多设备直接报错；MIUI 通过 USB 安装常需要在手机上点确认框，不点则 `adb install` 一直挂着（表现为"装得慢"）。**脚本里永远显式 `-s <serial>` 并给 timeout。**

## 五、会话管理

### 14. 断开后重连失灵——三重 bug 叠加（真机用户反馈）

- **现象**：disconnect 后再点 connect 无反应，杀掉 App 重启才能连。
- **根因**（三层叠加）：
  1. **`exit_signal_received` 停在 1 不复位**——第二次 `dsvpn_client_run` 里 `client_reconnect` 的循环条件 `exit_signal_received == 0` 直接不成立，秒退（"Unable to connect"），服务随即 stopSelf，UI 表现为"没反应"；
  2. **CLI 信号处理器会 `signal(SIG_DFL)` 复位**——下次 stop 的 `pthread_kill(SIGTERM)` 会按默认处置**杀死整个进程**；
  3. **会话拆除跨重启竞态**——旧线程未死时 `nativeStart` 拒绝（vpn_running=1），且旧线程退出时的 `onNativeExit` 回调会把**新会话的服务也拆掉**。
- **修复**：
  - `dsvpn_client_run` 入口 `exit_signal_received = 0`；安装专用不回复默认处置的 `android_signal_handler`；
  - JNI 层引入**会话代际号（generation）**：只有当前代际的线程才能更新状态/回调 service，过期线程静默退出；
  - `nativeStart` 对未退出的旧会话**等待（≤15s）**而非拒绝；
  - `dsvpn_client_stop()` 额外 `shutdown(client_fd)` 立即唤醒阻塞在 poll/connect 的线程；
  - Kotlin 侧广播 Connected 前复核 `isRunning()`。
- **验证**：真机同一进程连续 3 次 connect/disconnect 全部 Connected（logcat gen 1/2/3）。
- **教训**：**库化改造时，CLI 生命周期假设（进程即会话、信号处置、全局标志）必须逐条审视**——"一次运行"语义换成"可反复启停"后，每个全局状态都要回答"谁初始化、谁复位、并发启停时归谁"。

## 方法论小结

1. **分层隔离**：协议问题写最小 harness，同一份核心代码换宿主（macOS → NDK 独立二进制 → App）二分定位（坑 5）。
2. **计数器优先于抓包，抓包优先于猜测**：隧道 TCP 字节数、内核 ICMP 直方图、`ss` 队列、`/proc/net/dev`、tcpdump 层层递进（坑 7/8/9）。
3. **对产物负责**：CI 出的包要抽检验证内容物，"本地好 = 远端好"不成立（坑 11）。
4. **环境与代码分开归因**：先确认"客户端 bug 还是出口环境"再动手（坑 9）；测试出口是什么网络，决定哪些"失败"是预期。
5. **CLI → 库的改造清单**：全局标志复位、信号处置、fd 所有权、多实例/重入——四件事挨个过一遍（坑 14）。

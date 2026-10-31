# ![DSVPN](https://raw.github.com/jedisct1/dsvpn/master/logo.png)

[![GitHub CI status](https://github.com/jedisct1/dsvpn/workflows/CI/badge.svg)](https://github.com/jedisct1/dsvpn/actions)
![CodeQL scan](https://github.com/jedisct1/dsvpn/workflows/CodeQL%20scan/badge.svg)

## Android VPN client (addition in this repository)

An Android VPN client built directly on this repository's own C core
(`src/vpn.c` + `src/charm.c` + `src/os.c`, compiled in place by the NDK — no
code is duplicated). Sources live in [`android/`](android/):

- **Global mode (default)**: all traffic goes through the tunnel; IPv6 is
  blackholed inside it, matching the desktop clients (no IPv6 leaks)
- **Whitelist mode**: only the selected apps use the VPN
  (`VpnService.addAllowedApplication`); everything else stays direct
- **Remote DNS resolution**: editable, defaults to `8.8.8.8, 1.1.1.1`;
  queries travel through the tunnel and are resolved by the dsvpn server,
  never locally
- Shared key imported as base64 text or from a file (on the server:
  `dd if=/dev/urandom of=vpn.key count=1 bs=32` + `base64 < vpn.key`)
- A "Test" button verifies the server, port and key (full key exchange,
  without establishing the tunnel)
- Verified on an emulator and on a Xiaomi Mi 10 Pro (Android 13): Chrome
  works in both global and whitelist modes

### Building

```bash
cd android
./gradlew :app:assembleRelease                  # needs JDK 17 + Android SDK/NDK
                                                # (missing pieces are installed by Gradle)
./gradlew :app:assembleRelease -Pabi=arm64-v8a  # slimmer single-ABI APK
adb install -r app/build/outputs/apk/release/app-release.apk
```

### CI publishing

After pushing to GitHub, `.github/workflows/android.yml` takes over:

- **Push to master** → build and publish a rolling **pre-release** (tag
  `pre-release`, with universal + arm64 APKs and SHA256SUMS, replaced on
  every push)
- **Push a `v*` tag** (e.g. `v1.0.0`) → build and publish a formal
  **Release** with generated release notes
- PRs touching `android/**`, `src/**` or `include/**` → build check only;
  artifacts downloadable from the Actions page

> Release signing (optional): set the `DSVPN_KEYSTORE_BASE64` (base64 of a
> keystore), `DSVPN_KEYSTORE_PASSWORD`, `DSVPN_KEY_ALIAS` and
> `DSVPN_KEY_PASSWORD` repository secrets. Without them, APKs are
> debug-signed (installable, but must be uninstalled before upgrading to a
> signed release).

### Local end-to-end testing (no real server needed)

Run a desktop server and reach it from the device:

```bash
sudo ./dsvpn server vpn.key auto 1959
adb reverse tcp:1959 tcp:1959   # physical device: 127.0.0.1:1959 -> the computer
```

Emulators don't need the reverse — just enter `10.0.2.2` as the server. A
macOS server additionally needs pf NAT for internet egress:
`echo "nat on en0 inet from 192.168.192.0/24 to any -> (en0)" | sudo pfctl -ef -`.

> Architecture diagram, MTU notes, MIUI keep-alive and more in
> [android/README.md](android/README.md).

---

> ↓↓↓ Everything below is the original upstream README ↓↓↓

DSVPN is a Dead Simple VPN, designed to address the most common use case for using a VPN:

```text
[client device] ---- (untrusted/restricted network) ---- [vpn server] ---- [the Internet]
```

Features:

* Runs on TCP. Works pretty much everywhere, including on public WiFi where only TCP/443 is open or reliable.
* Uses only modern cryptography, with formally verified implementations.
* Small and constant memory footprint. Doesn't perform any heap memory allocations.
* Small (~25 KB), with an equally small and readable code base. No external dependencies.
* Works out of the box. No lousy documentation to read. No configuration file. No post-configuration. Run a single-line command on the server, a similar one on the client and you're done. No firewall and routing rules to manually mess with.
* Works on Linux (kernel >= 3.17), macOS and OpenBSD, as well as DragonFly BSD, FreeBSD and NetBSD in client and point-to-point modes. Adding support for other operating systems is trivial.
* Doesn't leak between reconnects if the network doesn't change. Blocks IPv6 on the client to prevent IPv6 leaks.

## Installation

```sh
make
```

On Raspberry Pi 3 and 4, use the following command instead to enable NEON optimizations:

```sh
env OPTFLAGS=-mfpu=neon make
```

Alternatively, if you have [zig](https://ziglang.org) installed, it can be used to compile DSVPN:

```sh
zig build -Drelease
```

On macOS, DSVPN can be installed using Homebrew: `brew install dsvpn`.

## Secret key

DSVPN uses a shared secret. Create it with the following command:

```sh
dd if=/dev/urandom of=vpn.key count=1 bs=32
```

And copy it on the server and the client.

If required, keys can be exported and imported in printable form:

```sh
base64 < vpn.key
echo 'HK940OkWcFqSmZXnCQ1w6jhQMZm0fZoEhQOOpzJ/l3w=' | base64 --decode > vpn.key
```

## Example usage on the server

```sh
sudo ./dsvpn server vpn.key auto 1959
```

Here, I use port `1959`. Everything else is set to the default values. If you want to use the default port (`443`), it doesn't even have to be specified, so the parameters can just be `server vpn.key`

## Example usage on the client

```sh
sudo ./dsvpn client vpn.key 34.216.127.34 1959
```

This is a macOS client, connecting to the VPN server `34.216.127.34` on port `1959`. The port number is optional here as well. And the IP can be replaced by a host name.

## That's it

You are connected. Just hit `Ctrl`-`C` to disconnect.

Evaggelos Balaskas wrote a great blog post walking through the whole procedure: [A Dead Simple VPN](https://balaskas.gr/blog/2019/07/20/a-dead-simple-vpn/).

He also maintains [systemd service files for DSVPN](https://github.com/ebal/scripts/tree/master/dsvpn). Thank you Evaggelos!

## A note on DNS

If you were previously using a DNS resolver only accessible from the local network, it won't be accessible through the VPN. That might be the only thing you may have to change. Use a public resolver, a local resolver, or DNSCrypt.

Or send a pull request implementing the required commands to change and revert the DNS settings, or redirect DNS queries to another resolver, for all supported operating systems.

## Advanced configuration

```text
dsvpn   "server"
        <key file>
        <vpn server ip or name>|"auto"
        <vpn server port>|"auto"
        <tun interface>|"auto"
        <local tun ip>|"auto"
        <remote tun ip>|"auto"
        <external ip>|"auto"

dsvpn   "client"
        <key file>
        <vpn server ip or name>
        <vpn server port>|"auto"
        <tun interface>|"auto"
        <local tun ip>|"auto"
        <remote tun ip>|"auto"
        <gateway ip>|"auto"
```

* `server`|`client`: use `server` on the server, and `client` on clients.
* `<key file>`: path to the file with the secret key (e.g. `vpn.key`).
* `<vpn server ip or name>`: on the client, it should be the IP address or the hostname of the server. On the server, it doesn't matter, so you can just use `auto`.
* `<vpn server port>`: the TCP port to listen to/connect to for the VPN. Use 443 or anything else. `auto` will use `443`.
* `<tun interface>`: this is the name of the VPN interface. On Linux, you can set it to anything. Or macOS, it has to follow a more boring pattern. If you feel lazy, just use `auto` here.
* `<local tun ip>`: local IP address of the tunnel. Use any private IP address that you don't use here.
* `<remote tun ip>`: remote IP address of the tunnel. See above. The local and remote tunnel IPs must the same on the client and on the server, just reversed. For some reason, I tend to pick `192.168.192.254` for the server, and `192.168.192.1` for the client. These values will be used if you put `auto` for the local and remote tunnel IPs.
* `<external ip>` (server only): the external IP address of the server. Can be left to `"auto"`.
* `<gateway ip>` (client only): the internal router IP address. The first line printed by `netstat -rn` will tell you (`gateway`).

If all the remaining parameters of a command would be `auto`, they don't have to be specified.

## Related projects

* [dsvpn-ios](https://github.com/jedisct1/dsvpn-ios) is an iOS client for DSVPN
* Robert Debock maintains [an Ansible role for DSVPN](https://github.com/robertdebock/ansible-role-dsvpn)
* [OpenMPTCProuter](http://www.openmptcprouter.com/) is an OpenWrt-based router OS that supports DSVPN
* Yecheng Fu maintains a [Docker image for DSVPN](https://github.com/cofyc/dsvpn-docker)

## Why

I needed a VPN that works in an environment where only TCP/80 and TCP/443 are open.

WireGuard doesn't work over TCP.

[GloryTun](https://github.com/angt/glorytun) is excellent, but requires post-configuration and the maintained branch uses UDP.

I forgot about [VTUN-libsodium](https://github.com/jedisct1/vtun). But it would have been too much complexity and attack surface for a simple use case.

OpenVPN is horribly difficult to set up.

Sshuttle is very nice and I've been using it a lot in the past, but it's not a VPN. It doesn't tunnel non-TCP traffic. It also requires a full Python install, which I'd rather avoid on my router.

Everything else I looked at was either too difficult to use, slow, bloated, didn't work on macOS, didn't work on small devices, was complicated to cross-compile due to dependencies, wasn't maintained, or didn't feel secure.

TCP-over-TCP is not as bad as some documents describe. It works surprisingly well in practice, especially with modern congestion control algorithms (BBR). For traditional algorithms that rely on packet loss, DSVPN couples the inner and outer congestion controllers by lowering `TCP_NOTSENT_LOWAT` and dropping packets when congestion is detected at the outer layer.

## Cryptography

The cryptographic primitives used in DSVPN are available as a standalone project: [Charm](https://github.com/jedisct1/charm).

## Guarantees, support, feature additions

None.

This is what I use, because it solves a problem I had. Extending it to solve different problems is not planned, but feel free to fork it and tailor it to your needs!

# DSVPN for Android

An Android client for [DSVPN](../README.md), built directly on the same C
core as the desktop clients: `src/vpn.c`, `src/charm.c` and `src/os.c` are
compiled in place by the NDK (no code is duplicated into this directory).
Only the platform layer is replaced by Android's `VpnService`.

## Architecture

```text
DsvpnVpnService (Kotlin)          dsvpn_jni.c                 dsvpn C core
┌─────────────────────────┐   ┌──────────────────────┐   ┌────────────────────┐
│ VpnService.Builder      │   │ nativeStart(fd,key,  │   │ dsvpn_client_run() │
│  addresses/routes/DNS   │──▶│  server,port)        │──▶│  = client path of  │
│  establish() → tun fd   │   │ thread + log bridge  │   │  main() in vpn.c   │
│ protect() via JNI       │◀──│ android_protect_fd() │◀──│ tcp_client() hook  │
└─────────────────────────┘   └──────────────────────┘   └────────────────────┘
```

* The tun descriptor comes from `Builder.establish().detachFd()`; the native
  side owns and closes it.
* The native core calls back `VpnService.protect()` for its own TCP socket so
  the tunnel never routes into itself (the equivalent of `SO_MARK` + policy
  routing on desktop Linux).
* Routing, addresses, MTU and firewall rules are all handled by the Builder;
  the C side is compiled with `-DNO_DEFAULT_ROUTES` and stubs the rest.
* Encryption, key exchange, framing and the reconnect loop are byte-for-byte
  the desktop implementation.

## Features

* Global mode (default): all apps go through the tunnel; IPv6 is blackholed
  inside it, matching the desktop client (no IPv6 leaks).
* Whitelist mode: pick the apps to proxy; everything else stays direct.
* Editable DNS servers (default `8.8.8.8, 1.1.1.1`): queries travel through
  the tunnel and are resolved remotely by the server.
* "Test" button: runs a key exchange against the server without connecting.

## Building

Requirements: JDK 17, Android SDK (+ platform 36), Android NDK r27
(`ANDROID_NDK_HOME`), CMake 3.22+ (Android Studio's SDK manager can install
both). `local.properties` with `sdk.dir` is created by Android Studio; it is
not committed.

```sh
cd android
./gradlew :app:assembleDebug

# Slimmer APK for a single device:
./gradlew :app:assembleRelease -Pabi=arm64-v8a
```

## Local end-to-end test

Run a desktop server on the host, then reach it from the emulator
(`10.0.2.2` is the host loopback from the emulator's perspective):

```sh
dd if=/dev/urandom of=/tmp/vpn.key count=1 bs=32
sudo ./dsvpn server /tmp/vpn.key auto 1959

adb reverse tcp:1959 tcp:1959   # optional: adb reverse instead of 10.0.2.2
# In the app: server = 10.0.2.2 (or 127.0.0.1 with adb reverse), port 1959,
# key = `base64 < /tmp/vpn.key`
```

`adb logcat -s dsvpn` shows the C core's stdout/stderr.

## Caveats

* The TUN MTU is 1280 (the IPv6 minimum) instead of the desktop clients'
  9000: mobile paths often sit behind further VPN hops where large inner
  packets get blackholed. Raise it only if you control the server's network.
* The shared key is stored in plain `SharedPreferences` (same trade-off as
  other simple VPN clients). Don't hand out your unlocked phone.
* Android's **Private DNS** setting bypasses the VPN's DNS for systems using
  it; per-app Private DNS is unaffected by this client.
* Stop requests take effect within a poll tick (1.5s), or as soon as the
  current blocking connect/read returns.
* The APK is debug-signed unless the `DSVPN_KEYSTORE_*` release secrets are
  configured (see `.github/workflows/android.yml`); debug- and
  release-signed builds cannot upgrade over each other.

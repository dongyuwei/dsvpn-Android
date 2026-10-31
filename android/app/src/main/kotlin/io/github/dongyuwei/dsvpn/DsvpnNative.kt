package io.github.dongyuwei.dsvpn

/** JNI surface of libdsvpn.so (see android/app/src/main/cpp/dsvpn_jni.c). */
object DsvpnNative {
    init {
        System.loadLibrary("dsvpn")
    }

    /** Lets the C core call VpnService.protect() and report session exit. */
    external fun bindService(service: Any?)

    /**
     * Starts the VPN session on a native thread. [key] must be exactly 32
     * bytes; [tunFd] (from Builder.establish().detachFd()) is owned and
     * closed by the native side. Returns 0 on success.
     */
    external fun start(tunFd: Int, key: ByteArray, server: String, port: String): Int

    /** Requests a graceful stop; takes effect within a second or two. */
    external fun stop()

    /** Runs the handshake against [server]:[port] only. Returns 0 if the key is accepted. */
    external fun test(server: String, port: String, key: ByteArray): Int

    external fun isRunning(): Boolean
}

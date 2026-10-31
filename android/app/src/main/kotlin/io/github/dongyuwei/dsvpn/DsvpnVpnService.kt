package io.github.dongyuwei.dsvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Base64
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Creates the TUN interface and hands the descriptor to the native dsvpn
 * core (src/vpn.c).
 *
 * Routing model: global mode (default) routes everything into the tunnel
 * except this app itself; whitelist mode routes only the selected packages.
 * The native core additionally protects its own TCP socket with
 * VpnService.protect() so the tunnel never routes into itself.
 */
class DsvpnVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.dongyuwei.dsvpn.START"
        const val ACTION_STOP = "io.github.dongyuwei.dsvpn.STOP"
        const val ACTION_STATE = "io.github.dongyuwei.dsvpn.STATE"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "dsvpn-vpn"
        private const val NOTIFICATION_ID = 1

        // Conservative MTU: the inner packets must fit the server's egress
        // path, which may itself be a VPN with a 1280 MTU. 1280 (the IPv6
        // minimum) is safe everywhere; desktop clients keep 9000.
        private const val MTU = 1280
        private const val LOCAL_TUN_IP = "192.168.192.1"
        private const val LOCAL_TUN_IP6 = "64:ff9b::192.168.192.1"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DsvpnVpnService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DsvpnVpnService::class.java).setAction(ACTION_STOP),
            )
        }

        fun broadcastState(context: Context, running: Boolean, error: String? = null) {
            context.sendBroadcast(
                Intent(ACTION_STATE)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_RUNNING, running)
                    .putExtra(EXTRA_ERROR, error),
            )
        }

        /** Decodes the stored base64 key; null when it is not exactly 32 bytes. */
        fun decodeKey(keyB64: String): ByteArray? = runCatching {
            Base64.decode(keyB64.trim(), Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.size == 32 }
    }

    // Only touched on the vpn thread; null once torn down.
    private var tunFd: Int? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        DsvpnNative.bindService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundCompat()
                if (running) {
                    broadcastState(this, true)
                    return START_STICKY
                }
                Thread { runVpn() }.start()
            }
        }
        return START_STICKY
    }

    private fun runVpn() {
        val server = Prefs.server(this)
        val port = Prefs.port(this)
        val whitelist = Prefs.whitelist(this)
        val mode = Prefs.mode(this)
        val key = decodeKey(Prefs.key(this))
        if (server.isBlank()) {
            fail("No server configured")
            return
        }
        if (key == null) {
            fail("Invalid key: expected the 32-byte key in base64")
            return
        }
        if (mode == Prefs.MODE_WHITELIST && whitelist.isEmpty()) {
            fail("Whitelist is empty: select at least one app")
            return
        }

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(LOCAL_TUN_IP, 32)
            .addAddress(LOCAL_TUN_IP6, 128)
            .addRoute("0.0.0.0", 0)
            // IPv6 goes into the tunnel and dies there, matching the desktop
            // client's blackhole behavior (no IPv6 leaks).
            .addRoute("::", 0)
        // Editable DNS (Prefs): queries travel through the tunnel and are
        // resolved remotely by the dsvpn server (no local resolution).
        val dnsServers = Prefs.dns(this).ifEmpty {
            Prefs.DEFAULT_DNS.split(',').map { it.trim() }
        }
        for (dns in dnsServers) builder.addDnsServer(dns)
        if (mode == Prefs.MODE_WHITELIST) {
            for (pkg in whitelist) {
                if (pkg == packageName) continue // never route our own traffic
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    // App was uninstalled; skip it.
                }
            }
        } else {
            // Global mode: keep our own traffic (UI, test button) direct even
            // if the native protect() call were to fail.
            builder.addDisallowedApplication(packageName)
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            fail("Establish VPN failed: ${e.message}")
            return
        }
        if (pfd == null) {
            fail("Establish VPN failed: VPN permission revoked?")
            return
        }

        // Hand the descriptor over to native; it owns and closes it from here on.
        val fd = pfd.detachFd()
        tunFd = fd
        try {
            if (DsvpnNative.start(fd, key, server, port) != 0) {
                tunFd = null
                fail("Start tunnel failed: another session may still be shutting down")
                return
            }
        } catch (e: Throwable) {
            tunFd = null
            // The native side closes the fd on its error paths itself.
            fail("Start tunnel failed: ${e.message}")
            return
        }

        running = true
        broadcastState(this, true)
    }

    /** Called from the native thread when the C session loop returns. */
    fun onNativeExit() {
        running = false
        tunFd = null
        broadcastState(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(error: String) {
        running = false
        broadcastState(this, false, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown() {
        if (running) {
            running = false
            try {
                DsvpnNative.stop()
            } catch (_: Throwable) {
                // Already down; nothing to do.
            }
            tunFd = null
        }
        broadcastState(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            try {
                DsvpnNative.stop()
            } catch (_: Throwable) {
            }
        }
        DsvpnNative.bindService(null)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startForegroundCompat() {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

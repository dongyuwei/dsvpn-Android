package io.github.dongyuwei.dsvpn

import android.content.Context
import android.content.SharedPreferences

/** Small persistence layer over SharedPreferences. */
object Prefs {
    private const val FILE = "dsvpn"
    private const val KEY_SERVER = "server"
    private const val KEY_PORT = "port"
    private const val KEY_KEY = "key"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_DNS = "dns"
    private const val KEY_MODE = "mode"

    /** Foreign resolvers only; editable in-app, read when the VPN is built. */
    const val DEFAULT_DNS = "8.8.8.8, 1.1.1.1"
    const val DEFAULT_PORT = "443"
    const val DEFAULT_SERVER = ""

    /** "global" (all traffic through the tunnel) or "whitelist" (selected apps only). */
    const val MODE_GLOBAL = "global"
    const val MODE_WHITELIST = "whitelist"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun server(context: Context): String =
        prefs(context).getString(KEY_SERVER, DEFAULT_SERVER).orEmpty()

    fun saveServer(context: Context, server: String) =
        prefs(context).edit().putString(KEY_SERVER, server.trim()).apply()

    fun port(context: Context): String =
        prefs(context).getString(KEY_PORT, DEFAULT_PORT)?.takeIf { it.isNotBlank() } ?: DEFAULT_PORT

    fun savePort(context: Context, port: String) =
        prefs(context).edit().putString(KEY_PORT, port.trim()).apply()

    /** Base64 of the 32-byte shared secret. */
    fun key(context: Context): String =
        prefs(context).getString(KEY_KEY, "").orEmpty()

    fun saveKey(context: Context, key: String) =
        prefs(context).edit().putString(KEY_KEY, key.trim()).apply()

    /** "global" or "whitelist"; global is the default. */
    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_GLOBAL)?.takeIf { it.isNotBlank() } ?: MODE_GLOBAL

    fun saveMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_MODE, mode).apply()

    /** Package names of apps allowed to use the VPN (whitelist mode only). */
    fun whitelist(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()

    fun saveWhitelist(context: Context, packages: Set<String>) =
        prefs(context).edit().putStringSet(KEY_WHITELIST, packages).apply()

    /** Raw DNS string as entered by the user. */
    fun dnsRaw(context: Context): String =
        prefs(context).getString(KEY_DNS, DEFAULT_DNS)?.takeIf { it.isNotBlank() } ?: DEFAULT_DNS

    fun saveDns(context: Context, raw: String) =
        prefs(context).edit().putString(KEY_DNS, raw.trim()).apply()

    /**
     * Parsed DNS server IPs for VpnService.Builder.addDnsServer. Invalid
     * entries are dropped; empty result means the caller should fall back
     * to DEFAULT_DNS's valid addresses.
     */
    fun dns(context: Context): List<String> =
        dnsRaw(context).split(',', ';', ' ').map { it.trim() }.filter { isValidIp(it) }.distinct()

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    private fun isValidIp(s: String): Boolean {
        val m = ipv4.matchEntire(s) ?: return s.contains(':') && s.all {
            it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':'
        }
        return m.groupValues.drop(1).all { it.toInt() in 0..255 }
    }
}

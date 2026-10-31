package io.github.dongyuwei.dsvpn

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.dongyuwei.dsvpn.ui.theme.DsvpnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DsvpnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var server by remember { mutableStateOf(Prefs.server(context)) }
    var port by remember { mutableStateOf(Prefs.port(context)) }
    var key by remember { mutableStateOf(Prefs.key(context)) }
    var mode by remember { mutableStateOf(Prefs.mode(context)) }
    var whitelistCount by remember { mutableStateOf(Prefs.whitelist(context).size) }
    var showAppList by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(DsvpnVpnService.running) }
    var error by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    // Key is masked like a password; the eye button reveals it.
    var showKey by remember { mutableStateOf(false) }
    var dnsRaw by remember { mutableStateOf(Prefs.dnsRaw(context)) }
    var editDns by remember { mutableStateOf("") }
    var showDnsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Track the VPN service lifecycle.
    val receiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) {
                i ?: return
                running = i.getBooleanExtra(DsvpnVpnService.EXTRA_RUNNING, false)
                val err = i.getStringExtra(DsvpnVpnService.EXTRA_ERROR)
                if (err != null) error = err
            }
        }
    }
    LaunchedEffect(Unit) {
        ContextCompat.registerReceiver(
            context, receiver,
            android.content.IntentFilter(DsvpnVpnService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun saveAll() {
        Prefs.saveServer(context, server.trim())
        Prefs.savePort(context, port.trim())
        Prefs.saveKey(context, key.trim())
        Prefs.saveMode(context, mode)
    }

    fun testConnection() {
        saveAll()
        testResult = null
        val keyBytes = DsvpnVpnService.decodeKey(key)
        if (keyBytes == null) {
            testResult = "Invalid key: expected the 32-byte key in base64"
            return
        }
        scope.launch(Dispatchers.IO) {
            val message = try {
                when (DsvpnNative.test(server.trim(), port.trim(), keyBytes)) {
                    0 -> "Handshake OK - server and key are valid"
                    else -> "Handshake failed: wrong key, wrong port, or server unreachable"
                }
            } catch (e: Throwable) {
                "Test failed: ${e.message}"
            }
            withContext(Dispatchers.Main) { testResult = message }
        }
    }

    // Import the 32-byte key from a file (raw bytes or base64 text).
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val message = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: error("Cannot read the file")
                val raw = if (bytes.size == 32) {
                    bytes
                } else {
                    val text = bytes.toString(Charsets.US_ASCII).trim()
                    val decoded = runCatching {
                        Base64.decode(text, Base64.DEFAULT)
                    }.getOrNull()
                    if (decoded != null && decoded.size == 32) decoded else error("Not a 32-byte key (raw or base64)")
                }
                key = Base64.encodeToString(raw, Base64.NO_WRAP)
                Prefs.saveKey(context, key)
                "Key imported"
            }.getOrElse { "Import failed: ${it.message}" }
            withContext(Dispatchers.Main) { testResult = message }
        }
    }

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            DsvpnVpnService.start(context)
        } else {
            error = "VPN permission denied"
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Continue with or without notification permission.
        val prepare = android.net.VpnService.prepare(context)
        if (prepare != null) vpnConsent.launch(prepare)
        else DsvpnVpnService.start(context)
    }

    fun connect() {
        error = null
        saveAll()
        if (server.isBlank()) {
            error = "No server configured"
            return
        }
        if (DsvpnVpnService.decodeKey(key) == null) {
            error = "Invalid key: expected the 32-byte key in base64"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val prepare = android.net.VpnService.prepare(context)
        if (prepare != null) {
            vpnConsent.launch(prepare)
            return
        }
        DsvpnVpnService.start(context)
    }

    if (showAppList) {
        AppListScreen(
            onDone = {
                whitelistCount = Prefs.whitelist(context).size
                showAppList = false
            },
        )
        return
    }

    if (showDnsDialog) {
        AlertDialog(
            onDismissRequest = { showDnsDialog = false },
            title = { Text("DNS servers") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Comma-separated IP addresses. Queries are resolved " +
                            "remotely by the dsvpn server through the tunnel.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = editDns,
                        onValueChange = { editDns = it },
                        label = { Text(Prefs.DEFAULT_DNS) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.saveDns(context, editDns)
                    dnsRaw = editDns.trim()
                    showDnsDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDnsDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it; Prefs.saveServer(context, it.trim()) },
                label = { Text("VPN server") },
                placeholder = { Text("server.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !running,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it; Prefs.savePort(context, it.trim()) },
                label = { Text("Port") },
                placeholder = { Text(Prefs.DEFAULT_PORT) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !running,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; Prefs.saveKey(context, it.trim()) },
                label = { Text("Shared key (base64)") },
                placeholder = { Text("base64 < vpn.key") },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (showKey) 2 else 1,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation('•')
                },
                // Masked: password keyboard (no suggestions); shown: paste-friendly.
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (showKey) KeyboardType.Ascii else KeyboardType.Password,
                ),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                enabled = !running,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }, enabled = !running) {
                    Text("Import key")
                }
                OutlinedButton(onClick = { testConnection() }) { Text("Test") }
            }
            testResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Routing mode: global by default; whitelist routes only the
            // selected apps.
            Text("Routing mode", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = mode == Prefs.MODE_GLOBAL,
                    onClick = { mode = Prefs.MODE_GLOBAL; Prefs.saveMode(context, mode) },
                    enabled = !running,
                )
                Text("Global (all apps)", Modifier.clickable(enabled = !running) {
                    mode = Prefs.MODE_GLOBAL; Prefs.saveMode(context, mode)
                })
                Spacer(Modifier.width(16.dp))
                RadioButton(
                    selected = mode == Prefs.MODE_WHITELIST,
                    onClick = { mode = Prefs.MODE_WHITELIST; Prefs.saveMode(context, mode) },
                    enabled = !running,
                )
                Text("Whitelist", Modifier.clickable(enabled = !running) {
                    mode = Prefs.MODE_WHITELIST; Prefs.saveMode(context, mode)
                })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !running && mode == Prefs.MODE_WHITELIST) {
                        showAppList = true
                    }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Whitelisted apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            mode != Prefs.MODE_WHITELIST -> "Unused in global mode"
                            whitelistCount == 0 -> "None selected - VPN will not start"
                            else -> "$whitelistCount app(s) proxied, others direct"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Edit", color = MaterialTheme.colorScheme.primary)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editDns = dnsRaw; showDnsDialog = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DNS servers", style = MaterialTheme.typography.titleMedium)
                    Text(
                        Prefs.dns(context).joinToString(", ")
                            .ifEmpty { "No valid entry - falls back to ${Prefs.DEFAULT_DNS}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (running) "Applies on next connect" else "Resolved remotely through the tunnel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Edit", color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = {
                    if (running) {
                        DsvpnVpnService.stop(context)
                    } else {
                        connect()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Disconnect" else "Connect")
            }

            Text(
                if (running) "Connected" else "Disconnected",
                style = MaterialTheme.typography.titleMedium,
                color = if (running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.weight(1f))
            val versionText = listOfNotNull(
                BuildConfig.GIT_TAG.takeIf { it.isNotBlank() },
                "v" + BuildConfig.VERSION_NAME,
                BuildConfig.GIT_HASH.takeIf { it.isNotBlank() },
                BuildConfig.BUILD_DATE.takeIf { it.isNotBlank() },
            ).joinToString("-")
            Text(
                versionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinkText("Server (upstream): github.com/jedisct1/dsvpn", "https://github.com/jedisct1/dsvpn")
            LinkText("Android client: github.com/dongyuwei/dsvpn-Android", "https://github.com/dongyuwei/dsvpn-Android")
        }
    }
}

@Composable
private fun LinkText(label: String, url: String) {
    val context = LocalContext.current
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .padding(vertical = 2.dp),
    )
}

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    // Launchable apps only: what a user would call "installed apps".
    val apps by produceState(initialValue = emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { ri ->
                    AppEntry(
                        label = ri.loadLabel(pm).toString(),
                        packageName = ri.activityInfo.packageName,
                        icon = ri.activityInfo.loadIcon(pm),
                    )
                }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    var selected by remember { mutableStateOf(Prefs.whitelist(context)) }
    var query by remember { mutableStateOf("") }

    fun persist(next: Set<String>) {
        selected = next
        Prefs.saveWhitelist(context, next)
    }

    // Selected apps float to the top (alphabetical among themselves),
    // then the rest; both halves filtered by the search query on label
    // or package name.
    val display = remember(apps, selected, query) {
        val q = query.trim().lowercase()
        val matches = { a: AppEntry ->
            q.isEmpty() || a.label.lowercase().contains(q) || a.packageName.lowercase().contains(q)
        }
        val (chosen, others) = apps.filter(matches).partition { it.packageName in selected }
        chosen + others
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Whitelist apps") },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text("←") }
                },
                actions = {
                    TextButton(onClick = {
                        persist(apps.map { it.packageName }.toSet())
                    }) { Text("All") }
                    TextButton(onClick = { persist(emptySet()) }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (apps.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { Text("Loading…") }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(display, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    val bitmap by produceState(initialValue = null as Bitmap?, app) {
                        value = withContext(Dispatchers.IO) { app.icon.toBitmap(96) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                persist(
                                    if (checked) selected - app.packageName
                                    else selected + app.packageName,
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bmp = bitmap
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        } else {
                            Spacer(Modifier.size(40.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                persist(
                                    if (checked) selected - app.packageName
                                    else selected + app.packageName,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun Drawable.toBitmap(sizePx: Int): Bitmap {
    if (this is BitmapDrawable) return bitmap
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

package uk.co.econetconnect

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ECONET_PORTAL = "https://cloud.grant.eu"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EconetDashboardApp() }
    }
}

private data class DashboardValues(
    val state: String, val flow: String, val ret: String, val rate: String,
    val compressor: String, val fan: String, val hpSet: String,
    val room: String, val roomTarget: String, val curve: String,
    val roomSet: String, val hysteresis: String, val dhw: String, val dhwTarget: String
)

private fun parseDashboard(source: String): DashboardValues {
    fun find(vararg aliases: String): String {
        val lines = source.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }
        for (i in lines.indices) for (alias in aliases) {
            val match = Regex("(?i)^.*?${Regex.escape(alias)}\\s*(?::|=|—|-)?\\s*(.+?)\\s*$").find(lines[i])
            if (match != null && match.groupValues[1].isNotBlank()) return match.groupValues[1].take(48)
            if (lines[i].equals(alias, true) && i + 1 < lines.size && lines[i + 1].length < 64) return lines[i + 1]
        }
        return "Not detected"
    }
    return DashboardValues(
        find("Heat pump status", "Operating status", "Device status", "On/Off", "Running state", "Status"),
        find("Flow temperature", "Flow temp", "Leaving water temperature", "LWT"),
        find("Return temperature", "Return temp", "Entering water temperature", "RWT"),
        find("Flow rate", "Water flow", "Water flow rate"),
        find("Compressor frequency", "Compressor freq", "Compressor Hz", "Compressor speed"),
        find("Fan RPM", "Fan speed", "Fan rotation speed"),
        find("Flow set temperature", "Flow target", "Water setpoint", "Heating set temperature", "Set temperature"),
        find("Room temperature", "Current room temperature", "Room temp"),
        find("Room temperature target", "Target room temperature", "Room target"),
        find("Weather curve", "Heating curve", "Compensation curve"),
        find("Room setpoint", "Requested room temperature", "Target room temp"),
        find("Hysteresis", "Room hysteresis", "Temperature hysteresis"),
        find("DHW temperature", "Hot water temperature", "Domestic hot water temperature", "Tank temperature"),
        find("DHW target", "Hot water target", "DHW set temperature", "Domestic hot water setpoint")
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EconetDashboardApp() {
    val context = LocalContext.current
    var portalVisible by remember { mutableStateOf(true) }
    var capturedText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("No installation selected") }
    var pageStatus by remember { mutableStateOf("Sign in to the official portal to load live data") }
    var lastRead by remember { mutableStateOf("Waiting for portal data") }
    var pageError by remember { mutableStateOf("") }
    var pageProgress by remember { mutableIntStateOf(0) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setBackgroundColor(android.graphics.Color.WHITE)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    pageProgress = newProgress
                    if (newProgress < 100) pageStatus = "Loading Econet portal… " + newProgress + "%"
                }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) pageError = "Portal script error: " + message.message().take(180)
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme.equals("https", ignoreCase = true)) return false
                    if (uri.scheme.equals("http", ignoreCase = true)) {
                        pageError = "Blocked an insecure HTTP redirect from the Econet portal."
                        return true
                    }
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    pageStatus = "Loading official portal…"
                    pageError = ""
                }
                override fun onPageFinished(view: WebView, url: String) {
                    title = view.title?.takeIf { it.isNotBlank() } ?: "ecoNET Cloud"
                    pageStatus = "Portal loaded"
                    capturePortalText(view) { text ->
                        capturedText = text
                        lastRead = "Captured " + SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        pageError = error.description?.toString() ?: "Portal could not be loaded."
                        pageStatus = "Portal loading failed"
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    pageError = "Secure connection validation failed. The page was blocked."
                    pageStatus = "Secure connection blocked"
                }
            }
            loadUrl(ECONET_PORTAL)
        }
    }

    fun capture() {
        capturePortalText(webView) { text ->
            capturedText = text
            lastRead = "Captured " + SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
            pageStatus = if (text.isBlank()) "No readable page text yet" else "Portal data captured"
        }
    }

    BackHandler(enabled = !portalVisible) { portalVisible = true }
    LaunchedEffect(portalVisible, webView) {
        while (true) {
            if (!portalVisible) capture()
            delay(5000)
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF006B3F),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFF200),
        onPrimaryContainer = Color(0xFF003B20),
        secondary = Color(0xFFFFD500),
        onSecondary = Color(0xFF003B20),
        secondaryContainer = Color(0xFFE5F4E9),
        onSecondaryContainer = Color(0xFF003B20),
        tertiary = Color(0xFF008A45),
        background = Color(0xFFFFFDF0),
        surface = Color(0xFFFFFEF8),
        surfaceVariant = Color(0xFFFFF9CC),
        onSurface = Color(0xFF173B29),
        onSurfaceVariant = Color(0xFF496353)
    )) {
        Scaffold(
            topBar = {
                Row(Modifier.fillMaxWidth().background(Color(0xFFFFFEF8)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Econet Connect", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Service Edition · Cloud dashboard", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { if (portalVisible) webView.reload() else capture() }) {
                        Text(if (portalVisible) "Reload" else "Refresh")
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = !portalVisible, onClick = { portalVisible = false }, icon = { Text("⌂") }, label = { Text("Dashboard") })
                    NavigationBarItem(selected = portalVisible, onClick = { portalVisible = true }, icon = { Text("◎") }, label = { Text("Econet portal") })
                }
            }
        ) { padding ->
            if (portalVisible) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (pageError.isNotBlank()) Text(pageError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("LIVE INSTALLATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("$pageStatus · $lastRead", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    val v = remember(capturedText) { parseDashboard(capturedText) }
                    if (capturedText.isBlank()) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Connect to your installation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Sign in through the official Econet portal, select your installation and open its live controller page. Return here to populate this dashboard.")
                                Button(onClick = { portalVisible = true }) { Text("Sign in to Econet") }
                            }
                        }
                    }
                    DashboardSection("HEAT PUMP", v.state) {
                        MetricGrid(listOf("Flow" to v.flow, "Return" to v.ret, "Flow rate" to v.rate,
                            "Compressor frequency" to v.compressor, "Fan RPM" to v.fan, "Set temp" to v.hpSet))
                    }
                    DashboardSection("ROOM HEATING", null) {
                        MetricGrid(listOf("Room temp" to v.room, "Room target" to v.roomTarget,
                            "Weather curve" to v.curve, "Set temp" to v.roomSet, "Hysteresis" to v.hysteresis))
                    }
                    DashboardSection("HOT WATER", null) {
                        MetricGrid(listOf("Hot water temp" to v.dhw, "Hot water target" to v.dhwTarget))
                    }
                    if (capturedText.isNotBlank()) {
                        Text("Source: official ecoNET portal · $lastRead", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { portalVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("Return to Econet portal") }
                        Text("Captured portal text (diagnostic)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(capturedText.take(1800), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                // Keep the signed-in portal and its cookies alive behind the dashboard.
                AndroidView(factory = { webView }, modifier = Modifier.size(1.dp))
            }
        }
    }
}

private fun capturePortalText(webView: WebView, callback: (String) -> Unit) {
    webView.post {
        webView.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { result ->
            val text = try { JSONTokener(result ?: "\"\"").nextValue() as? String ?: "" } catch (_: Exception) { "" }
            callback(text)
        }
    }
}

@Composable
private fun DashboardSection(title: String, status: String?, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFFFFEF8))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { (label, value) ->
                    Surface(Modifier.weight(1f), color = Color(0xFFFFF9CC), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF496353))
                            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF006B3F))
                        }
                    }
                }
                if (group.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

package com.linkextractor.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

private enum class LinkSource { DOM, SOURCE, SCRIPT }
private data class ExtractedLink(val url: String, val source: LinkSource)

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val allLinks = mutableStateListOf<ExtractedLink>()
    private var pageUrl by mutableStateOf("")
    private var addressText by mutableStateOf("")
    private var status by mutableStateOf("Open a webpage or share one from Chrome.")
    private var isBusy by mutableStateOf(false)
    private var deepScan by mutableStateOf(true)
    private var httpsOnly by mutableStateOf(false)
    private var externalOnly by mutableStateOf(false)
    private var includeSource by mutableStateOf(true)
    private var searchText by mutableStateOf("")
    private var tab by mutableStateOf(0)
    private var pendingExportType by mutableStateOf("")
    private val scanGeneration = AtomicInteger(0)

    private val createTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && pendingExportType == "txt") writeExport(uri, false)
    }
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && pendingExportType == "csv") writeExport(uri, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWebView()
        handleIntent(intent)
        setContent { AppUi() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        scanGeneration.incrementAndGet()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    pageUrl = url.orEmpty()
                    addressText = url.orEmpty()
                    status = "Loading page…"
                    allLinks.clear()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageUrl = url.orEmpty()
                    addressText = url.orEmpty()
                    status = "Page loaded. Scanning links…"
                    startScan()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            webChromeClient = WebChromeClient()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val candidate = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> intent?.dataString ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
        }
        val url = extractUrl(candidate.orEmpty()) ?: return
        addressText = url
        loadPage(url)
    }

    private fun extractUrl(text: String): String? =
        Regex("https?://[^\\s<>\\\"']+").find(text)?.value?.trimEnd('.', ',', ')', ']', ';')

    private fun loadPage(raw: String) {
        val normalized = normalizeHttpUrl(raw) ?: run {
            status = "Enter a valid HTTP or HTTPS URL."
            return
        }
        scanGeneration.incrementAndGet()
        allLinks.clear()
        pageUrl = normalized
        addressText = normalized
        isBusy = true
        status = "Opening $normalized"
        webView.loadUrl(normalized)
        tab = 0
    }

    private fun startScan() {
        val generation = scanGeneration.incrementAndGet()
        isBusy = true
        if (deepScan) deepScrollAndScan(generation, 0) else extractPage(generation, includeSource = true)
    }

    private fun deepScrollAndScan(generation: Int, step: Int) {
        if (generation != scanGeneration.get()) return
        if (step >= 8) {
            extractPage(generation, includeSource = true)
            return
        }

        // Move through the page in stages. This triggers many lazy-loaded/infinite-scroll pages.
        val js = """
            (function(){
                var h = Math.max(document.body ? document.body.scrollHeight : 0,
                                  document.documentElement ? document.documentElement.scrollHeight : 0);
                var y = Math.min(h, Math.max(0, h * ${(step + 1) / 8.0}));
                window.scrollTo(0, y);
                return h;
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) {
            // Capture DOM links at every stage, not only at the end.
            extractDomOnly(generation) {
                webView.postDelayed({ deepScrollAndScan(generation, step + 1) }, 600)
            }
        }
    }

    private fun extractDomOnly(generation: Int, done: () -> Unit) {
        if (generation != scanGeneration.get()) return
        webView.evaluateJavascript(domExtractionScript()) { result ->
            if (generation != scanGeneration.get()) return@evaluateJavascript
            parseJavascriptArray(result).forEach { mergeLinks(listOf(it)) }
            done()
        }
    }

    private fun extractPage(generation: Int, includeSource: Boolean) {
        if (generation != scanGeneration.get()) return
        status = "Collecting rendered links and page source…"
        val script = if (includeSource) fullExtractionScript() else domExtractionScript()
        webView.evaluateJavascript(script) { result ->
            if (generation != scanGeneration.get()) return@evaluateJavascript
            val links = parseJavascriptArray(result)
            mergeLinks(links)
            status = "${allLinks.size} unique HTTP(S) links found."
            isBusy = false
        }
    }

    private fun domExtractionScript(): String = """
        (function(){
            const out=[];
            function add(v,source){
                if(!v) return;
                v=String(v).trim();
                if(!v || /^(javascript:|mailto:|tel:|#)/i.test(v)) return;
                try {
                    const u=new URL(v,document.baseURI).href;
                    if(/^https?:\\/\\//i.test(u)) out.push({url:u,source:source});
                    // Google-style redirect links: also expose their destination.
                    try {
                        const x=new URL(u);
                        if(/(^|\\.)google\\./i.test(x.hostname) && x.pathname==='/url'){
                            const q=x.searchParams.get('q')||x.searchParams.get('url');
                            if(q && /^https?:\\/\\//i.test(q)) out.push({url:q,source:'DOM'});
                        }
                    }catch(e){}
                } catch(e) {}
            }
            document.querySelectorAll('a[href],area[href]').forEach(e=>add(e.getAttribute('href'),'DOM'));
            document.querySelectorAll('[data-href],[data-url],[data-link],[data-target-url]').forEach(e=>{
                add(e.getAttribute('data-href'),'DOM'); add(e.getAttribute('data-url'),'DOM');
                add(e.getAttribute('data-link'),'DOM'); add(e.getAttribute('data-target-url'),'DOM');
            });
            document.querySelectorAll('[onclick]').forEach(e=>{
                const s=e.getAttribute('onclick')||'';
                const r=/https?:\\/\\/[^\\s"'<>\\\\)]+/gi; let m;
                while((m=r.exec(s))!==null) add(m[0],'DOM');
            });
            return JSON.stringify(out);
        })();
    """.trimIndent()

    private fun fullExtractionScript(): String = """
        (function(){
            const out=[];
            function add(v,source){
                if(!v) return;
                v=String(v).trim().replace(/\\\\\\//g,'/');
                v=v.replace(/[.,;:)]$/,'');
                if(!v || /^(javascript:|mailto:|tel:|#)/i.test(v)) return;
                try {
                    const u=new URL(v,document.baseURI).href;
                    if(/^https?:\\/\\//i.test(u)) out.push({url:u,source:source});
                } catch(e) {}
            }
            document.querySelectorAll('a[href],area[href]').forEach(e=>add(e.getAttribute('href'),'DOM'));
            document.querySelectorAll('[data-href],[data-url],[data-link],[data-target-url]').forEach(e=>{
                add(e.getAttribute('data-href'),'DOM'); add(e.getAttribute('data-url'),'DOM');
                add(e.getAttribute('data-link'),'DOM'); add(e.getAttribute('data-target-url'),'DOM');
            });
            const html=document.documentElement ? document.documentElement.outerHTML : '';
            const scripts=Array.from(document.scripts).map(s=>s.textContent||'').join('\\n');
            const re=/https?:\\/\\/[^\\s"'<>\\\\]+/gi;
            let m;
            while((m=re.exec(html))!==null) add(m[0],'SOURCE');
            re.lastIndex=0;
            while((m=re.exec(scripts))!==null) add(m[0],'SCRIPT');
            return JSON.stringify(out);
        })();
    """.trimIndent()

    private fun parseJavascriptArray(result: String): List<ExtractedLink> {
        return runCatching {
            val jsonText = org.json.JSONTokener(result).nextValue() as? String ?: return@runCatching emptyList()
            val arr = JSONArray(jsonText)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("url")
                    val source = when (obj.optString("source")) {
                        "SCRIPT" -> LinkSource.SCRIPT
                        "SOURCE" -> LinkSource.SOURCE
                        else -> LinkSource.DOM
                    }
                    add(ExtractedLink(url, source))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun mergeLinks(incoming: List<ExtractedLink>) {
        val seen = allLinks.map { it.url }.toMutableSet()
        for (item in incoming) {
            val normalized = normalizeHttpUrl(item.url) ?: continue
            if (!includeSource && item.source != LinkSource.DOM) continue
            if (seen.add(normalized)) allLinks.add(ExtractedLink(normalized, item.source))
        }
    }

    private fun normalizeHttpUrl(raw: String): String? {
        var value = raw.trim()
            .replace("\\\\/", "/")
            .replace("\\u0026", "&")
        if (value.length > 8192) return null
        return runCatching {
            val u = URI(value)
            val scheme = u.scheme?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return null
            if (u.host.isNullOrBlank()) return null
            u.toASCIIString()
        }.getOrNull()
    }

    private fun filteredLinks(): List<ExtractedLink> = allLinks.filter {
        (!httpsOnly || it.url.startsWith("https://", true)) &&
        (!externalOnly || isExternal(it.url)) &&
        (includeSource || it.source == LinkSource.DOM) &&
        (searchText.isBlank() || it.url.contains(searchText, true))
    }

    private fun isExternal(url: String): Boolean {
        val base = runCatching { URI(pageUrl).host?.lowercase(Locale.US) }.getOrNull() ?: return true
        val host = runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull() ?: return true
        return host != base && !host.endsWith(".$base")
    }

    private fun copyLinks() {
        val visible = filteredLinks()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Extracted links", visible.joinToString("\n") { it.url }))
        status = "${visible.size} links copied."
    }

    private fun startExport(csv: Boolean) {
        pendingExportType = if (csv) "csv" else "txt"
        if (csv) createCsv.launch("links.csv") else createTxt.launch("links.txt")
    }

    private fun writeExport(uri: Uri, csv: Boolean) {
        val visible = filteredLinks()
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                requireNotNull(writer) { "Could not open output file" }
                if (csv) {
                    writer.write("URL,Source\n")
                    visible.forEach { writer.write("\"${it.url.replace("\"", "\"\"")}\",${it.source.name}\n") }
                } else {
                    visible.forEach { writer.write(it.url); writer.newLine() }
                }
            }
            status = "Exported ${visible.size} links."
        }.onFailure { status = "Export failed: ${it.message ?: "unknown error"}" }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun AppUi() {
        Scaffold(topBar = { TopAppBar(title = { Text("Link Extractor Pro") }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Browser") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Links (${allLinks.size})") })
                }
                if (tab == 0) BrowserPane() else ResultsPane()
            }
        }
    }

    @Composable
    private fun BrowserPane() {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    singleLine = true,
                    label = { Text("Webpage URL") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { loadPage(addressText) }, enabled = !isBusy) { Text("Go") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = deepScan, onCheckedChange = { deepScan = it })
                Text("Deep scan", Modifier.padding(end = 16.dp))
                Text("Find lazy/infinite-scroll links", style = MaterialTheme.typography.bodySmall)
            }
            if (isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }

    @Composable
    private fun ResultsPane() {
        val visible = filteredLinks()
        val context = LocalContext.current
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("Search extracted URLs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = httpsOnly, onCheckedChange = { httpsOnly = it }); Text("HTTPS only")
                Spacer(Modifier.weight(1f))
                Checkbox(checked = externalOnly, onCheckedChange = { externalOnly = it }); Text("External only")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { copyLinks() }, enabled = visible.isNotEmpty()) { Text("Copy All") }
                OutlinedButton(onClick = { startExport(false) }, enabled = visible.isNotEmpty()) { Text("TXT") }
                OutlinedButton(onClick = { startExport(true) }, enabled = visible.isNotEmpty()) { Text("CSV") }
                OutlinedButton(onClick = { allLinks.clear(); status = "Results cleared." }) { Text("Clear") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { tab = 0; if (pageUrl.isNotBlank()) startScan() }, enabled = pageUrl.isNotBlank() && !isBusy) { Text("Scan Again") }
                OutlinedButton(onClick = { includeSource = !includeSource }, enabled = !isBusy) { Text(if (includeSource) "Source: ON" else "Source: OFF") }
            }
            Text("Showing ${visible.size} of ${allLinks.size} unique links", Modifier.padding(vertical = 7.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.url }) { item ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(item.url, style = MaterialTheme.typography.bodyMedium)
                            Text(item.source.name, style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { webView.loadUrl(item.url); tab = 0 }) { Text("Open") }
                                OutlinedButton(onClick = {
                                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("Link", item.url))
                                    status = "Link copied."
                                }) { Text("Copy") }
                            }
                        }
                    }
                }
            }
        }
    }
}

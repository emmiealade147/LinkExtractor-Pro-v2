package com.linkextractor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URI

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val links = mutableStateListOf<String>()
    private var pageUrl by mutableStateOf("")
    private var status by mutableStateOf("Enter a URL and tap Scan")
    private var httpsOnly by mutableStateOf(false)
    private var externalOnly by mutableStateOf(false)
    private var tab by mutableIntStateOf(0)
    private var search by mutableStateOf("")

    private val saveTxt = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) writeFile(uri, links.joinToString("\n"))
    }

    private val saveCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            writeFile(
                uri,
                "URL\n" + links.joinToString("\n") {
                    "\"" + it.replace("\"", "\"\"") + "\""
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageUrl = url ?: ""
                status = "Page loaded. Scanning..."
                scanPage()
            }
        }

        setContent {
            App()
        }

        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val url = i?.data?.toString()

        if (!url.isNullOrBlank()) {
            pageUrl = url
            web.loadUrl(url)
        }
    }

    private fun scanPage() {
        web.evaluateJavascript(
            """
            (function() {
                var out = [];

                document.querySelectorAll('a[href], area[href]')
                    .forEach(function(e) {
                        out.push(e.href);
                    });

                document.querySelectorAll('[data-url],[data-href],[data-link]')
                    .forEach(function(e) {
                        if (e.dataset.url) out.push(e.dataset.url);
                        if (e.dataset.href) out.push(e.dataset.href);
                        if (e.dataset.link) out.push(e.dataset.link);
                    });

                var html = document.documentElement.outerHTML;

                var re = /https?:\/\/[^\s"'<>\\]+/gi;
                var m;

                while ((m = re.exec(html)) !== null) {
                    out.push(m[0]);
                }

                return JSON.stringify(out);
            })()
            """.trimIndent()
        ) { result: String ->

            try {
                val jsonText = JSONTokener(result).nextValue() as String
                val arr = JSONArray(jsonText)

                val found = mutableListOf<String>()

                for (i in 0 until arr.length()) {
                    val value = arr.optString(i)
                    val clean = normalizeUrl(value)

                    if (clean.isNotEmpty()) {
                        found.add(clean)
                    }
                }

                links.clear()
                links.addAll(found.distinct().sorted())

                status = "${links.size} valid links found"

            } catch (e: Exception) {
                status = "Scan error: ${e.message}"
            }
        }
    }

    private fun normalizeUrl(value: String): String {
        var s = value.trim()

        s = s.replace("\\/", "/")

        if (s.startsWith("http://") || s.startsWith("https://")) {
            s = s.substringBefore("\"")
            s = s.substringBefore("'")
            s = s.substringBefore(">")
            return s
        }

        return ""
    }

    private fun filteredLinks(): List<String> {
        return links.filter { url ->

            val httpsOk = !httpsOnly || url.startsWith("https://")

            val externalOk =
                if (!externalOnly) {
                    true
                } else {
                    try {
                        val pageHost = URI(pageUrl).host
                        val linkHost = URI(url).host
                        !pageHost.isNullOrBlank() &&
                                !linkHost.isNullOrBlank() &&
                                pageHost != linkHost
                    } catch (_: Exception) {
                        false
                    }
                }

            val searchOk =
                search.isBlank() ||
                        url.contains(search, ignoreCase = true)

            httpsOk && externalOk && searchOk
        }
    }

    private fun deepScan() {
        status = "Deep scanning..."

        var count = 0

        fun scrollMore() {
            if (count >= 6) {
                scanPage()
                return
            }

            count++

            web.evaluateJavascript(
                "window.scrollTo(0, document.body.scrollHeight);",
                null
            )

            Handler(Looper.getMainLooper()).postDelayed(
                { scrollMore() },
                900
            )
        }

        scrollMore()
    }

    private fun writeFile(uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray())
            }

            status = "Export completed"

        } catch (e: Exception) {
            status = "Export failed: ${e.message}"
        }
    }

    private fun copyAll() {
        val text = filteredLinks().joinToString("\n")

        val clipboard =
            getSystemService(CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "Extracted Links",
                text
            )
        )

        status = "${filteredLinks().size} links copied"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {

        val visibleLinks = filteredLinks()

        MaterialTheme {

            Column(
                modifier = Modifier.fillMaxSize()
            ) {

                TopAppBar(
                    title = {
                        Text("Link Extractor Pro")
                    }
                )

                TabRow(selectedTabIndex = tab) {

                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = {
                            Text("Browser")
                        }
                    )

                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = {
                            Text("Links (${visibleLinks.size})")
                        }
                    )
                }

                if (tab == 0) {
                    BrowserScreen()
                } else {
                    LinksScreen(visibleLinks)
                }
            }
        }
    }

    @Composable
    private fun BrowserScreen() {

        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {

                Button(
                    onClick = {
                        if (pageUrl.isNotBlank()) {
                            web.loadUrl(pageUrl)
                        }
                    }
                ) {
                    Text("Scan Again")
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        deepScan()
                    }
                ) {
                    Text("Deep Scan")
                }
            }

            Text(
                text = status,
                modifier = Modifier.padding(8.dp)
            )

            AndroidView(
                factory = {
                    web
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    @Composable
    private fun LinksScreen(items: List<String>) {

        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {

                Button(
                    onClick = {
                        copyAll()
                    }
                ) {
                    Text("Copy All")
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        saveTxt.launch("links.txt")
                    }
                ) {
                    Text("TXT")
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        saveCsv.launch("links.csv")
                    }
                ) {
                    Text("CSV")
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = {
                    search = it
                },
                label = {
                    Text("Search links")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )

            Row(
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {

                Checkbox(
                    checked = httpsOnly,
                    onCheckedChange = {
                        httpsOnly = it
                    }
                )

                Text(
                    "HTTPS only",
                    modifier = Modifier.padding(top = 12.dp)
                )

                Spacer(Modifier.width(16.dp))

                Checkbox(
                    checked = externalOnly,
                    onCheckedChange = {
                        externalOnly = it
                    }
                )

                Text(
                    "External only",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Text(
                text = "${items.size} links",
                modifier = Modifier.padding(8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {

                items(items) { url ->

                    Text(
                        text = url,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        )
                    )

                    HorizontalDivider()
                }
            }
        }
    }
}

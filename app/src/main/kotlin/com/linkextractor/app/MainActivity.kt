package com.linkextractor.app

import android.content.ClipData
import android.content.ClipboardManager
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

    private var browser: WebView? = null

    private val links = mutableStateListOf<String>()

    private var currentUrl by mutableStateOf("")
    private var status by mutableStateOf("Enter a website address")
    private var tab by mutableIntStateOf(0)
    private var searchText by mutableStateOf("")
    private var httpsOnly by mutableStateOf(false)
    private var externalOnly by mutableStateOf(false)

    private val saveTxt = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            writeFile(uri, links.joinToString("\n"))
        }
    }

    private val saveCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = "URL\n" + links.joinToString("\n") {
                "\"" + it.replace("\"", "\"\"") + "\""
            }
            writeFile(uri, csv)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }

        val incoming = intent?.data?.toString()

        if (!incoming.isNullOrBlank()) {
            currentUrl = incoming
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {

        MaterialTheme {

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text("Link Extractor Pro")
                        }
                    )
                }
            ) { padding ->

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {

                    TabRow(
                        selectedTabIndex = tab
                    ) {

                        Tab(
                            selected = tab == 0,
                            onClick = {
                                tab = 0
                            },
                            text = {
                                Text("Browser")
                            }
                        )

                        Tab(
                            selected = tab == 1,
                            onClick = {
                                tab = 1
                            },
                            text = {
                                Text("Links (${filteredLinks().size})")
                            }
                        )
                    }

                    if (tab == 0) {
                        BrowserScreen()
                    } else {
                        LinksScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun BrowserScreen() {

        var address by remember {
            mutableStateOf(currentUrl)
        }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {

                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                    },
                    label = {
                        Text("Website URL")
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Button(
                    onClick = {

                        var url = address.trim()

                        if (
                            !url.startsWith("http://") &&
                            !url.startsWith("https://")
                        ) {
                            url = "https://$url"
                        }

                        currentUrl = url
                        status = "Loading page..."

                        browser?.loadUrl(url)
                    }
                ) {
                    Text("Go")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {

                Button(
                    onClick = {
                        browser?.reload()
                    }
                ) {
                    Text("Scan Again")
                }

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

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
                factory = { context ->

                    WebView(context).apply {

                        browser = this

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.databaseEnabled = true

                        webViewClient = object : WebViewClient() {

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?
                            ) {

                                currentUrl = url ?: currentUrl
                                status = "Page loaded. Scanning..."

                                Handler(
                                    Looper.getMainLooper()
                                ).postDelayed(
                                    {
                                        scanPage()
                                    },
                                    1200
                                )
                            }
                        }

                        if (currentUrl.isNotBlank()) {
                            loadUrl(currentUrl)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    @Composable
    private fun LinksScreen() {

        val visible = filteredLinks()

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

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Button(
                    onClick = {
                        saveTxt.launch("extracted-links.txt")
                    }
                ) {
                    Text("TXT")
                }

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Button(
                    onClick = {
                        saveCsv.launch("extracted-links.csv")
                    }
                ) {
                    Text("CSV")
                }
            }

            OutlinedTextField(
                value = searchText,
                onValueChange = {
                    searchText = it
                },
                label = {
                    Text("Search links")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier.padding(
                    horizontal = 8.dp
                )
            ) {

                Checkbox(
                    checked = httpsOnly,
                    onCheckedChange = {
                        httpsOnly = it
                    }
                )

                Text(
                    text = "HTTPS only",
                    modifier = Modifier.padding(top = 12.dp)
                )

                Spacer(
                    modifier = Modifier.width(12.dp)
                )

                Checkbox(
                    checked = externalOnly,
                    onCheckedChange = {
                        externalOnly = it
                    }
                )

                Text(
                    text = "External only",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Text(
                text = "${visible.size} links found",
                modifier = Modifier.padding(8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {

                items(visible) { url ->

                    Text(
                        text = url,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 7.dp
                        )
                    )

                    HorizontalDivider()
                }
            }
        }
    }

    private fun scanPage() {

        val w = browser ?: return

        w.evaluateJavascript(
            """
            (function() {

                var result = [];

                document
                    .querySelectorAll('a[href], area[href]')
                    .forEach(function(e) {
                        result.push(e.href);
                    });

                document
                    .querySelectorAll(
                        '[data-url],[data-href],[data-link]'
                    )
                    .forEach(function(e) {

                        if (e.dataset.url)
                            result.push(e.dataset.url);

                        if (e.dataset.href)
                            result.push(e.dataset.href);

                        if (e.dataset.link)
                            result.push(e.dataset.link);
                    });

                var html =
                    document.documentElement.outerHTML;

                var regex =
                    /https?:\/\/[^\s"'<>\\]+/gi;

                var match;

                while (
                    (match = regex.exec(html)) !== null
                ) {
                    result.push(match[0]);
                }

                return JSON.stringify(result);

            })()
            """.trimIndent()
        ) { raw: String ->

            try {

                val json =
                    JSONTokener(raw).nextValue() as String

                val array = JSONArray(json)

                val found = mutableListOf<String>()

                for (i in 0 until array.length()) {

                    val value =
                        array.optString(i)

                    val clean =
                        cleanUrl(value)

                    if (clean.isNotEmpty()) {
                        found.add(clean)
                    }
                }

                links.clear()

                links.addAll(
                    found
                        .distinct()
                        .sorted()
                )

                status =
                    "${links.size} valid links found"

            } catch (e: Exception) {

                status =
                    "Extraction error: ${e.message}"
            }
        }
    }

    private fun cleanUrl(value: String): String {

        var url = value.trim()

        url = url.replace("\\/", "/")

        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {
            return ""
        }

        url = url.substringBefore("\"")
        url = url.substringBefore("'")
        url = url.substringBefore("<")
        url = url.substringBefore(">")

        return url
    }

    private fun filteredLinks(): List<String> {

        return links.filter { url ->

            val httpsPass =
                !httpsOnly ||
                        url.startsWith("https://")

            val searchPass =
                searchText.isBlank() ||
                        url.contains(
                            searchText,
                            ignoreCase = true
                        )

            val externalPass =
                if (!externalOnly) {
                    true
                } else {
                    try {

                        val pageHost =
                            URI(currentUrl).host

                        val linkHost =
                            URI(url).host

                        !pageHost.isNullOrBlank() &&
                                !linkHost.isNullOrBlank() &&
                                pageHost != linkHost

                    } catch (_: Exception) {
                        false
                    }
                }

            httpsPass &&
                    searchPass &&
                    externalPass
        }
    }

    private fun deepScan() {

        status = "Deep scanning page..."

        var count = 0

        fun scroll() {

            if (count >= 7) {

                scanPage()
                return
            }

            count++

            browser?.evaluateJavascript(
                "window.scrollTo(0,document.body.scrollHeight);",
                null
            )

            Handler(
                Looper.getMainLooper()
            ).postDelayed(
                {
                    scroll()
                },
                1000
            )
        }

        scroll()
    }

    private fun copyAll() {

        val text =
            filteredLinks()
                .joinToString("\n")

        val clipboard =
            getSystemService(
                CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Extracted Links",
                text
            )
        )

        status =
            "${filteredLinks().size} links copied"
    }

    private fun writeFile(
        uri: Uri,
        text: String
    ) {

        try {

            contentResolver
                .openOutputStream(uri)
                ?.use { output ->

                    output.write(
                        text.toByteArray()
                    )
                }

            status =
                "File exported successfully"

        } catch (e: Exception) {

            status =
                "Export failed: ${e.message}"
        }
    }

    override fun onDestroy() {

        browser?.destroy()
        browser = null

        super.onDestroy()
    }
}

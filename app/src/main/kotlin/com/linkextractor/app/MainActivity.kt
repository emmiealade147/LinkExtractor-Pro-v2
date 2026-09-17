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
import org.json.JSONTokener
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private enum class LinkSource {
    DOM,
    SOURCE,
    SCRIPT
}

private data class ExtractedLink(
    val url: String,
    val source: LinkSource
)

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    private val allLinks = mutableStateListOf<ExtractedLink>()

    private var pageUrl by mutableStateOf("")

    private var addressText by mutableStateOf("")

    private var status by mutableStateOf(
        "Open a webpage or share one from Chrome."
    )

    private var isBusy by mutableStateOf(false)

    private var deepScan by mutableStateOf(true)

    private var httpsOnly by mutableStateOf(false)

    private var externalOnly by mutableStateOf(false)

    private var includeSource by mutableStateOf(true)

    private var searchText by mutableStateOf("")

    private var tab by mutableStateOf(0)

    private var pendingExportType by mutableStateOf("")

    private val scanGeneration = AtomicInteger(0)

    private val createTxt =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->

            if (uri != null && pendingExportType == "txt") {
                writeExport(uri, false)
            }
        }

    private val createCsv =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->

            if (uri != null && pendingExportType == "csv") {
                writeExport(uri, true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setupWebView()

        handleIntent(intent)

        setContent {
            AppUi()
        }
    }

    override fun onNewIntent(intent: Intent) {

        super.onNewIntent(intent)

        setIntent(intent)

        handleIntent(intent)
    }

    override fun onBackPressed() {

        if (::webView.isInitialized && webView.canGoBack()) {

            webView.goBack()

        } else {

            super.onBackPressed()
        }
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

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {

                    pageUrl = url.orEmpty()

                    addressText = url.orEmpty()

                    status = "Loading page…"

                    allLinks.clear()
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {

                    pageUrl = url.orEmpty()

                    addressText = url.orEmpty()

                    status = "Page loaded. Scanning links…"

                    startScan()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {

                    return false
                }
            }

            webChromeClient = WebChromeClient()
        }
    }

    private fun handleIntent(intent: Intent?) {

        val candidate = when (intent?.action) {

            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
            }

            else -> {
                intent?.dataString
                    ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
            }
        }

        val url = extractUrl(candidate.orEmpty()) ?: return

        addressText = url

        loadPage(url)
    }

    private fun extractUrl(text: String): String? {

        return Regex(
            "https?://[^\\s<>\\\"']+"
        )
            .find(text)
            ?.value
            ?.trimEnd(
                '.',
                ',',
                ')',
                ']',
                ';'
            )
    }

    private fun loadPage(raw: String) {

        val normalized = normalizeHttpUrl(raw)

        if (normalized == null) {

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

        val generation =
            scanGeneration.incrementAndGet()

        isBusy = true

        if (deepScan) {

            deepScrollAndScan(
                generation,
                0
            )

        } else {

            extractPage(
                generation,
                includeSource = true
            )
        }
    }

    private fun deepScrollAndScan(
        generation: Int,
        step: Int
    ) {

        if (generation != scanGeneration.get()) {
            return
        }

        if (step >= 8) {

            extractPage(
                generation,
                includeSource = true
            )

            return
        }

        val progress =
            (step + 1) / 8.0

        val js = """
            (function() {

                var bodyHeight =
                    document.body
                        ? document.body.scrollHeight
                        : 0;

                var documentHeight =
                    document.documentElement
                        ? document.documentElement.scrollHeight
                        : 0;

                var height =
                    Math.max(
                        bodyHeight,
                        documentHeight
                    );

                var position =
                    Math.min(
                        height,
                        Math.max(
                            0,
                            height * $progress
                        )
                    );

                window.scrollTo(
                    0,
                    position
                );

                return height;

            })();
        """.trimIndent()

        webView.evaluateJavascript(js) {

            extractDomOnly(generation) {

                webView.postDelayed({

                    deepScrollAndScan(
                        generation,
                        step + 1
                    )

                }, 600)
            }
        }
    }

    private fun extractDomOnly(
        generation: Int,
        done: () -> Unit
    ) {

        if (generation != scanGeneration.get()) {
            return
        }

        webView.evaluateJavascript(
            domExtractionScript()
        ) { result ->

            if (generation != scanGeneration.get()) {
                return@evaluateJavascript
            }

            val links =
                parseJavascriptArray(result)

            mergeLinks(links)

            done()
        }
    }

    private fun extractPage(
        generation: Int,
        includeSource: Boolean
    ) {

        if (generation != scanGeneration.get()) {
            return
        }

        status =
            "Collecting rendered links and page source…"

        val script =
            if (includeSource) {
                fullExtractionScript()
            } else {
                domExtractionScript()
            }

        webView.evaluateJavascript(
            script
        ) { result ->

            if (generation != scanGeneration.get()) {
                return@evaluateJavascript
            }

            val links =
                parseJavascriptArray(result)

            mergeLinks(links)

            status =
                "${allLinks.size} unique HTTP(S) links found."

            isBusy = false
        }
    }

    private fun domExtractionScript(): String {

        return """
            (function() {

                const out = [];

                function add(value, source) {

                    if (!value) {
                        return;
                    }

                    let valueString =
                        String(value).trim();

                    if (!valueString) {
                        return;
                    }

                    if (
                        /^(javascript:|mailto:|tel:|#)/i
                            .test(valueString)
                    ) {
                        return;
                    }

                    try {

                        const url =
                            new URL(
                                valueString,
                                document.baseURI
                            ).href;

                        if (
                            /^https?:\/\//i
                                .test(url)
                        ) {

                            out.push({
                                url: url,
                                source: source
                            });
                        }

                        try {

                            const parsed =
                                new URL(url);

                            if (
                                /(^|\.)google\./i
                                    .test(parsed.hostname)
                                &&
                                parsed.pathname === "/url"
                            ) {

                                const destination =
                                    parsed.searchParams.get("q")
                                    ||
                                    parsed.searchParams.get("url");

                                if (
                                    destination
                                    &&
                                    /^https?:\/\//i
                                        .test(destination)
                                ) {

                                    out.push({
                                        url: destination,
                                        source: "DOM"
                                    });
                                }
                            }

                        } catch (googleError) {
                        }

                    } catch (error) {
                    }
                }

                document
                    .querySelectorAll(
                        "a[href], area[href]"
                    )
                    .forEach(function(element) {

                        add(
                            element.getAttribute("href"),
                            "DOM"
                        );
                    });

                document
                    .querySelectorAll(
                        "[data-href], [data-url], [data-link], [data-target-url]"
                    )
                    .forEach(function(element) {

                        add(
                            element.getAttribute("data-href"),
                            "DOM"
                        );

                        add(
                            element.getAttribute("data-url"),
                            "DOM"
                        );

                        add(
                            element.getAttribute("data-link"),
                            "DOM"
                        );

                        add(
                            element.getAttribute("data-target-url"),
                            "DOM"
                        );
                    });

                document
                    .querySelectorAll("[onclick]")
                    .forEach(function(element) {

                        const code =
                            element.getAttribute("onclick")
                            || "";

                        const regex =
                            /https?:\/\/[^\s"'<>\\)]+/gi;

                        let match;

                        while (
                            (match = regex.exec(code))
                            !== null
                        ) {

                            add(
                                match[0],
                                "DOM"
                            );
                        }
                    });

                return JSON.stringify(out);

            })();
        """.trimIndent()
    }

    private fun fullExtractionScript(): String {

        return """
            (function() {

                const out = [];

                function add(value, source) {

                    if (!value) {
                        return;
                    }

                    let valueString =
                        String(value)
                            .trim()
                            .replace(/\\\//g, "/");

                    valueString =
                        valueString.replace(
                            /[.,;:)]$/,
                            ""
                        );

                    if (!valueString) {
                        return;
                    }

                    if (
                        /^(javascript:|mailto:|tel:|#)/i
                            .test(valueString)
                    ) {
                        return;
                    }

                    try {

                        const url =
                            new URL(
                                valueString,
                                document.baseURI
                            ).href;

                        if (
                            /^https?:\/\//i
                                .test(url)
                        ) {

                            out.push({
                                url: url,
                                source: source
                            });
                        }

                    } catch (error) {
                    }
                }

                document
                    .querySelectorAll(
                        "a[href], area[href]"
                    )
                    .forEach(function(element) {

                        add(
                            element.getAttribute("href"),
                            "DOM"
                        );
                    });

                document
                    .querySelectorAll(
                        "[data-href], [data-url], [data-link], [data-target-url]"
                    )
                    .forEach(function(element) {

                        add(
                            element.getAttribute("data-href"),
                            "DOM"
                        );

                        add(
                            element.getAttribute("data-url"),
                            "DOM"
                        );

                        add(
                            element.getAttribute("data-link"),
                            "DOM"
                        );

                        add(
                            element.getAttribute("data-target-url"),
                            "DOM"
                        );
                    });

                document
                    .querySelectorAll("[onclick]")
                    .forEach(function(element) {

                        const code =
                            element.getAttribute("onclick")
                            || "";

                        const regex =
                            /https?:\/\/[^\s"'<>\\)]+/gi;

                        let match;

                        while (
                            (match = regex.exec(code))
                            !== null
                        ) {

                            add(
                                match[0],
                                "DOM"
                            );
                        }
                    });

                const html =
                    document.documentElement
                        ? document.documentElement.outerHTML
                        : "";

                const scripts =
                    Array.from(
                        document.scripts
                    )
                    .map(function(script) {

                        return script.textContent || "";

                    })
                    .join("\n");

                const regex =
                    /https?:\/\/[^\s"'<>\\]+/gi;

                let match;

                while (
                    (match = regex.exec(html))
                    !== null
                ) {

                    add(
                        match[0],
                        "SOURCE"
                    );
                }

                regex.lastIndex = 0;

                while (
                    (match = regex.exec(scripts))
                    !== null
                ) {

                    add(
                        match[0],
                        "SCRIPT"
                    );
                }

                return JSON.string

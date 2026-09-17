package com.linkextractor.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URI

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var status: TextView
    private lateinit var countText: TextView
    private lateinit var results: TextView

    private val links = mutableSetOf<String>()

    private var httpsOnly = false
    private var externalOnly = false
    private var currentUrl = ""

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildScreen()

        val incomingUrl = getIncomingUrl()

        if (!incomingUrl.isNullOrBlank()) {
            urlBox.setText(incomingUrl)
            loadPage(incomingUrl)
        }
    }

    private fun getIncomingUrl(): String? {

        val dataUrl = intent?.dataString

        if (!dataUrl.isNullOrBlank()) {
            return dataUrl
        }

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)

        if (!sharedText.isNullOrBlank()) {
            val match = Regex(
                """https?://[^\s<>"']+"""
            ).find(sharedText)

            return match?.value ?: sharedText.trim()
        }

        return null
    }

    private fun buildScreen() {

        val root = LinearLayout(this)

        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val title = TextView(this)

        title.text = "Link Extractor Pro"
        title.textSize = 26f
        title.setTextColor(Color.rgb(30, 20, 30))
        title.setPadding(20, 18, 20, 18)

        root.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val urlRow = LinearLayout(this)

        urlRow.orientation = LinearLayout.HORIZONTAL
        urlRow.setPadding(10, 5, 10, 5)

        urlBox = EditText(this)

        urlBox.hint = "https://example.com"
        urlBox.setSingleLine(true)

        urlRow.addView(
            urlBox,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        val go = Button(this)

        go.text = "GO"

        go.setOnClickListener {

            var url = urlBox.text.toString().trim()

            if (url.isBlank()) {

                status.text = "Enter a website address"

                return@setOnClickListener
            }

            if (
                !url.startsWith("http://") &&
                !url.startsWith("https://")
            ) {
                url = "https://$url"
            }

            loadPage(url)
        }

        urlRow.addView(
            go,
            LinearLayout.LayoutParams(
                -2,
                -2
            )
        )

        root.addView(urlRow)

        val buttonRow = LinearLayout(this)

        buttonRow.gravity = Gravity.CENTER_VERTICAL
        buttonRow.setPadding(10, 2, 10, 2)

        val scan = Button(this)

        scan.text = "Scan"

        scan.setOnClickListener {
            scanPage()
        }

        buttonRow.addView(scan)

        val deep = Button(this)

        deep.text = "Deep Scan"

        deep.setOnClickListener {
            deepScan()
        }

        buttonRow.addView(deep)

        val copy = Button(this)

        copy.text = "Copy All"

        copy.setOnClickListener {
            copyLinks()
        }

        buttonRow.addView(copy)

        val clear = Button(this)

        clear.text = "Clear"

        clear.setOnClickListener {

            links.clear()

            showResults()

            status.text = "Links cleared"
        }

        buttonRow.addView(clear)

        root.addView(buttonRow)

        val filterRow = LinearLayout(this)

        filterRow.orientation = LinearLayout.HORIZONTAL
        filterRow.setPadding(10, 2, 10, 2)

        val https = CheckBox(this)

        https.text = "HTTPS only"

        https.setOnCheckedChangeListener { _, checked ->

            httpsOnly = checked

            showResults()
        }

        filterRow.addView(https)

        val external = CheckBox(this)

        external.text = "External only"

        external.setOnCheckedChangeListener { _, checked ->

            externalOnly = checked

            showResults()
        }

        filterRow.addView(external)

        root.addView(filterRow)

        status = TextView(this)

        status.text = "Enter a URL and tap GO"
        status.textSize = 15f
        status.setTextColor(Color.DKGRAY)
        status.setPadding(12, 6, 12, 6)

        root.addView(
            status,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        countText = TextView(this)

        countText.text = "0 links found"
        countText.textSize = 18f
        countText.setTextColor(Color.BLACK)
        countText.setPadding(12, 4, 12, 8)

        root.addView(countText)

        web = WebView(this)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.settings.databaseEnabled = true

        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true

        web.webViewClient = object : WebViewClient() {

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {

                if (!url.isNullOrBlank()) {

                    currentUrl = url

                    urlBox.setText(url)

                    addLink(url)
                }

                status.text = "Page loaded. Scanning..."

                handler.postDelayed(
                    {
                        scanPage()
                    },
                    3000
                )
            }
        }

        root.addView(
            web,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        results = TextView(this)

        results.textSize = 14f
        results.setTextColor(Color.DKGRAY)
        results.setPadding(12, 12, 12, 12)

        val resultsScroll = ScrollView(this)

        resultsScroll.addView(results)

        root.addView(
            resultsScroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)

        showResults()
    }

    private fun addLink(value: String) {

        val clean = cleanUrl(value)

        if (clean.isNotEmpty()) {
            links.add(clean)
        }
    }

    private fun loadPage(url: String) {

        var finalUrl = url.trim()

        if (
            !finalUrl.startsWith("http://") &&
            !finalUrl.startsWith("https://")
        ) {
            finalUrl = "https://$finalUrl"
        }

        currentUrl = finalUrl

        urlBox.setText(finalUrl)

        status.text = "Loading page..."

        links.clear()

        addLink(finalUrl)

        showResults()

        web.loadUrl(finalUrl)
    }

    private fun scanPage() {

        if (!::web.isInitialized) {
            return
        }

        status.text = "Extracting links..."

        web.evaluateJavascript(
            """
            (function() {

                var found = [];

                function add(value) {

                    if (!value) return;

                    try {

                        var absolute =
                            new URL(value, document.baseURI).href;

                        if (
                            absolute.startsWith("http://") ||
                            absolute.startsWith("https://")
                        ) {
                            found.push(absolute);
                        }

                    } catch (e) {

                    }
                }

                document
                    .querySelectorAll("a[href], area[href]")
                    .forEach(function(e) {
                        add(e.getAttribute("href"));
                    });

                document
                    .querySelectorAll(
                        "[data-url], [data-href], [data-link], [data-src]"
                    )
                    .forEach(function(e) {

                        add(e.getAttribute("data-url"));
                        add(e.getAttribute("data-href"));
                        add(e.getAttribute("data-link"));
                        add(e.getAttribute("data-src"));
                    });

                document
                    .querySelectorAll("form[action]")
                    .forEach(function(e) {
                        add(e.getAttribute("action"));
                    });

                var html =
                    document.documentElement.outerHTML || "";

                var regex =
                    /https?:\/\/[^\s"'<>\\]+/gi;

                var match;

                while (
                    (match = regex.exec(html)) !== null
                ) {

                    var value = match[0];

                    value = value
                        .replace(/&amp;/g, "&")
                        .replace(/[),.;]+$/g, "");

                    found.push(value);
                }

                return JSON.stringify(
                    Array.from(new Set(found))
                );

            })()
            """.trimIndent()
        ) { result ->

            try {

                val json =
                    JSONTokener(result).nextValue() as String

                val array = JSONArray(json)

                for (i in 0 until array.length()) {

                    val value =
                        array.optString(i)

                    addLink(value)
                }

                if (currentUrl.isNotBlank()) {
                    addLink(currentUrl)
                }

                status.text = "Extraction complete"

                showResults()

            } catch (e: Exception) {

                status.text =
                    "Extraction error: " +
                    (e.message ?: "unknown error")
            }
        }
    }

    private fun cleanUrl(value: String): String {

        var url = value.trim()

        url = url.replace("\\/", "/")
        url = url.replace("&amp;", "&")

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

        url = url.replace(
            Regex("""[),.;]+$"""),
            ""
        )

        return url
    }

    private fun filtered(): List<String> {

        return links.filter { url ->

            val httpsOK =
                !httpsOnly ||
                url.startsWith("https://")

            val externalOK =
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
                        !linkHost.equals(
                            pageHost,
                            ignoreCase = true
                        )

                    } catch (_: Exception) {

                        false
                    }
                }

            httpsOK && externalOK

        }.sorted()
    }

    private fun showResults() {

        val list = filtered()

        countText.text =
            "${list.size} links found"

        if (list.isEmpty()) {

            results.text =
                "No links extracted yet."

            return
        }

        results.text =
            list.joinToString(
                separator = "\n\n"
            )
    }

    private fun copyLinks() {

        val list = filtered()

        if (list.isEmpty()) {

            status.text =
                "There are no links to copy"

            return
        }

        val text =
            list.joinToString("\n")

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

        status.text =
            "${list.size} links copied"
    }

    private fun deepScan() {

        if (currentUrl.isBlank()) {

            status.text =
                "Load a webpage first"

            return
        }

        status.text =
            "Deep scanning page..."

        var count = 0

        fun nextScroll() {

            if (count >= 8) {

                scanPage()

                status.text =
                    "Deep scan complete"

                return
            }

            count++

            web.evaluateJavascript(
                """
                (function() {
                    window.scrollTo(
                        0,
                        document.body.scrollHeight
                    );
                    return document.body.scrollHeight;
                })()
                """.trimIndent(),
                null
            )

            handler.postDelayed(
                {
                    scanPage()

                    handler.postDelayed(
                        {
                            nextScroll()
                        },
                        500
                    )
                },
                1000
            )
        }

        nextScroll()
    }

    override fun onBackPressed() {

        if (web.canGoBack()) {

            web.goBack()

        } else {

            super.onBackPressed()
        }
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        if (::web.isInitialized) {
            web.destroy()
        }

        super.onDestroy()
    }
}

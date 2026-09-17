package com.linkextractor.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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

    private val links = mutableListOf<String>()

    private var httpsOnly = false
    private var externalOnly = false
    private var currentUrl = ""

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildScreen()

        val incoming = intent?.data?.toString()

        if (!incoming.isNullOrBlank()) {
            urlBox.setText(incoming)
            loadPage(incoming)
        }
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

        countText.text = "0 links"
        countText.textSize = 18f
        countText.setTextColor(Color.BLACK)
        countText.setPadding(12, 4, 12, 8)

        root.addView(countText)

        web = WebView(this)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.settings.databaseEnabled = true

        web.webViewClient = object : WebViewClient() {

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {

                if (!url.isNullOrBlank()) {
                    currentUrl = url
                    urlBox.setText(url)
                }

                status.text = "Page loaded. Scanning..."

                handler.postDelayed(
                    {
                        scanPage()
                    },
                    1500
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

        val scroll = ScrollView(this)

        scroll.addView(results)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                0f
            )
        )

        setContentView(root)
    }

    private fun loadPage(url: String) {

        currentUrl = url

urlBox.setText(url)

status.text = "Loading page..."

links.clear()

links.add(url)

showResults()

web.loadUrl(url)
    }

    private fun scanPage() {

        status.text = "Extracting links..."

        web.evaluateJavascript(
            """
            (function() {

                var found = [];

                document
                    .querySelectorAll("a[href], area[href]")
                    .forEach(function(e) {
                        if (e.href) {
                            found.push(e.href);
                        }
                    });

                document
                    .querySelectorAll(
                        "[data-url],[data-href],[data-link]"
                    )
                    .forEach(function(e) {

                        if (e.dataset.url)
                            found.push(e.dataset.url);

                        if (e.dataset.href)
                            found.push(e.dataset.href);

                        if (e.dataset.link)
                            found.push(e.dataset.link);
                    });

                var html =
                    document.documentElement.outerHTML;

                var regex =
                    /https?:\/\/[^\s"'<>\\]+/gi;

                var match;

                while (
                    (match = regex.exec(html)) !== null
                ) {
                    found.push(match[0]);
                }

                return JSON.stringify(found);

            })()
            """.trimIndent()
        ) { result ->

            try {

                val json =
                    JSONTokener(result).nextValue() as String

                val array = JSONArray(json)

                val newLinks =
                    mutableListOf<String>()

                for (i in 0 until array.length()) {

                    val value =
                        array.optString(i)

                    val clean =
                        cleanUrl(value)

                    if (clean.isNotEmpty()) {
                        newLinks.add(clean)
                    }
                }

                links.clear()

                links.addAll(
                    newLinks
                        .distinct()
                        .sorted()
                )

                status.text =
                    "Extraction complete"

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
                        pageHost != linkHost

                    } catch (_: Exception) {
                        false
                    }
                }

            httpsOK && externalOK
        }
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
            "Deep scanning..."

        var count = 0

        fun nextScroll() {

            if (count >= 8) {

                scanPage()
                return
            }

            count++

            web.evaluateJavascript(
                "window.scrollTo(0,document.body.scrollHeight);",
                null
            )

            handler.postDelayed(
                {
                    nextScroll()
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

        web.destroy()

        super.onDestroy()
    }
}

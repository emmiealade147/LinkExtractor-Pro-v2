# Link Extractor Pro v2

A professional Android webpage link extractor built with Kotlin, Jetpack Compose and WebView.

## What it does

- Opens and renders webpages inside the app.
- Extracts HTTP/HTTPS links from rendered `<a>` and `<area>` elements.
- Resolves relative URLs to absolute URLs.
- Checks common `data-*` link attributes and inline `onclick` URLs.
- Scans page HTML and inline JavaScript for additional absolute URLs.
- Deep Scan scrolls through the page in stages to trigger lazy-loaded/infinite-scroll content.
- Removes duplicate URLs while preserving discovery order.
- HTTPS-only and External-only filters.
- URL search.
- Copy All, TXT export and CSV export.
- Open or copy an individual extracted URL.
- Accepts Chrome/browser Share and normal HTTP/HTTPS VIEW intents.

## Build with GitHub

1. Create an empty GitHub repository.
2. Upload **the contents of this ZIP** to the repository root. Do not upload the ZIP itself.
3. Commit to the `main` branch.
4. Open **Actions** → **Build Android APK**.
5. Run the workflow or push to `main`.
6. Download `LinkExtractor-Pro-v2-debug-apk` from the completed workflow's Artifacts section.

The workflow installs JDK 17 and Gradle 8.7 automatically, so a Gradle wrapper is not required for GitHub Actions.

## Important limitation

No Android extractor can guarantee URLs that a website never exposes to the browser (for example content blocked behind authentication, bot protection, or content that is not loaded into the page). This app extracts what is exposed by the rendered WebView DOM/source and can trigger ordinary lazy-loading by scrolling; it does not bypass access controls.

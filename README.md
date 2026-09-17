# Link Extractor Pro V2

A professional Android webpage link extractor built with Kotlin, Jetpack Compose and Android WebView.

## Features

- Built-in webpage browser
- Open HTTP and HTTPS webpages
- Receive URLs from Chrome using Android Share
- Extract links from rendered webpage DOM
- Extract `<a href>` links
- Extract `<area href>` links
- Extract common `data-*` URL attributes
- Extract URLs from inline JavaScript
- Extract absolute URLs from page source
- Resolve relative URLs into absolute URLs
- Google redirect destination extraction
- Deep scanning for dynamically loaded content
- Scroll-based lazy-loading detection
- Duplicate URL removal
- HTTPS-only filtering
- External-only filtering
- URL search
- Copy individual URLs
- Copy all URLs
- Export TXT
- Export CSV
- Open extracted URLs
- Scan page again

## GitHub build

The repository includes a GitHub Actions workflow.

After uploading all project files:

1. Open the GitHub repository.
2. Open **Actions**.
3. Select **Build Android APK**.
4. Click **Run workflow**.
5. Wait for the build to complete.
6. Open the successful workflow.
7. Download the artifact:

`LinkExtractor-Pro-v2-debug-apk`

## Project structure

```text
.github/
└── workflows/
    └── build-apk.yml

app/
├── build.gradle.kts
└── src/
    └── main/
        ├── AndroidManifest.xml
        ├── kotlin/
        │   └── com/
        │       └── linkextractor/
        │           └── app/
        │               └── MainActivity.kt
        └── res/
            └── values/
                └── styles.xml

.gitignore
build.gradle.kts
gradle.properties
README.md
settings.gradle.kts
```

## Important limitation

The application extracts URLs exposed to the browser's rendered page, DOM, HTML/source, and JavaScript content.

It cannot legitimately extract content that a website does not expose to the browser, such as content hidden behind authentication, access controls, or bot-protection systems.

The application does not bypass website security or access controls.

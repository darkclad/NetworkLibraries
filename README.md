# OPDS Library

An Android application for browsing OPDS (Open Publication Distribution System) catalogs and managing a local e-book library.

## Features

### Network Libraries (OPDS Catalogs)
- Browse multiple OPDS catalogs with hierarchical navigation
- Book details with descriptions, metadata, and cover images
- Download books in various formats (FB2, EPUB, PDF, MOBI, AZW3, etc.)
- Configurable format priority for automatic best-format selection
- Favorites system to save books and feeds for later
- Last visited authors for quick access — listed alphabetically; long-press an author to favorite it or remove it from the list
- Authentication support for password-protected catalogs (built-in credentials for the self-hosted mirrors)
- Alternate URL fallback (e.g., mirror domains)
- Feed caching with timestamp-based invalidation
- Search with OpenSearch support and search history
- Background image caching
- Downloads manager with retry and progress tracking

### App Maintenance
- In-app self-updater — checks a self-hosted publish manifest on launch (and on demand from Settings → About) and installs a newer signed build in place via the system package installer, preserving all data

### Local Library
- Scan device folders for e-books (FB2, FB2.ZIP, EPUB, PDF, MOBI, AZW3)
- Parse metadata from book files (title, author, series, genre, cover, language, ISBN)
- Browse by: All Books, Author, Series, Genre
- Sort by: Title, Author, Date Added (ascending/descending)
- Paginated book list with DB-level sorting
- Full-text search via Lucene index
- Book detail screen with cover, metadata, and OPDS links
- File rename, book deletion (with or without file)
- Duplicate detection by file size
- Parse failure tracking with bulk cleanup

### OPDS-to-Library Matching
- Automatically links local books to OPDS catalog entries
- Two-phase matching: fast OPDS ID lookup, then background filename/title/author matching
- Context-aware matching (author pages, series pages, collections)
- Shows "In Library" / "Outdated" / "Not in Library" status on catalog entries
- Manual unlink/relink support

## Requirements

- **Minimum Android Version:** Android 8.0 (Oreo) — API Level 26
- **Target Android Version:** Android 15 — API Level 36

## Tech Stack

- **Language:** Kotlin 2.0
- **UI Framework:** Jetpack Compose with Material 3
- **Architecture:** MVVM (Model-View-ViewModel)
- **Database:** Room with KSP annotation processing
- **Networking:** OkHttp
- **Image Loading:** Coil with custom cache interceptor
- **Search:** Apache Lucene (full-text search)
- **Background Processing:** WorkManager
- **Async:** Coroutines + Flow
- **Preferences:** DataStore

## Building

1. Clone the repository
2. Open the project in Android Studio
3. Sync Gradle dependencies
4. Build and run on an emulator or physical device

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Distribution

Field builds are published by Forgejo Actions (`.forgejo/workflows/apk.yml`). Every push to
`main` builds and signs the **release** APK (`com.example.opdslibrary`), and publishes it to the
self-hosted dist server with an `index.json` manifest that the in-app updater reads. It
publishes only when `versionCode` in `app/build.gradle.kts` is above the live one, so **bump
`versionCode` in the same commit** to ship a build.

The build fails if any of its secrets is missing: the signing keystore, the Cloudflare Access
token the updater uses off-LAN, and the Basic-auth credentials for the self-hosted catalogs.

Because release builds share a stable signature, the updater installs each new build in
place without losing data. The keystore (`release-keystore.jks`) is gitignored; CI gets it from
a Forgejo secret, and a local copy sits on the build machine — **keep it backed up**; losing it
means future builds can no longer update an installed app in place.

`publish-opds.ps1` is the older manual path (build locally, copy to the dist share, write the
manifest). It still works but is no longer the normal way to ship.

## Default Catalogs

The app comes pre-configured with these OPDS catalogs:
- **Flibusta** (`flibusta.is`, falls back to `flibusta.net`) — the default catalog
- **Flibusta (darkclad)** — self-hosted Flibusta mirror (inpx-web)
- **lib.rus.ec (darkclad)** — self-hosted lib.rus.ec mirror (inpx-web)

The self-hosted mirrors need Basic auth away from the home network; release builds carry
the credentials built in (`BOOKS_OPDS_USER` / `BOOKS_OPDS_PASS`), so no login prompt appears.
Project Gutenberg, Manybooks and Smashwords used to be defaults; they are removed from
existing installs on upgrade.

## License

This project is open source.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues.

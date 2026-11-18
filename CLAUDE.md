# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Compile Kotlin without packaging
./gradlew compileDebugKotlin

# Clean and rebuild
./gradlew clean assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

### Distribution / self-update

Publishing is done by CI: `.forgejo/workflows/apk.yml` runs on every push to `main` (self-hosted
Forgejo runner, `android-ci` image). It builds and signs the **release** APK and publishes it to
`dist.darkclad.org/opds/` with the `index.json` manifest the in-app updater reads — but only when
`versionCode` in `app/build.gradle.kts` is above the live one. **To ship, bump `versionCode` in the
same commit** (note the bump in the commit message). Workflow is direct-to-`main`; push to `origin`
(Forgejo), which mirrors to GitHub.

CI secrets: `RELEASE_KEYSTORE_B64`, `CF_ACCESS_CLIENT_ID`/`CF_ACCESS_CLIENT_SECRET` (updater token),
`BOOKS_OPDS_USER`/`BOOKS_OPDS_PASS` (self-hosted catalog auth). The build fails if any is missing
or not embedded in the APK.

The field app is the release package `com.example.opdslibrary` (stable `release-keystore.jks`
signature), so each new build installs in place and preserves data. The keystore is gitignored;
CI gets it from a secret and a local copy lives on the build machine — keep it backed up (its
password is in `app/build.gradle.kts`).

`publish-opds.ps1` is the older manual path (local build → dist share → manifest; `-Bump`,
`-BuildType debug` for the separate `.debug` test package). Use it only if CI is unavailable.

## Architecture Overview

This is an Android OPDS (Open Publication Distribution System) catalog browser and local e-book library manager built with Jetpack Compose.

### Key Architectural Patterns

**MVVM Architecture**: ViewModels in `viewmodel/` handle business logic and UI state, communicating with data layer through Repository pattern.

**Navigation**: Single-Activity architecture using Jetpack Navigation Compose. Routes defined in `MainActivity.kt`:
- `main` → Main menu (Library/Network Libraries)
- `network_libraries` → OPDS catalog list (StartScreen)
- `catalog/{catalogId}` → Browse specific catalog (CatalogScreen)
- `catalog_with_url/{catalogId}/{initialUrl}` → Catalog at specific URL (from library book details)
- `catalog_with_history/{catalogId}/{navHistory}` → Catalog with restored navigation (View in Catalog)
- `library` → Local book library
- `library/author/{authorId}` → Library filtered by author
- `library/series/{seriesId}` → Library filtered by series
- `book_detail/{bookId}` → Local book details
- `app_settings` → Application settings

**Data Layer**:
- `data/AppDatabase.kt` - Room database with 15+ migration versions. Contains both OPDS entities (catalogs, feed cache, favorites) and Library entities (books, authors, series, genres)
- `network/OpdsRepository.kt` - OPDS feed fetching with caching and authentication support
- `network/BooksAuth.kt` - built-in Basic auth for the self-hosted inpx-web catalogs on `books.darkclad.org` (`BuildConfig.BOOKS_OPDS_*`)
- Default catalogs are seeded in `AppDatabase.ensureDefaultCatalogsExist` (Flibusta + the two self-hosted mirrors); retired defaults are deleted there too
- `data/OpdsParser.kt` - Custom XML parser for OPDS/Atom feeds

**Book Format Parsers** (`library/parser/`):
- `EpubParser.kt`, `Fb2Parser.kt`, `Fb2ZipParser.kt`, `PdfParser.kt`, `MobiParser.kt`
- `BookParserFactory.kt` - Factory pattern for selecting correct parser by file extension
- All parsers extract metadata and cover images

**OPDS Matching** (`viewmodel/OpdsMatchingProcessor.kt`):
- Background processor that links local books to OPDS catalog entries
- Two-phase: fast OPDS ID lookup → queue-based filename/title/author matching
- Context-aware matching based on page type (author page, series, collection)
- Coordinates with downloads to avoid race conditions

**Background Processing**:
- `library/scanner/LibraryScanWorker.kt` - WorkManager-based library scanning with parallel workers and parse failure tracking
- `image/ImageDownloadWorker.kt` - Background image downloading
- `library/search/BookSearchManager.kt` - Lucene-based full-text search index

**In-app self-updater** (`update/`):
- `AppUpdater.kt` - reads the publish `index.json` manifest, downloads a newer APK (SHA-256 verified), and hands it to the system `PackageInstaller`. Optional Cloudflare Access headers via `BuildConfig.CF_ACCESS_*` (embedded at build time from CI secrets). Requires `buildConfig = true` and the `REQUEST_INSTALL_PACKAGES` permission.
- `AppInstallReceiver.kt` - PackageInstaller status callbacks (launches the confirm screen).
- `AppUpdateChecker.kt` - Compose front-end: silent check on launch (wired in `MainActivity`) + manual "Check for updates" in Settings → About.

### Data Models

OPDS models in `data/OpdsModels.kt`: `OpdsFeed`, `OpdsEntry`, `OpdsLink`, `OpdsAuthor`, `OpdsCategory`

Library models in `data/library/`: `Book`, `Author`, `Series`, `Genre` with junction tables for many-to-many relationships

### Key ViewModels

- `CatalogViewModel` - OPDS browsing, feed loading, pagination, navigation history, favorites, search, downloads, auth, alternate URLs, matching integration
- `LibraryViewModel` - Browse modes (ALL_BOOKS, BY_AUTHOR, BY_SERIES, BY_GENRE, SEARCH_RESULTS), paginated book list with DB-level sorting, search, book CRUD
- `AppSettingsViewModel` - Scan folders, scanning progress, format priority, download folder, reader preference, duplicate detection, parse failure management
- `StartScreenViewModel` - OPDS catalog CRUD

### Configuration

- Min SDK: 26 (Android 8.0)
- Target SDK: 36
- Kotlin 2.0.21 with Compose compiler plugin
- Room with KSP for annotation processing
- Database version: 18 (12 migrations from v6)
- OkHttp for networking, Coil for image loading
- Apache Lucene for full-text search
- WorkManager for background scanning
- DataStore for preferences
- `buildConfig = true`; build fields `CF_ACCESS_CLIENT_ID`/`CF_ACCESS_CLIENT_SECRET` (in-app updater) and `BOOKS_OPDS_USER`/`BOOKS_OPDS_PASS` (self-hosted catalogs), empty unless passed as Gradle properties (CI secrets)
- Release signed with `release-keystore.jks`; bump `versionCode` in `app/build.gradle.kts` to publish a build

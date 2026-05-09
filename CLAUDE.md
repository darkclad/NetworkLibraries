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

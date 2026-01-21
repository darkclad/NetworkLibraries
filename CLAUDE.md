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
- `library` → Local book library
- `book_detail/{bookId}` → Local book details

**Data Layer**:
- `data/AppDatabase.kt` - Room database with 15+ migration versions. Contains both OPDS entities (catalogs, feed cache, favorites) and Library entities (books, authors, series, genres)
- `network/OpdsRepository.kt` - OPDS feed fetching with caching and authentication support
- `data/OpdsParser.kt` - Custom XML parser for OPDS/Atom feeds

**Book Format Parsers** (`library/parser/`):
- `EpubParser.kt`, `Fb2Parser.kt`, `Fb2ZipParser.kt`, `PdfParser.kt`, `MobiParser.kt`
- `BookParserFactory.kt` - Factory pattern for selecting correct parser by file extension
- All parsers extract metadata and cover images

**Background Processing**:
- `library/scanner/LibraryScanWorker.kt` - WorkManager-based library scanning
- `image/ImageDownloadWorker.kt` - Background image downloading

### Data Models

OPDS models in `data/OpdsModels.kt`: `OpdsFeed`, `OpdsEntry`, `OpdsLink`, `OpdsAuthor`, `OpdsCategory`

Library models in `data/library/`: `Book`, `Author`, `Series`, `Genre` with junction tables for many-to-many relationships

### Configuration

- Min SDK: 26 (Android 8.0)
- Target SDK: 36
- Kotlin 2.0.21 with Compose compiler plugin
- Room with KSP for annotation processing

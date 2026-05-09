# OPDS Library

An Android application for browsing OPDS (Open Publication Distribution System) catalogs and managing a local e-book library.

## Features

### Network Libraries (OPDS Catalogs)
- Browse multiple OPDS catalogs with hierarchical navigation
- Book details with descriptions, metadata, and cover images
- Download books in various formats (FB2, EPUB, PDF, MOBI, AZW3, etc.)
- Configurable format priority for automatic best-format selection
- Favorites system to save books and feeds for later
- Last visited authors for quick access
- Authentication support for password-protected catalogs
- Alternate URL fallback (e.g., mirror domains)
- Feed caching with timestamp-based invalidation
- Search with OpenSearch support and search history
- Background image caching
- Downloads manager with retry and progress tracking

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

## Default Catalogs

The app comes pre-configured with several free OPDS catalogs:
- **Flibusta** — Russian books (with alternate URL fallback)
- **Project Gutenberg** — Public domain books
- **Manybooks** — Free ebooks
- **Smashwords** — Independent authors

## License

This project is open source.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues.

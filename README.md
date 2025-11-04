# Network Libraries

An Android application for browsing and managing OPDS (Open Publication Distribution System) catalogs.

## Features

- Browse multiple OPDS catalogs
- Book details with descriptions, metadata, and cover images
- Download books in various formats (FB2, EPUB, PDF, etc.)
- Favorites system to save books for later
- Authentication support for password-protected catalogs
- Offline caching for improved performance
- HTML content rendering for rich book descriptions
- Search and navigation through catalog hierarchies
- Default catalogs included (Flibusta, Project Gutenberg, Manybooks, Smashwords)

## Requirements

- **Minimum Android Version:** Android 7.0 (Nougat) - API Level 24
- **Target Android Version:** Android 14 - API Level 36

## Tech Stack

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose with Material 3
- **Architecture:** MVVM (Model-View-ViewModel)
- **Database:** Room
- **Networking:** Retrofit + OkHttp
- **Image Loading:** Coil
- **Async:** Coroutines + Flow

## Building

1. Clone the repository
2. Open the project in Android Studio
3. Sync Gradle dependencies
4. Build and run on an emulator or physical device

```bash
./gradlew assembleDebug
```

## Default Catalogs

The app comes pre-configured with several free OPDS catalogs:
- **Flibusta** - Russian books
- **Project Gutenberg** - Public domain books
- **Manybooks** - Free ebooks
- **Smashwords** - Independent authors

## License

This project is open source.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues.

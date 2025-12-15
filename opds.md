## **OPDS Library** | Android E-Book Library Manager
*Personal Project* | [GitHub](https://github.com/darkclad/NetworkLibraries)

Developed a feature-rich Android application for browsing online OPDS (Open Publication Distribution System) catalogs and managing a local e-book library.

**Key Features:**
- Built custom XML parser for OPDS/Atom feed protocol to browse and search online book catalogs
- Implemented multi-format e-book metadata extraction (EPUB, FB2, PDF, MOBI) with cover image parsing
- Designed parallel file scanning system using Kotlin Coroutines with configurable worker count and semaphore-based concurrency control
- Created full-text search using Room FTS (Full-Text Search) for fast library queries
- Built automatic library organization with author/series folder structure
- Implemented file deduplication using SHA-256 hashing

**Technical Stack:**
- **Language:** Kotlin
- **UI:** Jetpack Compose, Material Design 3
- **Architecture:** MVVM with ViewModels
- **Data:** Room Database, DataStore Preferences
- **Networking:** Retrofit, OkHttp
- **Async:** Kotlin Coroutines, WorkManager (background scanning)
- **Storage:** Android Storage Access Framework (SAF)
- **Image Loading:** Coil

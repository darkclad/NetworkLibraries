# OPDS Library — App Screen Reference

---

## Screen 1: Main Menu
**Route:** `main` (start screen)

The entry point. Shows two large navigation cards:
- **Library** — opens your local book collection
- **Network Libraries** — opens the OPDS catalog list

Top-right gear icon navigates to Settings.

---

## Screen 2: Network Libraries
**Route:** `network_libraries`

Manages your list of OPDS catalog servers.

**What you can do:**
- View all saved OPDS catalogs (name, URL, default badge)
- **Add** a new catalog via the FAB (+) — enter primary URL, optional fallback URL, and optional custom name (if no name is given, it's fetched from the feed)
- **Edit** a catalog — URL, fallback URL, custom name, and see the live OPDS feed name
- **Delete** a catalog — with confirmation dialog
- **Set as Default** — marks a catalog as the default
- **Open** a catalog — tap a card to browse it

---

## Screen 3: Catalog / OPDS Browser
**Route:** `catalog/{catalogId}`

The most complex screen. Browses an OPDS feed hierarchy and manages downloads.

**Top bar:**
- Catalog name (click to go to root)
- Breadcrumb back arrow for sub-navigation
- Downloads icon with badge (count of active/attention items)
- Search icon (when the feed supports search)
- Refresh icon
- Back to catalog list

**Feed content — two types of entries:**

| Entry Type | What it shows | What you can do |
|---|---|---|
| **Navigation** (folder/category) | Title, optional cover | Tap to go deeper; long-press to add to Favorites |
| **Acquisition** (book) | Cover, title, authors, year, description, format chips | Tap for full book details modal; long-press for Favorites |

**Book details modal** (shown inline, not a separate screen):
- Full metadata, cover, description
- Format buttons with file size — tap to download
- "Open" button if the book is already in your local library
- "Unlink" button to remove the OPDS-to-library link (keeps the local file)

**Search dialog:**
- Query input with clear button
- Format filter dropdown
- Recent search history (delete individual or clear all)

**Downloads manager dialog:**
- Lists all downloads with status (in progress / completed / failed)
- Per-item: retry (failed), remove
- Global: "Clear Completed"

**Login dialog** (shown automatically if server requires auth):
- Username + password, visibility toggle, attempt counter

**Favorites overlay** (on long-press):
- Add to Favorites / Remove from Favorites
- "Display in Catalog" (if viewing the Favorites section)
- "Clear All Favorites" (if at Favorites root)

**Error state:**
- Retry button
- "Show Cached" button (uses previously cached feed)

**WebView fallback** — if the server returns HTML instead of OPDS, it renders in a built-in web view.

---

## Screen 4: Library
**Route:** `library`

Local e-book library. All books found by scanning your storage folders.

**Browse modes** (menu in top bar):

| Mode | Shows | On tap |
|---|---|---|
| **All Books** | Paginated grid of all books (DB-level sorting, 50 per page) | Open Book Detail |
| **By Author** | List of authors + book count | Filter to that author's books |
| **By Series** | List of series + book count | Filter to that series |
| **By Genre** | List of genres + book count | Filter to that genre |

**Sort options:** Title / Author / Date Added — with ascending/descending toggle.

**Search bar:** Full-text search across the library. Results appear in the grid.

**Letter index scroller:** Vertical A–Z strip on the right edge — tap a letter to jump to it. Supports Cyrillic and other scripts, with `#` for symbols.

**Empty state:** "No books found" + shortcut to add scan folders.

---

## Screen 5: Book Detail
**Route:** `book_detail/{bookId}`

Full metadata view for a local book.

**What you can see:**
- Cover image, title, authors (tappable → author filter in Library), series + number, year
- Genres as chips
- Full description
- File info: format, size, date added, language, ISBN
- File location: filename + folder path

**What you can do:**
- **Open Book** — launches the book in your preferred reader app (or shows a picker)
- **Rename file** — pencil icon next to filename; extension is preserved automatically
- **Copy full path** — copies the absolute file path to clipboard
- **View in Catalog** — if the book was downloaded from OPDS, restores the exact catalog navigation position where it came from
- **OPDS Related Links** — buttons for any related feeds stored with the book
- **Delete from library** — trash icon in top bar; options to delete with file or remove metadata only, with confirmation

---

## Screen 6: App Settings
**Route:** `app_settings`

Configuration for the entire app. Organized in sections:

### Library
- **Scan Folders** — add/remove folders to scan for books
  - Per-folder: enable/disable toggle, individual scan, delete
  - Global: Quick Scan (new files only) or Full Scan (rescan everything)
  - While scanning: current file, progress counter + bar, Cancel button
  - **Parse failures notification** — after scan, shows count of books that couldn't be parsed; expandable to see filenames, with option to delete all unparsed books
  - **Find Duplicates** — detect books with identical file sizes, review and delete duplicates

### Downloads
- **Download Folder** — pick where downloaded books are saved (SAF folder picker)
- **Format Priority** — drag-to-reorder list of preferred formats (FB2, EPUB, PDF, MOBI, AZW3, etc.); has Reset to defaults

### OPDS Catalog
- **Enable Filename Matching** — toggle; when on, tries to match locally scanned books to OPDS catalog entries by filename, enabling catalog features for books not downloaded through the app

### Reader
- **Default Reader App** — pick which installed app opens books

### Performance
- **Parallel Workers** — slider from 1 to max CPUs; controls how many files are processed simultaneously during a library scan

### Cache
- **Image Cache** — shows current size; Clear button (disabled when empty)
  - Clears all cached covers and icons (re-downloaded on next view)

### Danger Zone
- **Clear Library Database** — removes all book metadata; files on disk are untouched, scan folders are preserved

---

## Navigation Map

```
Main Menu
├── Network Libraries
│   └── Catalog Browser
│       ├── Sub-feeds (breadcrumb navigation)
│       ├── Book Details Modal (inline)
│       └── Library (author filter, via author link)
├── Library
│   ├── Author list → Author's books
│   ├── Series list → Series books
│   ├── Genre list → Genre books
│   └── Book Detail
│       ├── Catalog Browser (View in Catalog / OPDS links)
│       └── Library (author filter, via author tap)
└── App Settings
    └── SAF Folder Picker (system UI)
```

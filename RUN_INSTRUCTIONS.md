# Running opdsLibrary on Android Device/Emulator

## Prerequisites

1. **Android Studio** installed (latest stable version recommended)
2. **Android device** or **emulator** set up
3. **USB Debugging** enabled on physical device (if using a real device)

## Setup Steps

### Option 1: Using Android Studio (Recommended)

1. **Open the Project**
   - Open Android Studio
   - Click "Open" and select the `opdsLibrary` folder

2. **Sync Gradle**
   - Android Studio will automatically sync Gradle files
   - Wait for the sync to complete
   - If prompted, accept any SDK updates

3. **Set Up Device/Emulator**

   **For Physical Device:**
   - Enable Developer Options on your device
   - Enable USB Debugging
   - Connect your device via USB
   - Accept the USB debugging prompt on your device

   **For Emulator:**
   - Open AVD Manager (Tools → Device Manager)
   - Create a new virtual device or select an existing one
   - Start the emulator

4. **Run the Application**
   - Select "app" from the run configuration dropdown (top toolbar)
   - Select your target device from the device dropdown
   - Click the green "Run" button (▶) or press Shift+F10
   - The app will build and install on your device

### Option 2: Using Command Line

1. **Build the APK**
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install on Device**

   Make sure your device is connected and visible:
   ```bash
   adb devices
   ```

   Install the APK:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Launch the App**
   ```bash
   adb shell am start -n com.example.opdslibrary/.MainActivity
   ```

### Option 3: Using Gradle Run Task

```bash
./gradlew installDebug
adb shell am start -n com.example.opdslibrary/.MainActivity
```

## Testing the App

Once the app is running:

1. **Initial Screen**
   - You'll see the welcome screen
   - Tap "Open Catalog" or the "+" button

2. **Enter OPDS Catalog URL**
   - Enter an OPDS catalog URL (examples below)
   - Tap "Open"

3. **Browse the Catalog**
   - Navigate through categories by tapping on navigation entries
   - View book details with thumbnails and descriptions
   - Use the back button to navigate through history

## Example OPDS Catalogs to Test

- **Standard Ebooks**: `https://standardebooks.org/opds`
- **Feedbooks Public Domain**: `https://www.feedbooks.com/publicdomain/catalog.atom`
- **Calibre Demo**: `https://demo.calibre-ebook.com/opds`

## Troubleshooting

### App doesn't appear in run configuration
- File → Sync Project with Gradle Files
- File → Invalidate Caches → Invalidate and Restart

### Device not detected
- Check USB debugging is enabled
- Try a different USB cable
- Run `adb devices` to verify connection

### Build errors
- Make sure you have the required SDK version (API 36)
- Check that Kotlin version is 2.0.21 or higher
- Clean and rebuild: Build → Clean Project, then Build → Rebuild Project

### Network errors when browsing catalogs
- Check device has internet connection
- Some OPDS catalogs may have SSL certificate issues
- Try a different catalog URL

## Debugging

To debug the app:
1. Click the "Debug" button (🐛) instead of "Run"
2. Set breakpoints in your code by clicking the gutter
3. App will pause at breakpoints for inspection

## Logcat

View app logs in Android Studio:
- View → Tool Windows → Logcat
- Filter by package: `com.example.opdslibrary`

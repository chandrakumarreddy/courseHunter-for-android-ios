<div align="center">

# ch. CourseHunter

**A cross-platform Android and iOS app for [coursehunter.net](https://coursehunter.net), built with ReactLynx.**

One ReactLynx codebase for both platforms: the whole site in a fast WebView, plus native extras on Android like a PDF reader with themes, search, bookmarks and offline books.

![Lynx](https://img.shields.io/badge/Lynx-4.1-8200B0?style=flat-square)
![ReactLynx](https://img.shields.io/badge/ReactLynx-0.126-61DAFB?style=flat-square&logo=react&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Lynx-000000?style=flat-square&logo=apple&logoColor=white)
![Bun](https://img.shields.io/badge/Bun-1.3-000000?style=flat-square&logo=bun&logoColor=white)

[![Release](https://img.shields.io/github/v/release/chandrakumarreddy/courseHunter-for-android-ios?style=flat-square&label=release&color=8200B0)](https://github.com/chandrakumarreddy/courseHunter-for-android-ios/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/chandrakumarreddy/courseHunter-for-android-ios/total?style=flat-square&color=8200B0)](https://github.com/chandrakumarreddy/courseHunter-for-android-ios/releases)

**[🌐 Website](https://coursehunter.net)** · **[📦 Releases](https://github.com/chandrakumarreddy/courseHunter-for-android-ios/releases)** · **[🧩 Packages](https://github.com/chandrakumarreddy?tab=packages&repo_name=courseHunter-for-android-ios)**

</div>

---

## 📲 Download

| Platform | Get it |
|---|---|
| 🤖 **Android** | Download `coursehunter.apk` from the [latest release](https://github.com/chandrakumarreddy/courseHunter-for-android-ios/releases/latest) and open it on your phone (allow installs from unknown sources when asked) |
| 🍎 **iOS** | Run `bun run dev` and scan the QR code with LynxExplorer |
| 🌐 **Web** | Visit [coursehunter.net](https://coursehunter.net) |

---

## ✨ Features

### 📱 Shared on Android and iOS
- Full **coursehunter.net** experience in a Lynx `<webview>`, with a branded splash and a retry toast if loading fails
- The same ReactLynx UI in `src/` renders natively on both platforms

### 🤖 Android extras
- **Pull to refresh** that stays out of the way of inner scrolling areas
- **Mobile / desktop toggle** that switches the user agent and viewport, and remembers your choice
- **Status bar and navigation bar follow the site's theme**, light or dark
- **Downloads** (videos, archives, ebooks) go to the system download manager and keep your login
- English by default; any language you pick on the site is kept
- Back gesture goes through the site's history; the app moves to the background instead of closing

### 📖 Native PDF reader (Android)
Android WebView can't show PDFs, so the app replaces the site's embedded books with a **Read book** card that opens a native reader built on PDFium:

| | |
|---|---|
| 🎨 **Themes** | Light, Sepia and Dark, which inverts pages but keeps image colours |
| 🔍 **Search** | Full-text search with highlighted matches and next/previous |
| 🔖 **Bookmarks and contents** | Bookmark pages and jump through the book's table of contents |
| ✂️ **Text selection** | Select text to copy it or search for it |
| 🔎 **Zoom** | Pinch zoom with sharp tiled rendering |
| 📑 **Two-page spread** | Pages side by side on wide screens |
| 🔆 **Reading comfort** | Brightness control and a keep-screen-on option |
| 📍 **Resume** | Reopens each book at the page where you left off |
| 📥 **Offline books** | Save books to the phone; recent books are cached automatically |

---

## 🏗️ How it works

```
┌──────────────────────────────────────────────┐
│ MainActivity (Kotlin)                        │
│  ├─ SwipeRefreshLayout                       │
│  └─ LynxView ── src/App.tsx (ReactLynx)      │
│        └─ <webview src="coursehunter.net">   │
│              └─ pdf-viewer.js (injected)     │
│                    │ CourseHunterApp.openPdf │
│                    ▼                         │
│ PdfReaderActivity (PDFium) ◄── Books cache   │
└──────────────────────────────────────────────┘
```

| Path | Role |
|---|---|
| `src/App.tsx` | ReactLynx UI: webview, splash overlay, error toast |
| `android/.../MainActivity.kt` | Hosts Lynx, configures the WebView: downloads, desktop mode, theme sync, PDF hand-off |
| `android/.../PdfReaderActivity.kt` | The native PDF reader |
| `android/.../Books.kt` | Book downloads, shared prefetching, the offline cache and saved books |
| `android/app/src/main/assets/pdf-viewer.js` | Injected into pages to swap embedded PDFs for the native reader |
| `lynx.config.ts` | Rspeedy config, including live reload on connected Android devices |

---

## 🚀 Getting started

### Prerequisites
- [Bun](https://bun.sh) 1.3+ and Node 20.19+ or 22.12+
- Android SDK (API 34) and JDK 17
- `adb`, with a phone or emulator connected

### Install
```bash
bun install
```

### Develop on iOS or Android with LynxExplorer
```bash
bun run dev
```
Scan the QR code in the terminal with the LynxExplorer app on your iPhone or Android phone. The page reloads on every save.

### Develop in the Android app
```bash
bun run dev:android
```
Installs the debug app, forwards the dev server port over `adb reverse`, launches the app, and re-renders it on every save.

### Build a release APK
```bash
bun run build:android
```
Output: `./coursehunter.apk`

> **Signing:** release builds read `android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). The keystore and its properties are git-ignored. Back up your `.jks`, because every update must be signed with the same key.

---

## 🏷️ Releasing

Push a version tag and GitHub Actions builds the APK and publishes a release with `coursehunter.apk` and `main.lynx.bundle`:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Add these repository secrets so releases are signed with your key (without them, the APK is debug-signed):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -i android/release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

---

## 🧰 Scripts

| Command | Description |
|---|---|
| `bun run dev` | Start the Rspeedy dev server (scan the QR code with LynxExplorer on iOS or Android) |
| `bun run dev:android` | Install the debug app and live-reload it on connected devices |
| `bun run build` | Build the Lynx bundle into `dist/` |
| `bun run build:android` | Build the bundle and a signed release APK |
| `bun run preview` | Preview the production bundle |
| `bun run test` | Run tests with Rstest |
| `bun run check` | Lint with Biome |
| `bun run format` | Format with Biome |

---

## 🩺 Troubleshooting

**`compileReleaseKotlin` fails with `IOException: No such file or directory`**
The Kotlin incremental cache is stale. Clear it and stop the Gradle daemon:
```bash
rm -rf android/app/build/kotlin android/app/build/tmp && ./android/gradlew -q -p android --stop
```

**`main.lynx.bundle not found`**
Run `bun run build` before building with Gradle directly.

---

<div align="center">
<sub>Built with <a href="https://lynxjs.org">Lynx</a> · <a href="https://rsbuild.rs">Rsbuild</a> · <a href="https://rspack.rs">Rspack</a></sub>
</div>

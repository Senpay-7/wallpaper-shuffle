# Wallpaper Shuffle

A native Android live wallpaper that shuffles my own photos, GIFs and videos on the home and lock screens. No ads, no network permission, no accounts, no analytics. I built it because every wallpaper app I tried was full of ads or needed internet access.

> Personal project, built with AI coding assistants under my direction: I wrote the requirements, reviewed the code, ran the tests and tested the builds.
> Its successor, a Kotlin / Jetpack Compose rewrite with instant double-tap changes and 66 tests, is [wallshift](https://github.com/Senpay-7/wallshift).

## Features
- Separate **home-screen and lock-screen** playback; mark each file Home / Lock / Both / Hidden.
- **Fair shuffle:** every file is shown once before anything repeats, then the order is reshuffled.
- Change on unlock, on a timer, or both; hold the current wallpaper when you like it.
- Import single files or whole folders (Storage Access Framework); media is copied into private app storage.

## Engineering notes
- **Rendering:** EGL / OpenGL ES 2 scene renderer; GIFs via `AnimatedImageDrawable`, muted looping video via `MediaPlayer`, stills via `ImageDecoder`.
- **Battery:** rendering and video stop while the wallpaper is not visible; work is deferred while the screen is off.
- **Storage:** SQLite metadata; scoped URI permissions instead of broad storage access.
- **Direct Boot:** reduced stills of the last scenes are available before the first unlock.
- **Size:** no third-party runtime libraries; the release APK is about 100 KB.
- **Quality:** unit tests (JUnit 4, Robolectric) and Android Lint.

| File | Responsibility |
| --- | --- |
| `MainActivity.java` | Library, import flow, preview and per-screen controls |
| `LibraryStore.java` / `FolderImporter.java` | Private copies and SQLite metadata |
| `Playback.java` / `AppController.java` | Independent queues, timers, holds and persistence |
| `ShuffleWallpaperService.java` | Wallpaper lifecycle, screen events and gestures |
| `SceneRenderer.java` / `MediaImages.java` | OpenGL rendering, thumbnails and Direct Boot stills |

## Build
Requires JDK 17 and Android SDK 35 (`minSdk 33`).
```
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
Signing keys and passwords are deliberately excluded. Release signing reads the password from the `WS_SIGN_PASSWORD` environment variable.

## Status
Version 1.2.0, a personal sideload build for Android 13. It has been tested on an emulator; vendor-specific lock-screen behaviour (Xiaomi HyperOS) still needs testing on the device.

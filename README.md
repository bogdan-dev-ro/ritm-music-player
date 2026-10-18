<p align="center"><img src="icon.png" width="120" alt="Ritm icon"></p>

# Ritm

A simple music player for the songs on your phone. Pick your files once, then control the music from a notification or from a floating bubble while you use other apps.

## Features
- Plays the music files from your phone, with playlists
- **Notification** with cover, title and Previous / Play-Pause / Next / Stop, also on the lock screen
- **Floating bubble** over other apps: title, controls, volume and play mode, draggable and snapping to the edge
- Play modes: Loop, Shuffle, Repeat 1, In order
- Sleep timer
- Keeps playing with the screen off (use the **Allow background playback** button in Settings; on some phones also set the battery to "No restrictions")

## Get the APK
This repository builds the app with GitHub Actions. Open the **Actions** tab, run **Build Android APK** (it also starts on every push) and download **ritm-debug-apk** from the **Artifacts** section. Unzip it and install `app-debug.apk`; Android will ask you to allow installing from this source.

## How it is built
The app itself is a web page (`www/index.html`) packaged with [Capacitor](https://capacitorjs.com/). The Android part (player service, notification, bubble) is in `native/java`. `scripts/apply-native.js` copies it, and the app icon, into the Android project that is generated on every build.

The page loads two fonts from Google Fonts; without internet it simply falls back to the system fonts. The music itself never leaves your phone.

## License
GPL-3.0-only. See [LICENSE](LICENSE).

# SLP Meal Selection Android App

This project wraps `demo.html` into an Android tablet app using a full-screen `WebView`.

## What was added
- Android project structure (`app` module + Gradle files)
- `MainActivity` that loads `file:///android_asset/demo.html`
- Tablet-friendly landscape launch setup
- Local assets copied to `app/src/main/assets`:
  - `demo.html`
  - `SLP.jpg`
  - `TechandThatLogoWhite.png`

## Run in Android Studio
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. If prompted, create/refresh Gradle wrapper from Android Studio (since `gradle-wrapper.jar` is not committed here).
4. Run on an Android tablet (or landscape emulator).

## Notes
- The UI and logic come directly from your existing HTML demo.
- Future step (optional): replace mocked JS data with real API-backed data.

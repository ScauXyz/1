Debug APK built and verified in the existing cloud Shell.

Source: 0fd9a9ed0e21eaa11fb1d13577271d68b8f6c134
Branch: codex/quark-owner-webview
Size: 11,006,419 bytes
SHA-256: 74725d7b9a4a310498591efb7df26e30d164ff302ba8ddba798ece6280595aca
Signature: apksigner verification passed, APK Signature Scheme v2, Android Debug.

Local validation: backend 33 tests, Ruff check/format passed; Android 16 tests, assembleDebug and lintDebug passed (0 errors, 14 warnings). Real device login, file chooser behavior and actual Quark upload results remain unverified. WebView restrictions are soft isolation only.

This dedicated download branch contains the existing validated APK. No environment deployment or Android SDK reinstallation is performed. The GitHub Actions Android setup step failed and did not produce this APK; this file is the locally validated build.

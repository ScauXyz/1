# Validation — 2026-10-25

Validated in the connected cloud Shell, after fast-forwarding baseline `8cd007d` to `69c31e5`. Existing SDK/JDK and dependency caches were reused. No environment publication or Android SDK reinstallation was performed.

| Check | Result |
| --- | --- |
| Backend `uv run --frozen pytest -q` | 33 passed, no failures/skips |
| `uv run --frozen ruff check app tests` | Passed |
| `uv run --frozen ruff format --check app tests` | Passed, 7 files already formatted |
| Android `./gradlew testDebugUnitTest assembleDebug lintDebug` | BUILD SUCCESSFUL |
| Android JVM tests | 16 passed: 10 retained backup tests + 6 new website-policy tests |
| Android Lint | 0 errors, 14 warnings (pinned dependency versions and optional KTX suggestions) |
| `git diff --check` | Passed |
| APK signature | `apksigner verify --verbose --print-certs`: Verifies, v2 true, 1 Android Debug signer |

Backend coverage is unchanged: real HTTP upload/restore, ownership/allowlist checks, IDOR, path/owner tampering, expired tokens, hashes/sizes, per-device dedupe and persistence. New Android tests cover concrete-folder validation, root ID `0` rejection, origin/userinfo/port/scheme spoofing, other-folder/root navigation, owner-only browsing, fragment-less network documents without loosening UI navigation, and MIME filtering. These JVM tests do not exercise the real WebView or system authentication.

APK path: `android/app/build/outputs/apk/debug/app-debug.apk`.

- Version: 0.2.0 (versionCode 2)
- Size: 11,006,419 bytes
- SHA-256: `74725d7b9a4a310498591efb7df26e30d164ff302ba8ddba798ece6280595aca`
- Debug certificate SHA-256: `52631571903ebcab05cae50f683d7ad9b907f7f009bef5af25d76715246feeeb`

This is a debug APK, not a release-signed distribution. A rebuild may produce a different artifact digest/signing certificate depending on local debug keys and build inputs. Outputs are intentionally ignored by Git.

Source-level UI review covered full-width primary actions, unbound disabled state, scrolling home/settings and confirmation lists, keyboard insets, a weighted title for larger fonts, message space outside the scroll area, loading indicator, retry, unavailable WebView, and network/certificate/HTTP/renderer errors. No rendered screenshots, emulator interaction or real-device visual acceptance was completed. The retained SDK has no emulator component; ADB could not initialize its default user directory in this Shell. No SDK component was installed to change that.

Not executed: owner credentials/SMS/QR login, session persistence on a real device, actual website routes and chooser behavior, file-provider permissions, device-credential UI, rotation/background/process-death acceptance, actual upload bytes/destination, or remote GitHub Actions results. The manual checklist is in [quark-webview.md](quark-webview.md). File handoff is never reported as upload success. Website folder controls remain soft isolation; backend ACLs remain server-enforced.

Backend tests emit one upstream Starlette/httpx deprecation warning. Gradle emits upstream deprecation warnings; these do not fail the pinned build. Tests requiring local sockets were run with network execution enabled.

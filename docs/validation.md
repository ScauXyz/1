# Validation of the MVP

Validated in the connected cloud execution machine using its retained tools. No environment publication or SDK reinstallation was performed for this implementation.

| Check | Result |
| --- | --- |
| Backend `uv run --frozen pytest -q` | 33 passed; 0 failed/skipped |
| Backend `ruff check app tests` | Passed |
| Backend `ruff format --check app tests` | Passed |
| Android `./gradlew testDebugUnitTest assembleDebug lintDebug` | BUILD SUCCESSFUL |
| Android JVM tests | 10 passed; 0 failed/skipped |
| Android Lint | 0 errors; 12 warnings (pinned-version and optional KTX suggestions) |
| Debug APK signature | apksigner verification passed, APK Signature Scheme v2 |

The backend suite includes a real uvicorn HTTP/socket test: register demo device, inspect approved content, upload bytes, complete backup, restore exact bytes, and reject another device's download. Other API tests explicitly cover IDOR, path/owner tampering, allowlist denial, expired tokens, hash/size integrity, dedupe and persistence across restarts.

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (11,337,207 bytes in this run).

SHA-256 of this run's APK: `f1af438f69ccd7d92801fe686fb500149f52f935304dfcd703693d267dc0d60e`. Debug signing and build inputs can make another build's digest differ. Build outputs are intentionally ignored by Git; the CI workflow builds and uploads its own artifact when run.

Backend tests emit one dependency deprecation warning about Starlette's future HTTP test-client transport. It does not fail tests. The wrapper/toolchain remain pinned rather than upgrading versions to silence dependency-age warnings.

Not executed: installation on a real phone/emulator, Compose interaction, MediaStore permission prompts, SAF provider behavior, Android Keystore on-device validation, WorkManager scheduling under Android battery restrictions, release signing, or remote GitHub Actions execution. These require their respective device/deployment environments. Production pairing, quotas, token lifecycle/recovery and an official authorized storage provider remain outside the mock MVP; see security-model.md.

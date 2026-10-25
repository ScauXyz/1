# 文件空间 / Device Backup

Native Kotlin/Compose home for upload/view through an owner-bound official Quark WebView. Android device authentication protects owner settings. Website sessions stay inside WebView; native code does not read/export cookies or store passwords. WebView navigation is **soft isolation**, not account-level authorization. The existing FastAPI mock backup backend and security tests remain intact. No private Quark API or official-app modification is used.

See [official website workflow and device acceptance checks](docs/quark-webview.md) before use. Owner setup, real login/SMS and actual website upload behavior require manual device validation.

## Run the backend

Requires Python 3.12+ and uv. From the checkout:

```bash
cd backend
uv sync --frozen
uv run --frozen uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8000
```

SQLite and blobs persist under ignored backend/.data/. Override with BACKUP_DATA_DIR. GET /health reports readiness. DEMO_MODE=1 is the default: each demo registration seeds a valid JPEG, one-second MP4, ZIP and two approved resources. PrivateOwnerFiles is also seeded server-side without granting access.

## Android

Requires JDK 21, Android SDK platform 35 and build-tools 35.0.0. Set ANDROID_HOME. The committed, SHA-256-pinned Gradle Wrapper supplies Gradle; no system Gradle installation is required.

```bash
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

APK: android/app/build/outputs/apk/debug/app-debug.apk.

On an emulator running on the backend's host, debug defaults to http://10.0.2.2:8000/. For a USB-connected phone:

```bash
adb reverse tcp:8000 tcp:8000
./gradlew -PbackupBaseUrl=http://127.0.0.1:8000/ assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For another host, build with -PbackupBaseUrl=https://your-backend.example/. Debug allows cleartext only on loopback/emulator hosts. Release requires HTTPS and defaults to an unusable example address; configure your endpoint and signing before distribution. A remote cloud backend requires your authorized HTTPS deployment or forwarding: 10.0.2.2 addresses the emulator's host, not a remote cloud container.

The current home has 上传文件 / 查看文件. First authenticate through 所有者设置, log into the official website, enter a concrete folder and confirm its binding. Upload selects local documents and confirms names before the official page can request them. File handoff is not upload success. Viewing is not a server-enforced read-only permission. The app does not contact the mock backend from this new home.

The retained native backup repository supports MediaStore/SAF, Room jobs, WorkManager, incremental SHA-256 deduplication and authorized restore against the mock backend. Its previous dashboard is superseded by the new product direction; backend endpoints and tests remain available independently. The mock API's 32 MiB limit does not describe the official website's limits.

## Validation

```bash
cd backend
uv run --frozen pytest -q
uv run --frozen ruff check app tests
uv run --frozen ruff format --check app tests
cd ../android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

API tests exercise authorization, IDOR, expired tokens, integrity, SQLite/blob persistence and demo isolation. Android JVM tests exercise hashing, incremental identity, resource path validation, progress, file limits and retries. Keystore, permissions, SAF, scheduling and UI interactions still require device validation; JVM tests do not prove them. CI runs both suites and uploads the APK.

See [architecture](docs/architecture.md), [security model](docs/security-model.md) and [API](docs/api.md).

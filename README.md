# Device Backup

Independent Kotlin/Compose Android client and FastAPI backend with persistent **MockCloudStorageProvider**. No third-party account, private API, cookies, account credentials or Quark branding is used.

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

Launch and refresh to register this device. Demo resources: IMG_001.jpg, VID_001.mp4, documents.zip, Family Photos and Public Documents. Grant media access and tap Back up now, or Select files using Android SAF. My backups and Allowed content offer Download / restore to a SAF destination. Originals are never deleted. No arbitrary path, resource-ID input, parent directory, global search or other-device view exists.

Automatic backup defaults off and scans at a minimum 15-minute interval, subject to Android scheduling. Wi-Fi only uses Android's unmetered classification. Android 14 partial media access limits counts/backups to permitted media. Files are limited to **32 MiB each**. SAF providers must support persistent read grants for background work.

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

# Codex Task: Restricted Backup Client MVP

Build a complete, runnable Android + backend prototype for a restricted cloud-backup client.

## Product goal

The app is for another Android phone. The user may only:

1. Back up local photos.
2. Back up local videos.
3. Select local files for backup.
4. View backups belonging to the current device.
5. View administrator-approved resources.
6. Download/restore authorized files.

Do not expose any full-drive functionality: no root browsing, global search, recent files, other-device backups, private files, privacy space, recycle bin, share management, arbitrary path navigation, or arbitrary resource-id entry.

This must be an independent client. Do not modify, decompile, repackage, impersonate, or copy branding/UI from the official Quark app.

## Android stack

- Kotlin
- Jetpack Compose
- Material 3
- MVVM
- Coroutines + Flow
- Room
- WorkManager
- MediaStore
- Storage Access Framework
- Android Keystore
- Gradle Kotlin DSL

Choose a reasonable minSdk. The project must build successfully.

## Android screens

### Home

Show:
- automatic backup on/off
- local photo count
- local video count
- pending backup count
- backed-up count
- used cloud space

Actions:
- Back up now
- Select files
- My backups

Do not add a full-drive home page.

### My backups

Only expose the logical namespace:

`/DeviceBackup/{deviceId}/`

The user cannot edit the path, browse to parent directories, or use URL/path/resource-id manipulation to escape this scope.

Support file list, image thumbnails, size, backup time, download and restore.

### Allowed content

Display only resources returned by the backend allowlist.

Model example:

`AllowedResource(id, displayName, type, readOnly)`

Do not provide an arbitrary resource-id input.

## Security model

This is the highest-priority requirement.

The Android app must never store:
- cloud-drive master password
- full-account cookies
- full-account tokens
- administrator tokens

The app stores only a `deviceToken`, protected with Android Keystore.

Authorization rules:

- `READ /DeviceBackup/{deviceId}/**`
- `WRITE /DeviceBackup/{deviceId}/**`
- optional `READ allowedResourceIds`

Anything outside the current device namespace and allowlist must be rejected.

Enforce authorization on the backend, not merely by hiding UI.

## Backend

Use Python FastAPI.

Provide at least:

- `POST /auth/device/register`
- `POST /backup/init`
- `POST /backup/complete`
- `GET /backup/files`
- `GET /backup/files/{id}`
- `GET /backup/files/{id}/download`
- `GET /allowed`
- `GET /allowed/{id}`

Create a centralized authorization layer, e.g.:

- `can_read(device, resource)`
- `can_write(device, resource)`

Every resource operation must perform ACL checks before data access.

Do not accept arbitrary storage paths from the client. Use opaque resource IDs.

## Storage abstraction

Create a storage provider abstraction with operations such as:

- upload
- list
- metadata
- download
- delete

Implement `MockCloudStorageProvider` so the project works end-to-end without external services.

Also create a disabled `QuarkStorageProvider` skeleton only as an authorized integration point.

Do NOT:
- reverse engineer Quark
- extract cookies
- call private APIs
- emulate private signing
- bypass permissions
- embed account credentials

Mark the provider clearly: `Requires official/authorized API integration.`

It must remain disabled unless a legitimate authorized API is configured.

## Backup behavior

Use MediaStore for photos/videos and SAF for user-selected files.

Use WorkManager for background jobs.

Implement:
- incremental backup
- SHA-256 deduplication
- retry on network failure
- progress tracking
- local job database
- Wi-Fi-only option

Never delete the user's original local file.

## Suggested data models

At minimum:
- Device
- BackupItem
- BackupJob
- AllowedResource
- LocalMedia
- RemoteResource

## Demo mode

The app must run in demo mode without third-party APIs.

Create a simulated current device and mock resources:

Current device:
- IMG_001.jpg
- VID_001.mp4
- documents.zip

Allowed content:
- Family Photos
- Public Documents

Unauthorized resource:
- PrivateOwnerFiles

Normal APIs must never expose `PrivateOwnerFiles` to the demo device.

## Tests

Add automated tests proving:

1. current device can read its own backup
2. current device can upload to its own namespace
3. current device cannot read another device's backup
4. allowlisted resources are readable
5. unauthorized resources are rejected
6. modified resource IDs cannot bypass ACL
7. modified path parameters cannot bypass ACL
8. expired device tokens are rejected

Include explicit IDOR tests.

## Repository layout

Create:

- `/android`
- `/backend`
- `/docs`

Docs must include:
- `architecture.md`
- `security-model.md`
- `api.md`

## Validation

Run and fix:
- Android unit tests
- backend tests
- lint/static checks where practical

## Final handoff

When done, summarize:
1. implemented features
2. project structure
3. test results
4. remaining work
5. exact interface required to replace the mock storage provider with an authorized production provider

Never add real account credentials or private Quark endpoints to source control.
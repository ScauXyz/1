# API

From backend: uv run --frozen uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8000

All routes except health/registration require Authorization: Bearer <deviceToken>. JSON is used except raw file bytes. No arbitrary storage paths or administrator tokens are accepted.

| Method | Path | Request / result |
| --- | --- | --- |
| GET | /health | {status: "ok", provider: "mock", demo: true} |
| POST | /auth/device/register | {display_name: "Phone", demo: true} → 201 {deviceId, deviceToken, expiresAt} |
| POST | /backup/init | {name, mime_type, size, sha256} → {resourceId, complete} |
| PUT | /backup/files/{id}/content | Raw bytes matching size/digest → {uploaded: true} |
| POST | /backup/complete | {resource_id: id} → completed metadata |
| GET | /backup/files | Current device's completed backups |
| GET | /backup/files/{id} | Own completed backup metadata |
| GET | /backup/files/{id}/download | Authorized bytes |
| GET | /allowed | Current device's approved resources |
| GET | /allowed/{id} | Granted metadata |
| GET | /allowed/{id}/download | Granted bytes, read only |

Resource: {id, displayName, type, size, sha256, backupTime, readOnly, complete}. Time values are Unix seconds. IDs are canonical opaque UUIDs; type is MIME type. Names are at most 160 characters, file size at most 33,554,432 bytes, and SHA-256 has 64 lowercase hex characters. Unexpected request fields are rejected.

Sequence: init → if complete=false, PUT bytes → complete → list/download. Already completed identical content owned by this device returns its existing ID with complete=true; skip PUT. No cross-device dedupe. Completion can be repeated safely. Retrying init can leave an unused pending resource; production cleanup is still needed.

Errors: 401 missing/invalid/expired token; 403 disabled demo; 404 absent/unauthorized/malformed resource; 409 missing/corrupt content or overwrite attempt; 413 oversized upload; 422 schema/hash/size mismatch. Retry network errors or HTTP 408/429/5xx, not permanent authorization errors.

Demo fixtures are a 32×32 solid-color JPEG, one-second MP4, and harmless ZIP. Family Photos is an approved image; Public Documents an approved ZIP. Their names are labels, not account-wide folders. No private media is included.

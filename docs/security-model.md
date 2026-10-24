# Security model

## Identity

The Android client is untrusted; backend authorization is authoritative. Registration creates a random UUID and 256-bit random device token valid for 30 days. SQLite stores only its SHA-256. Tokens travel in Authorization headers, never query strings. Android encrypts its token with AES-GCM using a non-exportable Android Keystore key. Device ID is ordinary metadata. Application backup is disabled. No full-account/cloud-drive/administrator credential exists on Android.

A Keystore failure or expired token is shown without silently replacing device identity. Recovery/rotation is not implemented. Reinstalling/clearing app data loses access unless the server operator implements a separate trusted recovery process.

## Scope

The logical namespace is /DeviceBackup/{authenticatedDeviceId}/; it is a label and authorization rule, never a client-selected storage path. can_write requires a backup owned by that device. can_read permits the same ownership or an allowed-kind resource with a server-side grant to that device.

Every metadata/download/upload/complete operation checks ownership or grants before blob access. Lists filter by authenticated identity, never a supplied device ID. Allowed resources cannot be written through backup routes. Unknown, malformed and unauthorized IDs return the same 404. Query manipulation cannot change namespaces. Strict JSON schemas reject injected owner/path/grant fields.

Names are display-only and reject separators/control characters. Storage keys are canonical server UUIDs. Upload size and SHA-256 are checked, pending files are hidden, and completed content cannot be overwritten. Client redirects are disabled to avoid forwarding tokens to another origin. Release requires HTTPS; debug allows HTTP only on emulator/loopback hosts.

PrivateOwnerFiles is an ungranted demo fixture and cannot appear in any device's successful normal reads. Cross-device backup access and cross-device dedupe discovery are denied even when an attacker knows IDs/hashes.

## Tests

Backend tests cover cross-device reads/writes/completion, grants, endpoint confusion, modified IDs, encoded path traversal, owner/path injection, invalid/expired tokens, pending downloads, corrupt content and per-device dedupe. A storage spy proves denied IDOR requests never reach blob read/write. Android endpoint/hash tests are defense in depth and do not replace ACL enforcement.

## Prototype boundaries

Registration is unauthenticated for local demo use. Each call creates a new device and cannot select another identity or retrieve its token. Public deployment needs trusted pairing, registration throttling, quotas, revocation and recovery. DEMO_MODE=0 disables demo registrations; Android -PdemoMode=false requests an empty device. That flag does not implement production pairing.

The SQLite/blob mock is not an encrypted production cloud. The operator must manage filesystem permissions, HTTPS termination, retention, safe logs and deployment credentials. Never log Authorization headers. Rooted/malicious OS compromise and stolen unlocked devices are outside this prototype's protection. SHA-256 provides integrity/deduplication, not end-to-end encryption.

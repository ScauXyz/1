import hashlib
import io
import os
import secrets
import sqlite3
import threading
import time
import zipfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .security import can_read, can_write
from .storage import MockCloudStorageProvider

MAX_FILE_SIZE = 32 * 1024 * 1024
TOKEN_TTL = 30 * 24 * 3600


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Register(StrictModel):
    display_name: str = Field(default="My Android", min_length=1, max_length=80)
    demo: bool = True


class BackupInit(StrictModel):
    name: str = Field(min_length=1, max_length=160)
    mime_type: str = Field(
        default="application/octet-stream",
        max_length=120,
        pattern=r"^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$",
    )
    size: int = Field(ge=0, le=MAX_FILE_SIZE)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")

    @field_validator("name")
    @classmethod
    def safe_filename(cls, value: str) -> str:
        if value in {".", ".."} or any(c in value for c in "/\\\x00\r\n"):
            raise ValueError("Only a display filename is accepted")
        if any(ord(c) < 32 for c in value):
            raise ValueError("Invalid display filename")
        return value


class BackupComplete(StrictModel):
    resource_id: str


def create_app(data_dir: Path | None = None, demo_enabled: bool | None = None) -> FastAPI:
    data_dir = data_dir or Path(os.getenv("BACKUP_DATA_DIR", ".data"))
    demo_enabled = demo_enabled if demo_enabled is not None else os.getenv("DEMO_MODE", "1") == "1"
    data_dir.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(data_dir / "backup.sqlite3", check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript("""
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
            token_hash TEXT UNIQUE NOT NULL, expires_at REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS resources (
            id TEXT PRIMARY KEY, device_id TEXT, kind TEXT NOT NULL,
            name TEXT NOT NULL, mime_type TEXT NOT NULL, size INTEGER NOT NULL,
            sha256 TEXT NOT NULL, created_at REAL NOT NULL, complete INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS grants (
            device_id TEXT NOT NULL REFERENCES devices(id),
            resource_id TEXT NOT NULL REFERENCES resources(id),
            PRIMARY KEY(device_id, resource_id));
        CREATE INDEX IF NOT EXISTS resource_owner ON resources(device_id, kind, complete);
    """)
    lock = threading.RLock()
    storage = MockCloudStorageProvider(data_dir / "blobs")

    @asynccontextmanager
    async def lifespan(_app):
        yield
        db.close()

    app = FastAPI(title="Restricted Backup", version="0.1.0", lifespan=lifespan)
    app.state.db, app.state.storage = db, storage
    app.state.lock = lock
    bearer = HTTPBearer(auto_error=False)

    def get_device(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]):
        if credentials is None:
            raise HTTPException(401, "Device token required")
        digest = hashlib.sha256(credentials.credentials.encode()).hexdigest()
        with lock:
            row = db.execute("SELECT * FROM devices WHERE token_hash=?", (digest,)).fetchone()
        if row is None or row["expires_at"] <= time.time():
            raise HTTPException(401, "Invalid or expired device token")
        return dict(row)

    Device = Annotated[dict, Depends(get_device)]

    def grants(device):
        return {
            r[0]
            for r in db.execute("SELECT resource_id FROM grants WHERE device_id=?", (device["id"],))
        }

    def authorized(device, resource_id, kind, write=False, completed=True):
        try:
            if str(UUID(resource_id)) != resource_id:
                raise ValueError
        except ValueError:
            raise HTTPException(404, "Resource not found") from None
        row = db.execute("SELECT * FROM resources WHERE id=?", (resource_id,)).fetchone()
        if row is None or row["kind"] != kind:
            raise HTTPException(404, "Resource not found")
        permitted = can_write(device, row) if write else can_read(device, row, grants(device))
        if not permitted or (completed and not row["complete"]):
            raise HTTPException(404, "Resource not found")
        return row

    def view(row):
        return {
            "id": row["id"],
            "displayName": row["name"],
            "type": row["mime_type"],
            "size": row["size"],
            "sha256": row["sha256"],
            "backupTime": row["created_at"],
            "readOnly": row["kind"] == "allowed",
            "complete": bool(row["complete"]),
        }

    def insert_resource(owner, kind, name, mime, data):
        rid = str(uuid4())
        storage.upload(rid, data)
        db.execute(
            "INSERT INTO resources VALUES (?,?,?,?,?,?,?,?,1)",
            (
                rid,
                owner,
                kind,
                name,
                mime,
                len(data),
                hashlib.sha256(data).hexdigest(),
                time.time(),
            ),
        )
        return rid

    def demo_seed(device_id):
        fixtures = Path(__file__).with_name("fixtures")
        image = (fixtures / "IMG_001.jpg").read_bytes()
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("demo.txt", "Demo document. No personal files or third-party APIs.")
        insert_resource(device_id, "backup", "IMG_001.jpg", "image/jpeg", image)
        insert_resource(
            device_id, "backup", "VID_001.mp4", "video/mp4", (fixtures / "VID_001.mp4").read_bytes()
        )
        insert_resource(device_id, "backup", "documents.zip", "application/zip", archive.getvalue())
        for name, mime, data in [
            ("Family Photos", "image/jpeg", image),
            ("Public Documents", "application/zip", archive.getvalue()),
        ]:
            rid = insert_resource(None, "allowed", name, mime, data)
            db.execute("INSERT INTO grants VALUES (?,?)", (device_id, rid))
        if not db.execute("SELECT 1 FROM resources WHERE name='PrivateOwnerFiles'").fetchone():
            insert_resource(
                "private-owner",
                "allowed",
                "PrivateOwnerFiles",
                "text/plain",
                b"Unauthorized fixture",
            )

    @app.get("/health")
    def health():
        return {"status": "ok", "provider": "mock", "demo": demo_enabled}

    @app.post("/auth/device/register", status_code=201)
    def register(body: Register):
        if body.demo and not demo_enabled:
            raise HTTPException(403, "Demo mode disabled")
        token, did = secrets.token_urlsafe(32), str(uuid4())
        expires = time.time() + TOKEN_TTL
        with lock, db:
            db.execute(
                "INSERT INTO devices VALUES (?,?,?,?)",
                (did, body.display_name, hashlib.sha256(token.encode()).hexdigest(), expires),
            )
            if body.demo:
                demo_seed(did)
        return {"deviceId": did, "deviceToken": token, "expiresAt": expires}

    @app.post("/backup/init")
    def initialize(body: BackupInit, device: Device):
        with lock, db:
            existing = db.execute(
                "SELECT * FROM resources WHERE device_id=? AND kind='backup' AND sha256=? "
                "AND size=? AND complete=1",
                (device["id"], body.sha256, body.size),
            ).fetchone()
            if existing:
                return {"resourceId": existing["id"], "complete": True}
            rid = str(uuid4())
            db.execute(
                "INSERT INTO resources VALUES (?,?,?,?,?,?,?,?,0)",
                (
                    rid,
                    device["id"],
                    "backup",
                    body.name,
                    body.mime_type,
                    body.size,
                    body.sha256,
                    time.time(),
                ),
            )
            return {"resourceId": rid, "complete": False}

    @app.put("/backup/files/{resource_id}/content")
    async def upload(resource_id: str, request: Request, device: Device):
        with lock:
            row = authorized(device, resource_id, "backup", write=True, completed=False)
            expected_size = row["size"]
            if row["complete"]:
                raise HTTPException(409, "Backup already completed")
        chunks, received = [], 0
        async for chunk in request.stream():
            received += len(chunk)
            if received > expected_size or received > MAX_FILE_SIZE:
                raise HTTPException(413, "Upload exceeds declared size")
            chunks.append(chunk)
        data = b"".join(chunks)
        with lock:
            row = authorized(device, resource_id, "backup", write=True, completed=False)
            if row["complete"]:
                raise HTTPException(409, "Backup already completed")
            if received != row["size"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                raise HTTPException(422, "Size or SHA-256 mismatch")
            storage.upload(resource_id, data)
        return {"uploaded": True}

    @app.post("/backup/complete")
    def complete(body: BackupComplete, device: Device):
        with lock, db:
            row = authorized(device, body.resource_id, "backup", write=True, completed=False)
            try:
                data = storage.download(row["id"])
            except FileNotFoundError:
                raise HTTPException(409, "Upload content first") from None
            if len(data) != row["size"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                raise HTTPException(409, "Stored content integrity mismatch")
            db.execute("UPDATE resources SET complete=1 WHERE id=?", (row["id"],))
            return view(db.execute("SELECT * FROM resources WHERE id=?", (row["id"],)).fetchone())

    @app.get("/backup/files")
    def files(device: Device):
        with lock:
            rows = db.execute(
                "SELECT * FROM resources WHERE device_id=? AND kind='backup' "
                "AND complete=1 ORDER BY created_at DESC",
                (device["id"],),
            ).fetchall()
            return [view(r) for r in rows if can_read(device, r, set())]

    @app.get("/allowed")
    def allowed(device: Device):
        with lock:
            ids = grants(device)
            rows = db.execute(
                "SELECT * FROM resources WHERE kind='allowed' AND complete=1"
            ).fetchall()
            return [view(r) for r in rows if can_read(device, r, ids)]

    def get_metadata(device, rid, kind):
        with lock:
            return view(authorized(device, rid, kind))

    def get_download(device, rid, kind):
        with lock:
            row = authorized(device, rid, kind)
            try:
                data = storage.download(row["id"])
            except FileNotFoundError:
                raise HTTPException(404, "Content unavailable") from None
            # No filename supplied in headers; the Android SAF picker chooses a destination.
            return Response(
                data,
                media_type=row["mime_type"],
                headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "no-store"},
            )

    @app.get("/backup/files/{resource_id}")
    def metadata(resource_id: str, device: Device):
        return get_metadata(device, resource_id, "backup")

    @app.get("/backup/files/{resource_id}/download")
    def download(resource_id: str, device: Device):
        return get_download(device, resource_id, "backup")

    @app.get("/allowed/{resource_id}")
    def allowed_metadata(resource_id: str, device: Device):
        return get_metadata(device, resource_id, "allowed")

    @app.get("/allowed/{resource_id}/download")
    def allowed_download(resource_id: str, device: Device):
        return get_download(device, resource_id, "allowed")

    return app

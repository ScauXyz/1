import hashlib
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import MAX_FILE_SIZE, create_app
from app.storage import QuarkStorageProvider


@pytest.fixture
def api(tmp_path):
    application = create_app(tmp_path)
    with TestClient(application) as client:
        yield client, application


def register(client, demo=False):
    response = client.post(
        "/auth/device/register", json={"display_name": "Test phone", "demo": demo}
    )
    assert response.status_code == 201
    value = response.json()
    return value, {"Authorization": f"Bearer {value['deviceToken']}"}


def initialize(client, headers, data=b"photo bytes", **overrides):
    body = {
        "name": "image.jpg",
        "mime_type": "image/jpeg",
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        **overrides,
    }
    return client.post("/backup/init", headers=headers, json=body)


def backup(client, headers, data=b"photo bytes"):
    response = initialize(client, headers, data)
    assert response.status_code == 200, response.text
    rid = response.json()["resourceId"]
    if not response.json()["complete"]:
        assert (
            client.put(f"/backup/files/{rid}/content", headers=headers, content=data).status_code
            == 200
        )
        assert (
            client.post("/backup/complete", headers=headers, json={"resource_id": rid}).status_code
            == 200
        )
    return rid


def test_registration_does_not_store_raw_token(api):
    client, app = api
    device, _ = register(client)
    row = app.state.db.execute("SELECT * FROM devices WHERE id=?", (device["deviceId"],)).fetchone()
    assert row["token_hash"] == hashlib.sha256(device["deviceToken"].encode()).hexdigest()
    assert device["deviceToken"] not in str(dict(row))


def test_own_upload_list_metadata_download_and_deduplication(api):
    client, _ = api
    device, headers = register(client)
    rid = backup(client, headers)
    listing = client.get("/backup/files", headers=headers).json()
    assert len(listing) == 1 and listing[0]["id"] == rid
    metadata = client.get(f"/backup/files/{rid}", headers=headers)
    assert metadata.status_code == 200 and metadata.json()["size"] == len(b"photo bytes")
    assert device["deviceId"] not in str(metadata.json())
    response = client.get(f"/backup/files/{rid}/download", headers=headers)
    assert response.content == b"photo bytes"
    assert response.headers["cache-control"] == "no-store"
    assert initialize(client, headers).json() == {"resourceId": rid, "complete": True}


@pytest.mark.parametrize("operation", ["metadata", "download", "upload", "complete"])
def test_idor_other_device_denied_before_storage_access(api, monkeypatch, operation):
    client, app = api
    _, owner = register(client)
    _, attacker = register(client)
    rid = backup(client, owner)

    def forbidden(*args, **kwargs):
        pytest.fail("Unauthorized request reached storage")

    monkeypatch.setattr(app.state.storage, "download", forbidden)
    monkeypatch.setattr(app.state.storage, "upload", forbidden)
    if operation == "complete":
        response = client.post("/backup/complete", headers=attacker, json={"resource_id": rid})
    elif operation == "upload":
        response = client.put(f"/backup/files/{rid}/content", headers=attacker, content=b"attack")
    else:
        suffix = "/download" if operation == "download" else ""
        response = client.get(f"/backup/files/{rid}{suffix}", headers=attacker)
    assert response.status_code == 404
    assert client.get("/backup/files", headers=attacker).json() == []


def test_dedupe_does_not_reveal_other_device_hash(api):
    client, _ = api
    _, first = register(client)
    _, second = register(client)
    rid = backup(client, first)
    response = initialize(client, second)
    assert response.json()["complete"] is False
    assert response.json()["resourceId"] != rid


def test_allowlist_demo_and_private_denied(api):
    client, app = api
    _, headers = register(client, demo=True)
    assert {r["displayName"] for r in client.get("/backup/files", headers=headers).json()} == {
        "IMG_001.jpg",
        "VID_001.mp4",
        "documents.zip",
    }
    resources = client.get("/allowed", headers=headers).json()
    assert {r["displayName"] for r in resources} == {"Family Photos", "Public Documents"}
    for row in resources:
        rid = row["id"]
        assert row["readOnly"]
        assert client.get(f"/allowed/{rid}", headers=headers).status_code == 200
        assert client.get(f"/allowed/{rid}/download", headers=headers).status_code == 200
        assert (
            client.put(f"/backup/files/{rid}/content", headers=headers, content=b"x").status_code
            == 404
        )
        assert client.get(f"/backup/files/{rid}", headers=headers).status_code == 404
    private = app.state.db.execute(
        "SELECT id FROM resources WHERE name='PrivateOwnerFiles'"
    ).fetchone()[0]
    for suffix in ["", "/download"]:
        assert client.get(f"/allowed/{private}{suffix}", headers=headers).status_code == 404
    _, ungranted = register(client)
    assert client.get(f"/allowed/{resources[0]['id']}", headers=ungranted).status_code == 404


@pytest.mark.parametrize(
    "rid",
    [
        "../../PrivateOwnerFiles",
        "%2e%2e%2fPrivateOwnerFiles",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
    ],
)
def test_modified_id_and_path_cannot_escape_scope(api, rid):
    client, _ = api
    _, headers = register(client)
    for root in ["/backup/files", "/allowed"]:
        assert client.get(f"{root}/{rid}/download", headers=headers).status_code == 404
    assert (
        client.post("/backup/complete", headers=headers, json={"resource_id": rid}).status_code
        == 404
    )


@pytest.mark.parametrize(
    "extra",
    [
        {"path": "/DeviceBackup/other/"},
        {"device_id": "other"},
        {"allowedResourceIds": ["private"]},
        {"resource_id": "private"},
    ],
)
def test_path_or_owner_injection_is_rejected(api, extra):
    client, _ = api
    _, headers = register(client)
    assert initialize(client, headers, **extra).status_code == 422


@pytest.mark.parametrize("name", ["../photo.jpg", "/absolute", "a\\b", "..", "a\r\nb", "a\x00b"])
def test_filename_path_injection_rejected(api, name):
    client, _ = api
    _, headers = register(client)
    assert initialize(client, headers, name=name).status_code == 422


def test_query_parameters_cannot_select_another_namespace(api):
    client, _ = api
    owner, owner_headers = register(client)
    _, attacker = register(client)
    backup(client, owner_headers)
    response = client.get(
        "/backup/files",
        headers=attacker,
        params={"device_id": owner["deviceId"], "path": f"/DeviceBackup/{owner['deviceId']}/"},
    )
    assert response.json() == []


@pytest.mark.parametrize(
    "headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": "Basic x"}]
)
def test_missing_invalid_tokens_rejected(api, headers):
    client, _ = api
    assert client.get("/backup/files", headers=headers).status_code == 401
    assert client.get("/allowed", headers=headers).status_code == 401
    assert initialize(client, headers).status_code == 401


def test_expired_token_rejected(api):
    client, app = api
    device, headers = register(client)
    with app.state.db:
        app.state.db.execute("UPDATE devices SET expires_at=0 WHERE id=?", (device["deviceId"],))
    assert client.get("/backup/files", headers=headers).status_code == 401
    assert initialize(client, headers).status_code == 401


def test_pending_content_integrity_and_immutability(api):
    client, _ = api
    _, headers = register(client)
    rid = initialize(client, headers).json()["resourceId"]
    assert client.get("/backup/files", headers=headers).json() == []
    assert client.get(f"/backup/files/{rid}/download", headers=headers).status_code == 404
    assert (
        client.post("/backup/complete", headers=headers, json={"resource_id": rid}).status_code
        == 409
    )
    assert (
        client.put(f"/backup/files/{rid}/content", headers=headers, content=b"wrong").status_code
        == 422
    )
    assert (
        client.put(
            f"/backup/files/{rid}/content", headers=headers, content=b"too many bytes to upload"
        ).status_code
        == 413
    )
    assert (
        client.put(
            f"/backup/files/{rid}/content", headers=headers, content=b"photo bytes"
        ).status_code
        == 200
    )
    assert (
        client.post("/backup/complete", headers=headers, json={"resource_id": rid}).status_code
        == 200
    )
    assert (
        client.put(
            f"/backup/files/{rid}/content", headers=headers, content=b"photo bytes"
        ).status_code
        == 409
    )


def test_limits_and_corrupt_stored_content(api):
    client, app = api
    _, headers = register(client)
    assert initialize(client, headers, size=MAX_FILE_SIZE + 1).status_code == 422
    rid = backup(client, headers)
    app.state.storage.upload(rid, b"corrupt")
    assert (
        client.post("/backup/complete", headers=headers, json={"resource_id": rid}).status_code
        == 409
    )


def test_persistence(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        _, headers = register(client)
        rid = backup(client, headers)
    with TestClient(create_app(tmp_path)) as client:
        assert (
            client.get(f"/backup/files/{rid}/download", headers=headers).content == b"photo bytes"
        )


def test_demo_can_be_disabled(tmp_path):
    with TestClient(create_app(tmp_path, demo_enabled=False)) as client:
        assert client.post("/auth/device/register", json={"demo": True}).status_code == 403
        assert client.post("/auth/device/register", json={"demo": False}).status_code == 201


def test_storage_and_disabled_provider(api):
    _, app = api
    key = str(uuid4())
    provider = app.state.storage
    provider.upload(key, b"abc")
    assert provider.metadata(key)["size"] == 3
    assert key in provider.list()
    provider.delete(key)
    assert key not in provider.list()
    with pytest.raises(ValueError):
        provider.download("../private")
    with pytest.raises(RuntimeError, match="official/authorized"):
        QuarkStorageProvider()

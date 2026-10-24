"""Exercise the actual ASGI server/socket, not only the in-process test client."""

import hashlib
import os
import socket
import subprocess
import sys
import time

import httpx


def test_real_http_backup_restore_and_isolation(tmp_path):
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with (tmp_path / "server.log").open("w") as log:
        process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "app.main:create_app",
                "--factory",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            env={**os.environ, "BACKUP_DATA_DIR": str(tmp_path / "data"), "DEMO_MODE": "1"},
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", trust_env=False) as client:
                for _ in range(100):
                    assert process.poll() is None, "Server exited before startup"
                    try:
                        assert client.get("/health").json()["provider"] == "mock"
                        break
                    except httpx.ConnectError:
                        time.sleep(0.05)
                else:
                    raise AssertionError("Server did not become ready")
                identity = client.post("/auth/device/register", json={"demo": True}).json()
                headers = {"Authorization": f"Bearer {identity['deviceToken']}"}
                assert len(client.get("/backup/files", headers=headers).json()) == 3
                approved = client.get("/allowed", headers=headers).json()
                assert {r["displayName"] for r in approved} == {"Family Photos", "Public Documents"}
                for resource in approved:
                    content = client.get(
                        f"/allowed/{resource['id']}/download", headers=headers
                    ).content
                    assert hashlib.sha256(content).hexdigest() == resource["sha256"]
                data = b"Actual HTTP upload and verified restore"
                response = client.post(
                    "/backup/init",
                    headers=headers,
                    json={
                        "name": "http.txt",
                        "mime_type": "text/plain",
                        "size": len(data),
                        "sha256": hashlib.sha256(data).hexdigest(),
                    },
                )
                assert response.status_code == 200
                rid = response.json()["resourceId"]
                assert (
                    client.put(
                        f"/backup/files/{rid}/content", headers=headers, content=data
                    ).status_code
                    == 200
                )
                assert (
                    client.post(
                        "/backup/complete", headers=headers, json={"resource_id": rid}
                    ).status_code
                    == 200
                )
                assert client.get(f"/backup/files/{rid}/download", headers=headers).content == data
                other = client.post("/auth/device/register", json={"demo": False}).json()
                denied = client.get(
                    f"/backup/files/{rid}/download",
                    headers={"Authorization": f"Bearer {other['deviceToken']}"},
                )
                assert denied.status_code == 404
        finally:
            process.terminate()
            process.wait(timeout=10)

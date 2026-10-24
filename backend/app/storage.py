"""Storage keys are server-generated UUIDs; client filenames are never paths."""

import os
import tempfile
from abc import ABC, abstractmethod
from pathlib import Path
from uuid import UUID


class StorageProvider(ABC):
    @abstractmethod
    def upload(self, key: str, content: bytes) -> None: ...

    @abstractmethod
    def list(self) -> list[str]: ...

    @abstractmethod
    def metadata(self, key: str) -> dict: ...

    @abstractmethod
    def download(self, key: str) -> bytes: ...

    @abstractmethod
    def delete(self, key: str) -> None: ...


class MockCloudStorageProvider(StorageProvider):
    """Persistent local blobs, atomically replaced. No third-party connectivity."""

    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        if str(UUID(key)) != key:
            raise ValueError("Invalid internal storage key")
        return self.root / f"{key}.blob"

    def upload(self, key: str, content: bytes) -> None:
        destination = self._path(key)
        fd, temporary = tempfile.mkstemp(dir=self.root)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(content)
            os.replace(temporary, destination)
        finally:
            Path(temporary).unlink(missing_ok=True)

    def list(self) -> list[str]:
        return sorted(p.stem for p in self.root.glob("*.blob"))

    def metadata(self, key: str) -> dict:
        return {"key": key, "size": self._path(key).stat().st_size}

    def download(self, key: str) -> bytes:
        return self._path(key).read_bytes()

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)


class QuarkStorageProvider(StorageProvider):
    """Requires official/authorized API integration. Intentionally disabled."""

    def __init__(self):
        raise RuntimeError("Requires official/authorized API integration.")

    def upload(self, key: str, content: bytes) -> None:
        raise NotImplementedError

    def list(self) -> list[str]:
        raise NotImplementedError

    def metadata(self, key: str) -> dict:
        raise NotImplementedError

    def download(self, key: str) -> bytes:
        raise NotImplementedError

    def delete(self, key: str) -> None:
        raise NotImplementedError

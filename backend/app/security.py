"""Single authorization policy, shared by every resource operation."""

from collections.abc import Mapping


def can_write(device: Mapping, resource: Mapping) -> bool:
    return resource["kind"] == "backup" and resource["device_id"] == device["id"]


def can_read(device: Mapping, resource: Mapping, allowed_ids: set[str]) -> bool:
    return can_write(device, resource) or (
        resource["kind"] == "allowed" and resource["id"] in allowed_ids
    )

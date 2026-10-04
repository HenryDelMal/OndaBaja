#!/usr/bin/env python3
"""Convert a station-directory JSON file to the StationDirectory protobuf wire format.

This uses only the Python standard library. The field numbers and types are defined
in ../proto/station_directory.proto.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varints in this schema must be non-negative")
    out = bytearray()
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def encode_key(field_number: int, wire_type: int) -> bytes:
    return encode_varint((field_number << 3) | wire_type)


def encode_uint(field_number: int, value: int) -> bytes:
    return encode_key(field_number, 0) + encode_varint(value)


def encode_bytes(field_number: int, value: bytes) -> bytes:
    return encode_key(field_number, 2) + encode_varint(len(value)) + value


def require_string(obj: dict[str, Any], key: str, where: str, *, required: bool) -> str | None:
    if key not in obj:
        if required:
            raise ValueError(f"{where}.{key} is required")
        return None
    value = obj[key]
    if not isinstance(value, str) or (required and not value.strip()):
        raise ValueError(f"{where}.{key} must be a non-empty string")
    return value


def encode_station(value: Any, index: int) -> bytes:
    where = f"stations[{index}]"
    if not isinstance(value, dict):
        raise ValueError(f"{where} must be an object")

    out = bytearray()
    for field_number, key in ((1, "id"), (2, "name"), (3, "url")):
        text = require_string(value, key, where, required=True)
        out += encode_bytes(field_number, text.encode("utf-8"))

    for field_number, key in ((4, "protobuf"), (5, "tcp")):
        if key in value:
            flag = value[key]
            if not isinstance(flag, bool):
                raise ValueError(f"{where}.{key} must be true or false")
            # These are proto3 optional bools; encode explicit false too, so field
            # presence round-trips distinctly from an omitted property.
            out += encode_uint(field_number, int(flag))

    tcp_url = require_string(value, "tcp_url", where, required=False)
    if tcp_url is not None:
        out += encode_bytes(6, tcp_url.encode("utf-8"))
    if value.get("tcp") is False and tcp_url is not None:
        raise ValueError(f"{where}.tcp_url cannot be set when tcp is false")
    if value.get("tcp") is True and not tcp_url:
        raise ValueError(f"{where}.tcp_url is required when tcp is true")
    return bytes(out)


def encode_directory(value: Any) -> bytes:
    if not isinstance(value, dict):
        raise ValueError("JSON root must be an object")
    version = value.get("version")
    if isinstance(version, bool) or not isinstance(version, int) or not 0 <= version <= 0xFFFFFFFF:
        raise ValueError("version must be an unsigned 32-bit integer")
    stations = value.get("stations")
    if not isinstance(stations, list):
        raise ValueError("stations must be an array")

    out = bytearray()
    if version != 0:
        out += encode_uint(1, version)
    for index, station in enumerate(stations):
        out += encode_bytes(2, encode_station(station, index))
    return bytes(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="input station-directory JSON")
    parser.add_argument("output", type=Path, help="output protobuf binary (.pb)")
    args = parser.parse_args()

    try:
        data = json.loads(args.input.read_text(encoding="utf-8"))
        encoded = encode_directory(data)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(encoded)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"Wrote {len(encoded)} bytes to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

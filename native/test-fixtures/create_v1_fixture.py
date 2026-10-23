#!/usr/bin/env python3
"""Generate deterministic 32 KiB legal V1 BitTorrent fixture (payload.txt + payload.torrent)."""

import hashlib
import pathlib
import sys


PIECE_LENGTH = 16384
TOTAL_LENGTH = 32768
FILE_NAME = b"payload.txt"


def build_payload() -> bytes:
    # Deterministic 32 KiB payload (256 * 128 = 32768)
    data = bytes(range(256)) * 128
    assert len(data) == TOTAL_LENGTH
    return data


def build_v1_torrent(payload: bytes) -> bytes:
    pieces = b"".join(
        hashlib.sha1(payload[offset : offset + PIECE_LENGTH]).digest()
        for offset in range(0, len(payload), PIECE_LENGTH)
    )
    assert len(pieces) == 40
    # Bencode dictionary keys in lexicographical order: length, name, piece length, pieces
    info = (
        b"d"
        + f"6:lengthi{len(payload)}e".encode("ascii")
        + f"4:name{len(FILE_NAME)}:".encode("ascii")
        + FILE_NAME
        + f"12:piece lengthi{PIECE_LENGTH}e".encode("ascii")
        + f"6:pieces{len(pieces)}:".encode("ascii")
        + pieces
        + b"e"
    )
    return b"d4:info" + info + b"e"


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python create_v1_fixture.py <output_dir>", file=sys.stderr)
        return 1
    out_dir = pathlib.Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = build_payload()
    torrent = build_v1_torrent(payload)
    (out_dir / "payload.txt").write_bytes(payload)
    (out_dir / "payload.torrent").write_bytes(torrent)
    print(f"Generated {out_dir / 'payload.txt'} ({len(payload)} bytes) and {out_dir / 'payload.torrent'} ({len(torrent)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

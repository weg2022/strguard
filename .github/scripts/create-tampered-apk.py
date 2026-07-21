#!/usr/bin/env python3
import argparse
import struct
import zipfile
from pathlib import Path


def tamper_vault(library: bytes) -> bytes:
    positions = []
    start = 0
    while True:
        position = library.find(b"SGV3", start)
        if position < 0:
            break
        positions.append(position)
        start = position + 4
    if len(positions) != 1:
        raise ValueError(f"expected one SGV3 vault, found {len(positions)}")

    data = bytearray(library)
    cursor = positions[0] + 4
    version = data[cursor]
    if version != 3:
        raise ValueError(f"expected vault version 3, found {version}")
    cursor += 1 + 16
    record_count = struct.unpack_from("<I", data, cursor)[0]
    if record_count == 0:
        raise ValueError("cannot tamper an empty vault")
    cursor += 4
    body_length = struct.unpack_from("<I", data, cursor)[0]
    cursor += 4
    body_end = cursor + body_length
    cursor += 16 + 1 + 12 + 4
    ciphertext_length = struct.unpack_from("<I", data, cursor)[0]
    cursor += 4
    if ciphertext_length == 0 or cursor + ciphertext_length > body_end:
        raise ValueError("invalid first vault record ciphertext")
    data[cursor] ^= 1
    return bytes(data)


def create_tampered_apk(source: Path, destination: Path) -> None:
    tampered_libraries = 0
    with zipfile.ZipFile(source, "r") as input_apk, zipfile.ZipFile(destination, "w") as output_apk:
        for entry in input_apk.infolist():
            upper_name = entry.filename.upper()
            if upper_name.startswith("META-INF/") and upper_name.endswith((".RSA", ".DSA", ".EC", ".SF", ".MF")):
                continue
            contents = input_apk.read(entry)
            if entry.filename.startswith("lib/") and entry.filename.endswith(".so"):
                contents = tamper_vault(contents)
                tampered_libraries += 1
            output_apk.writestr(entry, contents)
    if tampered_libraries != 1:
        destination.unlink(missing_ok=True)
        raise ValueError(f"expected one split-APK Native library, tampered {tampered_libraries}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    create_tampered_apk(arguments.source, arguments.destination)
    print(f"Tampered one SGV3 ciphertext in {arguments.destination}")


if __name__ == "__main__":
    main()

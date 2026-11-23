import base64
import hashlib
import os
import struct
import zipfile
import zlib
from pathlib import Path

folder = Path(__file__).resolve().parent
with zipfile.ZipFile("unsigned.zip") as archive:
    member = "app-gms-mobile-arm64-release-unsigned.apk"
    if archive.namelist() != [member]:
        raise ValueError("Unexpected build artifact contents")
    build = archive.read(member)

encoded = "".join(part.read_text(encoding="ascii") for part in sorted(folder.glob("delta-*.txt")))
data = zlib.decompress(base64.b64decode(encoded, validate=True))
count, = struct.unpack_from("<I", data)
steps = struct.iter_unpack("<III", data[4:4 + 12 * count])
literal_offset = 4 + 12 * count
apk = bytearray()
for literal_size, build_start, build_size in steps:
    apk.extend(data[literal_offset:literal_offset + literal_size])
    apk.extend(build[build_start:build_start + build_size])
    literal_offset += literal_size
apk.extend(data[literal_offset:])
if len(apk) != 33578552 or hashlib.sha256(apk).hexdigest() != os.environ["SIGNED_SHA256"]:
    raise ValueError("Reassembled APK differs from the release-signed file")
Path("app-gms-mobile-arm64-release.apk").write_bytes(apk)

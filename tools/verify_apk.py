#!/usr/bin/env python3
"""Check the packaged runtime contract, including AAPT asset-name transformations."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(path):
    with zipfile.ZipFile(path) as apk:
        manifest = json.loads(apk.read("assets/runtime/manifest.json"))
        for group in ("ubuntu", "engine"):
            entry = manifest[group]
            name = "assets/runtime/" + entry["file"]
            assert apk.getinfo(name).file_size == entry["bytes"], name
            h = hashlib.sha256()
            with apk.open(name) as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    h.update(chunk)
            assert h.hexdigest() == entry["sha256"], name
        for name, entry in manifest["libraries"].items():
            binary = apk.read("lib/arm64-v8a/" + name)
            assert binary[:4] == b"\x7fELF" and int.from_bytes(binary[18:20], "little") == 183, name
            assert hashlib.sha256(binary).hexdigest() == entry["sha256"], name
        assert "assets/NOTICE.txt" in apk.namelist()
    print(json.dumps({"apk": str(path), "bytes": Path(path).stat().st_size, "runtimeAssets": "verified", "nativeABI": "arm64-v8a", "opencodeVersion": manifest["opencodeVersion"]}))


if __name__ == "__main__":
    verify(sys.argv[1])

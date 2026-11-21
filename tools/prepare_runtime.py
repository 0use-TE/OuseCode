#!/usr/bin/env python3
"""Compose OuseCode's own Ubuntu bundle from verified DSH Go build inputs.

No DSH engine, DSH configuration, user files, or signing material is copied.
Run under Linux/WSL so the root filesystem retains POSIX modes and links.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

BASE_SHA = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
NODE_SHA = "dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e"
BASE_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
ENGINE_URL = "https://opencode.ai/update/api/latest/cli/opencode/opencode-linux-arm64.tar.gz"


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as file:
        for part in iter(lambda: file.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()


def verify(path, expected):
    if digest(path) != expected:
        raise RuntimeError("SHA-256 mismatch: " + str(path))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsh", type=Path, required=True, help="DSH Go checkout with cached ARM64 runtime inputs")
    ap.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    ap.add_argument("--engine-archive", type=Path, required=True)
    ap.add_argument("--engine-version", default="2.3.2")
    args = ap.parse_args()
    repo = args.source.resolve()
    cache = args.dsh / "runtime/.cache"
    assets = repo / "app/src/main/assets/runtime"
    assets.mkdir(parents=True, exist_ok=True)
    staging = repo / ".cache/rootfs-build"
    if staging.exists():
        # Only this fixed build directory is disposable.
        assert staging.resolve().parent == (repo / ".cache").resolve()
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    base = cache / "ubuntu/24.04.5/ubuntu-base-24.04.5-base-arm64.tar.gz"
    verify(base, BASE_SHA)
    subprocess.run(["tar", "--no-same-owner", "-xzf", str(base), "-C", str(staging)], check=True)
    package_lock = json.loads((args.dsh / "runtime/locks/ubuntu-noble-arm64.json").read_text())
    for package in package_lock["packages"]:
        deb = cache / "ubuntu/debs" / Path(package["filename"]).name
        verify(deb, package["sha256"])
        subprocess.run(["dpkg-deb", "-x", str(deb), str(staging)], check=True)
    node = cache / "node/v24.22.1/node-v24.22.1-linux-arm64.tar.xz"
    verify(node, NODE_SHA)
    node_dir = staging / "opt/node"
    node_dir.mkdir(parents=True)
    subprocess.run(["tar", "--no-same-owner", "-xJf", str(node), "--strip-components=1", "-C", str(node_dir)], check=True)
    for name, target in [("node", "/opt/node/bin/node"), ("npm", "/opt/node/lib/node_modules/npm/bin/npm-cli.js"), ("npx", "/opt/node/lib/node_modules/npm/bin/npx-cli.js"), ("opencode", "/opt/ousecode/opencode")]:
        link = staging / "usr/local/bin" / name
        link.parent.mkdir(parents=True, exist_ok=True)
        link.unlink(missing_ok=True)
        link.symlink_to(target)
    # ca-certificates' postinst is not executed during cross-architecture assembly.
    certs = sorted((staging / "usr/share/ca-certificates").rglob("*.crt"))
    cert_dir = staging / "etc/ssl/certs"
    cert_dir.mkdir(parents=True, exist_ok=True)
    (cert_dir / "ca-certificates.crt").write_bytes(b"\n".join(p.read_bytes() for p in certs))
    for directory in ["root", "proc", "sys", "dev", "tmp", "opt/ousecode", "var/lib/ousecode/empty", "var/lib/ousecode/shm", "etc/ousecode"]:
        (staging / directory).mkdir(parents=True, exist_ok=True)
    for path in [staging / "tmp", staging / "var/lib/ousecode/shm"]:
        path.chmod(0o1777)
    # Restrict pruning to nonessential documentation/cache, retaining all package copyright files.
    for rel in ["usr/share/man", "usr/share/info", "opt/node/share/doc", "opt/node/share/man", "var/cache/apt/archives"]:
        path = staging / rel
        if path.is_dir() and not path.is_symlink():
            shutil.rmtree(path)
    (staging / "etc/resolv.conf").unlink(missing_ok=True)
    (staging / "etc/resolv.conf").write_text("nameserver 223.5.5.5\nnameserver 1.1.1.1\n")
    (staging / "etc/hosts").write_text("127.0.0.1 localhost\n::1 localhost\n")
    (staging / "etc/apt/sources.list.d/ubuntu.sources").write_text("Types: deb\nURIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports\nSuites: noble noble-updates noble-security\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n")
    (staging / "etc/ousecode/runtime-version").write_text("ubuntu-24.04.5-node24.22.1-r1\n")
    print("Packing Ubuntu + Git + Node…", flush=True)
    root_archive = assets / "ubuntu-arm64.tar.xz"
    tar_env = dict(os.environ, XZ_OPT="-T4 -4")
    subprocess.run(["tar", "--sort=name", "--mtime=2026-01-01", "--owner=0", "--group=0", "-cJf", str(root_archive), "-C", str(staging), "."], env=tar_env, check=True)
    # AAPT transparently gunzips assets ending in .gz and removes that suffix.
    # .tgz preserves the verified compressed bytes and their asset name.
    old_engine = assets / "opencode-linux-arm64.tar.gz"
    old_engine.unlink(missing_ok=True)
    shutil.copyfile(args.engine_archive, assets / "opencode-arm64.tgz")
    libs_source = args.dsh / "runtime/out/arm64-v8a"
    dsh_manifest = json.loads((libs_source / "assets/runtime/manifest.json").read_text())
    native = repo / "app/src/main/jniLibs/arm64-v8a"
    native.mkdir(parents=True, exist_ok=True)
    libraries = {}
    for name in ["libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"]:
        src = libs_source / "jniLibs/arm64-v8a" / name
        verify(src, dsh_manifest["jniLibs"][name]["sha256"])
        shutil.copyfile(src, native / name)
        libraries[name] = dict(sha256=digest(src), source=dsh_manifest["jniLibs"][name]["source"])
    notices = repo / "app/src/main/assets/licenses"
    notices.mkdir(parents=True, exist_ok=True)
    for path in (libs_source / "assets/runtime/licenses").glob("*.txt"):
        shutil.copyfile(path, notices / path.name)
    manifest = dict(schema=1, runtimeVersion="ubuntu-24.04.5-node24.22.1-r1", ubuntuVersion="24.04.5", nodeVersion="24.22.1", opencodeVersion=args.engine_version,
                    ubuntu=dict(file=root_archive.name, sha256=digest(root_archive), bytes=root_archive.stat().st_size, source=BASE_URL, baseSha256=BASE_SHA),
                    engine=dict(file="opencode-arm64.tgz", sha256=digest(args.engine_archive), bytes=args.engine_archive.stat().st_size, source=ENGINE_URL), libraries=libraries)
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (repo / "runtime-inputs.json").write_text(json.dumps(manifest, indent=2) + "\n")
    shutil.copyfile(args.dsh / "runtime/locks/ubuntu-noble-arm64.json", repo / "ubuntu-packages.lock.json")
    print(json.dumps({"ubuntuMiB": round(root_archive.stat().st_size / 1048576, 1), "engineMiB": round(args.engine_archive.stat().st_size / 1048576, 1), "version": args.engine_version}), flush=True)


if __name__ == "__main__":
    main()

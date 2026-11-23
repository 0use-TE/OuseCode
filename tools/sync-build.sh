#!/usr/bin/env bash
set -euo pipefail
source_dir=/mnt/d/Projects/OuseCode
build_dir=/home/ouse/projects/OuseCode
mkdir -p "$build_dir"
rsync -rt --exclude=.cache --exclude=.gradle --exclude=build --exclude=app/build --exclude=app/src/main/assets/runtime --exclude=app/src/main/jniLibs --exclude=local.properties --exclude=dist "$source_dir/" "$build_dir/"
cd "$build_dir"
bash tools/build.sh
mkdir -p "$source_dir/dist"
cp dist/OpenCode-0.2.9-arm64.apk "$source_dir/dist/"
cp runtime-inputs.json ubuntu-packages.lock.json "$source_dir/"

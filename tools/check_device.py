#!/usr/bin/env python3
"""Check the installed release without dumping user content or credentials."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("ANDROID_SERIAL")
def adb(*args):
    device = ["-s", SERIAL] if SERIAL else []
    return subprocess.run(["adb", *device, *args], check=True, capture_output=True, text=True).stdout

root = Path(__file__).resolve().parents[1]
apk = root / "dist/OpenCode-0.2.5-arm64.apk"
local = hashlib.sha256(apk.read_bytes()).hexdigest()
remote = adb("shell", "sha256sum", "/sdcard/Download/OpenCode-0.2.5-arm64.apk").split()[0]
assert local == remote, "Tablet download differs from the release artifact"
package = adb("shell", "dumpsys", "package", "io.github.ouse.ousecode")
assert "versionName=0.2.5" in package
adb("shell", "uiautomator", "dump", "/data/local/tmp/ousecode-window.xml")
tree = ET.fromstring(adb("shell", "cat", "/data/local/tmp/ousecode-window.xml"))
app_text = " ".join((node.get("text", "") + " " + node.get("content-desc", "")) for node in tree.iter("node") if node.get("package") == "io.github.ouse.ousecode")
web_nodes = [node for node in tree.iter("node") if node.get("package") == "io.github.ouse.ousecode" and node.get("class") == "android.webkit.WebView"]
web_text = " ".join(node.get("text", "") + " " + node.get("content-desc", "") for web in web_nodes for node in web.iter("node"))
print(json.dumps(dict(apkSha256=local, tabletDownloadMatches=True, releaseInstalled=True, ownWindowVisible="OpenCode" in app_text, webAccessibilityTextAvailable=bool(web_text.strip()), workspaceVisible="workspace" in web_text if web_text.strip() else None)))
adb("shell", "rm", "/data/local/tmp/ousecode-window.xml")

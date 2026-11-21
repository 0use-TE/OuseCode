"""Read-only smoke probe of the official binary's HTTP/auth behavior (no credentials printed)."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

binary = Path.home() / "projects/OuseCode/.cache/host/opencode"
with tempfile.TemporaryDirectory(prefix="ousecode-probe-") as home:
    password = secrets.token_urlsafe(32)
    env = dict(os.environ, HOME=home, XDG_CONFIG_HOME=home + "/config", XDG_DATA_HOME=home + "/data", OPENCODE_PASSWORD=password)
    with open(home + "/server.log", "w") as log:
        argv = [str(binary), "serve", "--hostname", "127.0.0.1", "--port", "39461"]
        if "--service-mode" in sys.argv:
            for key, value in [("hostname", "127.0.0.1"), ("port", "39461"), ("password", password)]:
                subprocess.run([str(binary), "service", "set", key, value], env=env, capture_output=True, check=True)
            argv += ["--service"]
        proc = subprocess.Popen(argv, env=env, stdout=log, stderr=log)
        try:
            for attempt in range(90):
                if proc.poll() is not None:
                    raise RuntimeError("server exited with " + str(proc.returncode))
                try:
                    urllib.request.urlopen("http://127.0.0.1:39461/", timeout=1)
                    break
                except urllib.error.HTTPError:
                    break
                except OSError:
                    time.sleep(1)
            for endpoint in ["/", "/api/info", "/api/global/health", "/global/health", "/api/provider", "/api/path"]:
                for authenticated in [False, True]:
                    headers = {"Authorization": "Basic " + base64.b64encode(("opencode:" + password).encode()).decode()} if authenticated else {}
                    try:
                        with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:39461" + endpoint, headers=headers), timeout=5) as res:
                            body = res.read()
                            kind = res.headers.get("Content-Type", "")
                            data = json.loads(body) if "json" in kind else None
                            print(json.dumps(dict(endpoint=endpoint, auth=authenticated, status=res.status, type=kind, bytes=len(body), keys=list(data)[:12] if isinstance(data, dict) else None)))
                    except urllib.error.HTTPError as exc:
                        print(json.dumps(dict(endpoint=endpoint, auth=authenticated, status=exc.code, challenge=exc.headers.get("WWW-Authenticate"))))
            for endpoint in ["/api/auth/pair", "/api/auth/pairing", "/api/openapi.json", "/api/doc", "/api/auth/login"]:
                req = urllib.request.Request("http://127.0.0.1:39461" + endpoint, data=b"{}", headers={"Content-Type": "application/json", "Authorization": "Basic " + base64.b64encode(("opencode:" + password).encode()).decode()})
                try:
                    with urllib.request.urlopen(req, timeout=5) as res:
                        body = res.read()
                        data = json.loads(body)
                        print(json.dumps(dict(endpoint=endpoint, status=res.status, keys=list(data)[:10], cookie=bool(res.headers.get("Set-Cookie")))))
                except urllib.error.HTTPError as exc:
                    print(json.dumps(dict(endpoint=endpoint, status=exc.code)))
            if "--service-mode" in sys.argv:
                for command in [["service", "status"], ["pair", "--url", "http://127.0.0.1:39461"]]:
                    probe = subprocess.run([str(binary)] + command, env=env, capture_output=True, text=True, timeout=20)
                    print(json.dumps(dict(command=command[0], exit=probe.returncode, usesExpectedPort="39461" in probe.stdout, pairingLink="/auth/connect/" in probe.stdout)))
        finally:
            proc.terminate()
            proc.wait(timeout=20)

#!/usr/bin/env python3
"""Pobiera najnowszy artefakt nuta-android-apk z GitHub Actions przez Chrome CDP.

Wymaga Chrome uruchomionego w trybie debugowania (skrót "Chrome CDP" na pulpicie):
  chrome.exe --remote-debugging-port=9222 --user-data-dir="C:\\ChromeCDPProfile"
z profilem zalogowanym do GitHuba (artefakty Actions wymagają zalogowania).

Użycie:
  python scripts/download-latest-apk.py            # pobierz do ~/Downloads i rozpakuj
  python scripts/download-latest-apk.py --install  # dodatkowo zainstaluj przez adb i uruchom
"""
import argparse
import asyncio
import json
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

try:
    import websockets
except ImportError:
    sys.exit("Brak pakietu 'websockets' — zainstaluj: pip install websockets")

CDP = "http://localhost:9222"
ACTIONS_URL = "https://github.com/jablonowskit/Nuta/actions?query=branch%3Amain"
ARTIFACT_NAME = "nuta-android-apk"
DOWNLOADS = Path.home() / "Downloads"
ADB = Path.home() / r"AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
PACKAGE = "app.nuta"


def cdp_new_tab():
    req = urllib.request.Request(f"{CDP}/json/new?about:blank", method="PUT")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())
    except OSError:
        sys.exit("Chrome CDP nie odpowiada na porcie 9222 — uruchom skrót 'Chrome CDP' z pulpitu.")


async def download() -> Path:
    tab = cdp_new_tab()
    async with websockets.connect(tab["webSocketDebuggerUrl"], max_size=None) as ws:
        msg_id = 0

        async def send(method, params=None):
            nonlocal msg_id
            msg_id += 1
            this_id = msg_id
            await ws.send(json.dumps({"id": this_id, "method": method, "params": params or {}}))
            while True:
                msg = json.loads(await ws.recv())
                if msg.get("id") == this_id:
                    return msg.get("result", {})

        async def evaluate(expr):
            r = await send("Runtime.evaluate", {"expression": expr, "returnByValue": True, "awaitPromise": True})
            return r.get("result", {}).get("value")

        await send("Page.enable")
        await send("Runtime.enable")
        await send("Page.setDownloadBehavior", {"behavior": "allow", "downloadPath": str(DOWNLOADS)})

        # najnowszy run na main
        await send("Page.navigate", {"url": ACTIONS_URL})
        await asyncio.sleep(5)
        run_url = await evaluate("""
            (() => {
              const link = document.querySelector('.Box-row a[href*="/actions/runs/"]');
              return link ? link.href.split('?')[0] : null;
            })()
        """)
        if not run_url:
            sys.exit("Nie znaleziono żadnego runa na stronie Actions.")
        print("run:", run_url)

        # czekaj aż artefakt APK będzie dostępny (job androida mógł jeszcze nie skończyć)
        artifact_url = None
        for attempt in range(40):
            await send("Page.navigate", {"url": run_url})
            await asyncio.sleep(6)
            artifact_url = await evaluate("""
                (() => {
                  const links = document.querySelectorAll('a[href*="/artifacts/"]');
                  for (const a of links) if (a.textContent.trim() === '%NAME%') return a.href;
                  return null;
                })()
            """.replace("%NAME%", ARTIFACT_NAME))
            print(f"artefakt (próba {attempt + 1}):", artifact_url or "jeszcze niedostępny")
            if artifact_url:
                break
            await asyncio.sleep(15)
        if not artifact_url:
            sys.exit("Artefakt nie pojawił się w wyznaczonym czasie.")

        target = DOWNLOADS / f"{ARTIFACT_NAME}.zip"
        target.unlink(missing_ok=True)
        await send("Page.navigate", {"url": artifact_url})
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
            except asyncio.TimeoutError:
                continue
            if msg.get("method") == "Page.downloadProgress":
                p = msg["params"]
                if p.get("state") == "completed":
                    print("Pobrano:", target)
                    return target
                if p.get("state") == "canceled":
                    sys.exit("Pobieranie anulowane.")
        sys.exit("Przekroczono czas oczekiwania na pobranie.")


def extract(zip_path: Path) -> Path:
    out_dir = DOWNLOADS / "nuta-apk-latest"
    shutil.rmtree(out_dir, ignore_errors=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(out_dir)
    apk = next(out_dir.rglob("*.apk"), None)
    if not apk:
        sys.exit("W archiwum nie było pliku APK.")
    print("APK:", apk)
    return apk


def install(apk: Path) -> None:
    if not ADB.exists():
        sys.exit(f"Nie znaleziono adb: {ADB}")
    subprocess.run([str(ADB), "install", "-r", str(apk)], check=True)
    subprocess.run([str(ADB), "shell", "am", "force-stop", PACKAGE], check=True)
    subprocess.run([str(ADB), "shell", "monkey", "-p", PACKAGE, "1"], check=True)
    print("Zainstalowano i uruchomiono", PACKAGE)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true", help="po pobraniu zainstaluj przez adb i uruchom aplikację")
    args = parser.parse_args()
    zip_path = asyncio.run(download())
    apk = extract(zip_path)
    if args.install:
        install(apk)


if __name__ == "__main__":
    main()

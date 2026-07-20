#!/usr/bin/env python3
"""Instaluje APK Nuty na podłączonym telefonie przez adb i restartuje aplikację.

Użycie:
  python scripts/install-apk.py                # najnowszy APK: ~/Downloads/nuta-apk-latest/ lub świeżo pobrany zip
  python scripts/install-apk.py sciezka.apk    # konkretny plik APK
"""
import subprocess
import sys
import shutil
import zipfile
from pathlib import Path

DOWNLOADS = Path.home() / "Downloads"
ADB = Path.home() / r"AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
PACKAGE = "app.nuta"


def find_latest_apk() -> Path:
    # 1) rozpakowany katalog ze skryptu download-latest-apk.py
    extracted = DOWNLOADS / "nuta-apk-latest"
    apk = next(extracted.rglob("*.apk"), None) if extracted.exists() else None

    # 2) świeższy zip z artefaktem? rozpakuj go
    zips = sorted(DOWNLOADS.glob("nuta-android-apk*.zip"), key=lambda p: p.stat().st_mtime, reverse=True)
    if zips and (apk is None or zips[0].stat().st_mtime > apk.stat().st_mtime):
        shutil.rmtree(extracted, ignore_errors=True)
        with zipfile.ZipFile(zips[0]) as z:
            z.extractall(extracted)
        apk = next(extracted.rglob("*.apk"), None)

    if apk is None:
        sys.exit("Nie znaleziono APK — najpierw uruchom scripts/download-latest-apk.py")
    return apk


def main() -> None:
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else find_latest_apk()
    if not apk.exists():
        sys.exit(f"Plik nie istnieje: {apk}")
    if not ADB.exists():
        sys.exit(f"Nie znaleziono adb: {ADB}")

    devices = subprocess.run([str(ADB), "devices"], capture_output=True, text=True).stdout
    lines = [l for l in devices.splitlines()[1:] if l.strip()]
    if not any(l.endswith("device") for l in lines):
        if any(l.endswith("unauthorized") for l in lines):
            sys.exit("Telefon niezautoryzowany — odblokuj ekran i zatwierdź dialog debugowania USB.")
        sys.exit("Brak podłączonego telefonu (adb devices pusty).")

    print("Instaluję:", apk)
    subprocess.run([str(ADB), "install", "-r", str(apk)], check=True)
    subprocess.run([str(ADB), "shell", "am", "force-stop", PACKAGE], check=True)
    subprocess.run([str(ADB), "shell", "monkey", "-p", PACKAGE, "1"], check=True)
    pid = subprocess.run([str(ADB), "shell", "pidof", PACKAGE], capture_output=True, text=True).stdout.strip()
    print(f"Gotowe — {PACKAGE} działa (pid {pid})." if pid else f"Uwaga: {PACKAGE} nie wystartował — sprawdź logcat.")


if __name__ == "__main__":
    main()

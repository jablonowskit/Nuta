#!/usr/bin/env python3
"""Wdraża APK Nuty na telefon/emulator przez adb (odpowiednik deploy-android.ps1).

Instaluje z zachowaniem danych aplikacji (bez odinstalowania — chroni sesję Spotify).

Użycie:
  python scripts/deploy-android.py                         # domyślny APK: artifacts/android/Nuta-debug.apk
  python scripts/deploy-android.py --apk sciezka.apk
  python scripts/deploy-android.py --device emulator-5554  # gdy podłączono kilka urządzeń
  python scripts/deploy-android.py --build                 # najpierw zbuduj przez scripts/build-android.ps1
  python scripts/deploy-android.py --no-launch             # bez restartu aplikacji po instalacji
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_APK = PROJECT_ROOT / "artifacts" / "android" / "Nuta-debug.apk"
PACKAGE = "app.nuta"
ACTIVITY = f"{PACKAGE}/{PACKAGE}.android.MainActivity"


def find_adb() -> str:
    on_path = shutil.which("adb")
    if on_path:
        return on_path
    local_app_data = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    candidates = [
        local_app_data / "Android" / "Sdk" / "platform-tools" / "adb.exe",
        local_app_data / "Microsoft" / "WinGet" / "Packages"
        / "Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe" / "platform-tools" / "adb.exe",
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    sys.exit("Nie znaleziono adb. Sprawdź instalację Android SDK lub platform-tools (winget install Google.PlatformTools).")


def adb_run(adb: str, device: str | None, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    command = [adb] + (["-s", device] if device else []) + list(args)
    return subprocess.run(command, check=check)


def ensure_device(adb: str, device: str | None) -> None:
    output = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True).stdout
    online = [line.split()[0] for line in output.splitlines()[1:] if line.strip().endswith("device")]
    unauthorized = [line for line in output.splitlines()[1:] if line.strip().endswith("unauthorized")]
    if not online:
        if unauthorized:
            sys.exit("Telefon niezautoryzowany — odblokuj ekran i zatwierdź dialog debugowania USB.")
        sys.exit("Brak uruchomionego emulatora lub telefonu widocznego przez adb.")
    if device and device not in online:
        sys.exit(f"Urządzenie {device} nie jest online. Dostępne: {', '.join(online)}")
    if not device and len(online) > 1:
        sys.exit(f"Wykryto kilka urządzeń: {', '.join(online)}. Podaj --device.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK, help="ścieżka do APK (domyślnie artifacts/android/Nuta-debug.apk)")
    parser.add_argument("--device", help="numer seryjny urządzenia, np. emulator-5554")
    parser.add_argument("--build", action="store_true", help="najpierw zbuduj APK przez scripts/build-android.ps1")
    parser.add_argument("--no-launch", action="store_true", help="nie restartuj aplikacji po instalacji")
    args = parser.parse_args()

    if args.build:
        build_script = PROJECT_ROOT / "scripts" / "build-android.ps1"
        subprocess.run(
            ["powershell", "-ExecutionPolicy", "Bypass", "-File", str(build_script), "-OutputPath", str(args.apk)],
            check=True,
        )

    if not args.apk.is_file():
        sys.exit(f"Brak APK: {args.apk}. Uruchom scripts/build-android.ps1 albo dodaj --build.")

    adb = find_adb()
    ensure_device(adb, args.device)

    print("Instalowanie APK z zachowaniem danych aplikacji...")
    result = adb_run(adb, args.device, "install", "-r", str(args.apk), check=False)
    if result.returncode != 0:
        sys.exit("Instalacja nie powiodła się. Skrypt celowo nie odinstalowuje aplikacji, aby nie utracić sesji Spotify.")

    if not args.no_launch:
        adb_run(adb, args.device, "shell", "am", "force-stop", PACKAGE)
        launch = adb_run(adb, args.device, "shell", "am", "start", "-n", ACTIVITY, check=False)
        if launch.returncode != 0:
            sys.exit("APK zainstalowano, ale nie udało się uruchomić aplikacji.")

    print("Nuta została wdrożona na urządzenie.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Pobiera najnowszy artefakt nuta-android-apk z GitHub Actions przez gh CLI.

Wymaga zalogowanego GitHub CLI (gh auth login) — patrz `gh auth status`.

Użycie:
  python scripts/download-latest-apk.py            # pobierz do ~/Downloads i rozpakuj
  python scripts/download-latest-apk.py --install  # dodatkowo wdroż przez scripts/deploy-android.py
"""
import argparse
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

REPO = "jablonowskit/Nuta"
ARTIFACT_NAME = "nuta-android-apk"
DOWNLOADS = Path.home() / "Downloads"
DEPLOY_SCRIPT = Path(__file__).resolve().parent / "deploy-android.py"


def find_gh() -> str:
    on_path = shutil.which("gh")
    if on_path:
        return on_path
    candidate = Path(r"C:\Program Files\GitHub CLI\gh.exe")
    if candidate.exists():
        return str(candidate)
    sys.exit("Nie znaleziono gh CLI. Zainstaluj: winget install GitHub.cli")


def gh(gh_bin: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run([gh_bin, *args], capture_output=True, text=True, check=check)


def latest_run_id(gh_bin: str) -> str:
    result = gh(gh_bin, "run", "list", "--repo", REPO, "--branch", "main",
                "--workflow", "linux-gui.yml", "--limit", "1", "--json", "databaseId")
    import json
    runs = json.loads(result.stdout)
    if not runs:
        sys.exit("Nie znaleziono żadnego runa na main.")
    run_id = str(runs[0]["databaseId"])
    print("run:", f"https://github.com/{REPO}/actions/runs/{run_id}")
    return run_id


def wait_for_run(gh_bin: str, run_id: str) -> None:
    for attempt in range(60):
        result = gh(gh_bin, "run", "view", run_id, "--repo", REPO, "--json", "status,conclusion")
        import json
        info = json.loads(result.stdout)
        status = info.get("status")
        conclusion = info.get("conclusion")
        print(f"status (próba {attempt + 1}): {status} ({conclusion or '...'})")
        if status == "completed":
            if conclusion != "success":
                sys.exit(f"Run zakończony z wynikiem: {conclusion}")
            return
        time.sleep(15)
    sys.exit("Przekroczono czas oczekiwania na zakończenie runa.")


def download(run_id: str) -> Path:
    gh_bin = find_gh()
    wait_for_run(gh_bin, run_id)
    out_dir = DOWNLOADS / "nuta-apk-latest"
    shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    gh(gh_bin, "run", "download", run_id, "--repo", REPO, "--name", ARTIFACT_NAME, "--dir", str(out_dir))
    apk = next(out_dir.rglob("*.apk"), None)
    if not apk:
        sys.exit("W artefakcie nie było pliku APK.")
    print("APK:", apk)
    return apk


def install(apk: Path) -> None:
    subprocess.run([sys.executable, str(DEPLOY_SCRIPT), "--apk", str(apk)], check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--install", action="store_true", help="po pobraniu wdroż przez scripts/deploy-android.py")
    parser.add_argument("--run-id", help="konkretny run id zamiast najnowszego na main")
    args = parser.parse_args()
    gh_bin = find_gh()
    run_id = args.run_id or latest_run_id(gh_bin)
    apk = download(run_id)
    if args.install:
        install(apk)


if __name__ == "__main__":
    main()

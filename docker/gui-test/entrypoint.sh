#!/usr/bin/env bash
set -euo pipefail

mkdir -p /artifacts/logs /artifacts/screenshots

# Chromium lock files contain the previous container hostname/PID and must not survive
# container replacement. They are not session data; cookies and the profile remain intact.
case "${NUTA_WEBVIEW_DIR}" in
  /home/nuta/.local/share/nuta/*)
    rm -f -- \
      "${NUTA_WEBVIEW_DIR}/cache/SingletonLock" \
      "${NUTA_WEBVIEW_DIR}/cache/SingletonCookie" \
      "${NUTA_WEBVIEW_DIR}/cache/SingletonSocket"
    ;;
  *)
    echo "Refusing to clean Chromium locks outside the expected session directory" >&2
    exit 1
    ;;
esac

cleanup() {
  jobs -p | xargs -r kill 2>/dev/null || true
}
trap cleanup EXIT INT TERM

Xvfb "${DISPLAY}" -screen 0 "${NUTA_SCREEN_SIZE}" -nolisten tcp -ac > /artifacts/logs/xvfb.log 2>&1 &
sleep 1
openbox-session > /artifacts/logs/openbox.log 2>&1 &
x11vnc -display "${DISPLAY}" -forever -shared -nopw -rfbport 5900 -localhost > /artifacts/logs/x11vnc.log 2>&1 &
websockify --web=/usr/share/novnc 6080 localhost:5900 > /artifacts/logs/novnc.log 2>&1 &
python3 /opt/nuta/screenshot_server.py > /artifacts/logs/screenshot-server.log 2>&1 &

sleep 2
exec /opt/nuta/bin/composeApp

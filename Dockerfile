FROM gradle:9.6.1-jdk25 AS builder

WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 chmod +x gradlew \
    && ./gradlew --no-daemon :composeApp:installDesktopDist :composeApp:allTests

FROM eclipse-temurin:25-jre-noble AS gui-test

ENV DEBIAN_FRONTEND=noninteractive \
    DISPLAY=:99 \
    NUTA_LOG_LEVEL=DEBUG \
    NUTA_LOG_DIR=/artifacts/logs \
    NUTA_MPV_AUDIO_OUTPUT=null \
    NUTA_WEBVIEW_DIR=/home/nuta/.local/share/nuta/spotify-webview \
    NUTA_WEBVIEW_RUNTIME_DIR=/tmp/nuta-kcef-runtime \
    NUTA_SESSION_DIR=/home/nuta/.local/share/nuta/spotify-session \
    JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED" \
    NUTA_SCREEN_SIZE=1440x900x24

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        xvfb openbox x11vnc novnc websockify scrot xdotool python3 mpv \
        fonts-dejavu-core fonts-noto-core \
        libx11-6 libxext6 libxrender1 libxtst6 libxi6 \
        libfreetype6 libfontconfig1 libgl1 libgtk-3-0 libasound2t64 \
        libnss3 libnspr4 libxss1 libgbm1 libxkbcommon0 libatk1.0-0 \
        libatk-bridge2.0-0 libcups2 libdrm2 libpangocairo-1.0-0 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 nuta \
    && mkdir -p /opt/nuta /artifacts/logs /artifacts/screenshots /home/nuta/.local/share/nuta \
    && chown -R nuta:nuta /opt/nuta /artifacts /home/nuta/.local

COPY --from=builder /workspace/composeApp/build/install/composeApp-desktop /opt/nuta
COPY --chown=nuta:nuta docker/gui-test/entrypoint.sh /opt/nuta/entrypoint.sh
COPY --chown=nuta:nuta docker/gui-test/screenshot_server.py /opt/nuta/screenshot_server.py

RUN chmod +x /opt/nuta/entrypoint.sh /opt/nuta/bin/composeApp

USER nuta
WORKDIR /home/nuta

EXPOSE 6080 6081
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:6081/health', timeout=2)" || exit 1

ENTRYPOINT ["/opt/nuta/entrypoint.sh"]

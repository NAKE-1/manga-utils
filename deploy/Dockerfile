# manga-utils :server — container image.
# Multi-stage: build the app (JVM + bundled web UI) with Gradle, then ship a slim JRE runtime
# that carries the shared libraries JCEF/Chromium needs (so WebView-based sources work headless).
# Build context is the REPO ROOT (so gradlew + all modules are visible):
#   docker build -f deploy/Dockerfile -t manga-utils .

# ---- Stage 1: build --------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build

# Node is needed because :server's processResources runs the web UI's `npm run build`.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .
# installDist builds :core/:source-api/:android-compat, bundles the built web UI into resources,
# and produces a runnable dist at server/build/install/server (bin/server launcher + lib/*.jar).
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon -x test

# ---- Stage 2: runtime ------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# JCEF spawns real Chromium subprocesses even in windowless mode — these are its shared-lib deps.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libgbm1 libgtk-3-0 \
      libasound2 libxcomposite1 libxdamage1 libxext6 libxfixes3 libxrandr2 libxkbcommon0 \
      libpango-1.0-0 libcairo2 libx11-6 libxcb1 libxi6 libxtst6 libglib2.0-0 \
      fonts-liberation fontconfig ca-certificates wget \
 && rm -rf /var/lib/apt/lists/*

# Run as a non-root user; /data is the single persisted volume (library, extensions, settings,
# downloads, and the one-time-downloaded JCEF native under /data/bin/jcef).
RUN useradd -m -u 1000 app
COPY --from=build /src/server/build/install/server /app
RUN mkdir -p /data && chown -R app:app /data /app

ENV MU_DATA_DIR=/data \
    MANGA_WEB_PORT=8080

USER app
WORKDIR /app
VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- "http://localhost:8080/api/version" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["/app/bin/server"]

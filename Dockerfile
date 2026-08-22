# syntax=docker/dockerfile:1.7

# ── Stage 1: build with Maven ──────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Cache dependencies (faster rebuilds when only source changes)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ── Stage 2: runtime ───────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

# Calibre (ebook-convert) + ImageMagick. ImageMagick 6 ships `convert`;
# the backend calls `magick`, so we expose a compat symlink.
# librsvg2-bin: high-fidelity SVG delegate for ImageMagick (its built-in
# MSVG renderer is low quality); used for SVG → PNG/JPG conversion.
# jpegoptim/pngquant/gifsicle/webp(cwebp): dedicated lossy optimizers for
# image compression — meaningfully smaller output than ImageMagick's
# -quality/-strip alone, at the same visible quality.
# libreoffice-calc/-impress: Excel/PowerPoint headless conversion.
# tesseract-ocr(+rus): PDF OCR text extraction. poppler-utils: pdftoppm/pdftotext
# for OCR page rasterization and layout-preserving text extraction.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      calibre \
      imagemagick \
      librsvg2-bin \
      jpegoptim \
      pngquant \
      gifsicle \
      webp \
      python3 \
      python3-pip \
      ffmpeg \
      libwebp-dev \
      libheif1 \
      ghostscript \
      libreoffice-writer \
      libreoffice-draw \
      libreoffice-calc \
      libreoffice-impress \
      libreoffice-core \
      tesseract-ocr \
      tesseract-ocr-rus \
      poppler-utils \
      fonts-liberation \
      fonts-dejavu \
      ca-certificates \
      tzdata \
 && ln -sf /usr/bin/convert /usr/local/bin/magick \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Ubuntu ships ImageMagick with "rights=none" rules (CVE-2016-3714 mitigation)
# that block coders/filters/delegates. Remove the catch-all patterns AND the
# targeted PDF/PS/EPS/XPS bans so the image→PDF tool can write PDF. Inputs are
# validated and size-capped; modern IM+Ghostscript patch the original CVE.
# Robust: also matches the grouped form pattern="{PS,PS2,PS3,EPS,PDF,XPS}".
RUN sed -i -E \
      -e '/<policy domain="(coder|filter|delegate)" rights="none" pattern="\*"/d' \
      -e '/<policy domain="coder" rights="none" pattern="[^"]*(PDF|PS|EPS|XPS)[^"]*"/d' \
      /etc/ImageMagick-6/policy.xml

# rembg: AI background removal (U^2-Net-portable ONNX model, CPU inference
# via onnxruntime), served by docker/rembg_server.py — a small FastAPI
# wrapper run as a long-lived sidecar (see ENTRYPOINT) so the model stays
# loaded in memory across requests, instead of a fresh CLI process per
# request reloading the model and re-initializing onnxruntime every time
# (what made the original implementation too slow). Deliberately NOT using
# rembg's own `rembg s` CLI server: it pulls in the "cli" extra's full
# dependency tree (gradio, watchdog, aiohttp, ...) since rembg's CLI eagerly
# imports every subcommand — memory-heavy enough to fail to start on the
# production VPS. Importing the library directly avoids all of that. Uses
# the "u2netp" model (see rembg_server.py) rather than the default "u2net" —
# the production host is a ~1.9GB shared VPS, and u2net's own footprint was
# still enough to get OOM-killed even after capping input resolution. The
# model is pre-downloaded into U2NET_HOME at build time so the image is
# fully self-contained and startup doesn't pay download latency; the
# directory is left world-readable since it's populated as root, before the
# non-root "app" user below is switched to.
RUN pip3 install --no-cache-dir rembg onnxruntime fastapi uvicorn python-multipart
COPY docker/rembg_server.py /opt/rembg-server/rembg_server.py
ENV U2NET_HOME=/opt/rembg-models
RUN mkdir -p /opt/rembg-models \
 && python3 -c "from rembg import new_session; new_session('u2netp')" \
 && chmod -R a+rX /opt/rembg-models

# Non-root runtime user
RUN useradd -u 1000 -m -s /bin/bash app
WORKDIR /app

COPY --from=builder /build/target/iconverter-*-SNAPSHOT.jar /app/app.jar

# Working dirs: tmp for conversions, logs for Spring file appender,
# data for the SQLite database (error log / contact messages)
RUN mkdir -p /app/logs /app/data /tmp/iconverter \
 && chown -R app:app /app /tmp/iconverter

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
# rembg server binds loopback-only (127.0.0.1) — never exposed outside the
# container, only ImagesConversionService talks to it. Backgrounded and
# unsupervised (no process manager); if it dies, background removal starts
# failing until the container restarts, but every other endpoint is unaffected.
ENTRYPOINT ["sh","-c","cd /opt/rembg-server && python3 -m uvicorn rembg_server:app --host 127.0.0.1 --port 5000 & exec java $JAVA_OPTS -jar /app/app.jar"]

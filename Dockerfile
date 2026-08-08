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

# rembg: AI background removal (U^2-Net ONNX model, CPU inference via
# onnxruntime). The model is pre-downloaded into U2NET_HOME at build time so
# the image is fully self-contained and the first request doesn't pay
# download latency; the directory is left world-readable since it's
# populated as root, before the non-root "app" user below is switched to.
RUN pip3 install --no-cache-dir rembg onnxruntime
ENV U2NET_HOME=/opt/rembg-models
RUN mkdir -p /opt/rembg-models \
 && python3 -c "from rembg import new_session; new_session('u2net')" \
 && chmod -R a+rX /opt/rembg-models

# Non-root runtime user
RUN useradd -u 1000 -m -s /bin/bash app
WORKDIR /app

COPY --from=builder /build/target/iconverter-*-SNAPSHOT.jar /app/app.jar

# Working dirs: tmp for conversions, logs for Spring file appender
RUN mkdir -p /app/logs /tmp/iconverter \
 && chown -R app:app /app /tmp/iconverter

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]

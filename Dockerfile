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
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      calibre \
      imagemagick \
      libwebp-dev \
      libheif1 \
      ghostscript \
      libreoffice-writer \
      libreoffice-draw \
      libreoffice-core \
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
RUN sed -i -E \
      -e '/<policy domain="(coder|filter|delegate)" rights="none" pattern="\*"/d' \
      -e '/<policy domain="coder" rights="none" pattern="(PDF|PS|PS2|PS3|EPS|XPS|PDFA)"/d' \
      /etc/ImageMagick-6/policy.xml

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

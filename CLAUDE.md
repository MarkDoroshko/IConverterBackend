## Backend (`IConverterBackend/`)

### Commands
```bash
mvn spring-boot:run                       # run locally
mvn clean package && java -jar target/iconverter-*.jar
mvn test                                  # unit tests only — no CLI tools required
docker compose up --build                 # local build with all CLI deps baked in
```
Run a single test: `mvn test -Dtest=PdfConversionServiceTest`.

Running conversions locally (outside Docker) requires ImageMagick, Ghostscript, LibreOffice (incl. Calc/Impress), Calibre, ffmpeg, Tesseract OCR, and poppler-utils installed and on `PATH`.

### Architecture
Each conversion domain follows the same three-layer pattern: `controller/*ConversionController.java` → `services/conversions/I*ConversionService.java` interface → `*ConversionService.java` implementation, which shells out to an external CLI tool via `ProcessBuilder` (temp file in → process → result → cleanup):

| Domain | Endpoint prefix | Backing tool |
|---|---|---|
| Images | `/api/convert/images/` (+ `resize`, `crop`) | ImageMagick (`magick`) |
| PDF | `/api/convert/pdf/` (`compress`, `merge`, `from-image`, `to-jpg`, `ocr`) | Ghostscript + ImageMagick; `ocr` uses poppler (`pdftoppm`) + Tesseract (rus+eng), merging multi-page searchable-PDF output via Ghostscript |
| Office | `/api/convert/office/` | LibreOffice (`soffice`); PDF input routes through Calibre |
| Excel | `/api/convert/excel/` | LibreOffice Calc (`soffice`); xlsx/xls/csv ⇄, xlsx/csv→pdf. PDF as source routes to a separate best-effort path: `pdftotext -layout` + Apache POI (whitespace-column heuristic, not real table detection) |
| PowerPoint | `/api/convert/powerpoint/` (`to-pdf`, `to-image`, `from-image`, `from-pdf`) | LibreOffice Impress (`soffice`) + Ghostscript for `to-image`; Apache POI for `from-image`/`from-pdf` (image(s)→PPTX, no CLI tool for this direction — `from-pdf` rasterizes each page via Ghostscript, then composes one slide per page, image-only/not editable text) |
| Ebook | `/api/convert/ebook/` | Calibre (`ebook-convert`) |
| Audio | `/api/convert/audio/` (+ `trim`) | ffmpeg |
| Video | `/api/convert/video/` (+ `resize`, `trim`, `gif`) | ffmpeg |

Other notable pieces:
- `ratelimit/FixedWindowRateLimiter.java` + `RateLimitFilter.java` — per-IP rate limiting applied to all of `/api/**` (`app.rate-limit.per-minute`, default 60/min); `/api/devtools/**` has its own tighter budget (`app.rate-limit.devtools-per-minute`, default 10/min). Keys come from `utils/RequestUtils.clientIp()`, which trusts forwarding headers only from a loopback/private proxy.
- `ratelimit/ConversionQuotaFilter.java` — longer-horizon per-IP conversion budgets (`app.conversion.hourly-per-ip`, default 100; `app.conversion.daily-per-ip`, default 400) so a slow client cannot use the service as free CPU for hours.
- `ratelimit/ConversionConcurrencyFilter.java` — process-wide semaphore on `/api/convert/**` (`app.conversion.max-concurrent`, default 3); extra requests get `503 + Retry-After` instead of queueing, so a burst can't OOM the container.
- `utils/HtmlSanitizer.java` — HTML uploads (including HTML disguised with another extension, detected via Tika) are rewritten before LibreOffice/Calibre: scripts, frames, external images/links and CSS URLs are removed. This closes the HTML→PDF SSRF vector; `data:image/*` payloads still work. LibreOffice also gets a per-call profile with macro execution off and external-link updates disabled.
- `controller/ApiErrorController.java` — replaces Boot's default `/error` document with `{"error":"..."}` (no timestamp/path/exception), matching `GlobalExceptionHandler`.
- `config/CorsConfig.java` — allow-list of origins allowed to call the API (only the https site origins; no cookie credentials). Update when the frontend's domain changes.
- `controller/GlobalExceptionHandler.java` — converts failures to JSON `{"error": "..."}`.
- Limits: 25 MB per file (audio/video: 50 MB), enforced before invoking the CLI tools. Video transcodes use a longer ffmpeg timeout (300s) than other domains (120s) since re-encoding is heavier.
- Trim endpoints (audio + video) use ffmpeg `-c copy` (stream copy, no re-encode) and accept start/end as seconds or `HH:MM:SS`. GIF export is two-pass (`palettegen` + `paletteuse`) and caps clip duration at 15s.
- Unit tests cover command builders, format validation, and the rate limiter — they deliberately avoid depending on the CLI tools being installed. `CalibreBookConversionIntegrationTest` is an integration test and does need Calibre present.

### Deploy
Push to `main` → GitHub Actions builds a multi-stage Docker image (bundling all CLI tools) → pushes to GHCR → SSHes to the server and restarts the container via `docker compose pull && up -d`. **No test gate** — `mvn package` runs with `-DskipTests` on this pipeline, so a broken commit on `main` still deploys. The production `docker-compose.yml` (pulling from GHCR) lives only on the server, not in this repo; the committed `docker-compose.yml` here is for local `build: .` use.

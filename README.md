# IConverter — Backend

REST API для файловых конвертеров сайта [iconverter.ru](https://iconverter.ru).
Spring Boot 3.4.2 / Java 17 / Maven.

Конвертация делегируется проверенному открытому ПО через `ProcessBuilder`
(временный файл → процесс → результат → очистка):

- **изображения** — ImageMagick (`magick`): конвертация форматов, сжатие, resize, crop;
- **PDF** — Ghostscript + ImageMagick: сжатие, объединение, изображение→PDF, PDF→JPG;
- **документы** — LibreOffice (`soffice`): Word↔PDF (PDF на входе уходит в Calibre);
- **книги** — Calibre (`ebook-convert`): 30+ форматов;
- **аудио/видео** — ffmpeg: извлечение/конвертация звука (MP4→MP3 и др.).

## Эндпоинты

Все под `/api/convert/...`, ответы об ошибках — JSON `{"error": "..."}`.

| Метод | Путь | Назначение | Лимит |
|---|---|---|---|
| POST | `/images/` | конвертация изображений (`targetFormat`, `quality`, `maxSize`) | 25 МБ |
| POST | `/images/resize` | изменение размера (`width`/`height`/`mode`) | 25 МБ |
| POST | `/images/crop` | обрезка (`width`/`height`/`gravity`) | 25 МБ |
| POST | `/pdf/compress` | сжатие PDF (`level`) | 25 МБ |
| POST | `/pdf/merge` | объединение PDF (`files[]`) | 25 МБ/файл |
| POST | `/pdf/from-image` | изображение → PDF | 25 МБ |
| POST | `/pdf/to-jpg` | PDF → ZIP со страницами JPG (`dpi`) | 25 МБ |
| POST | `/office/` | документы Word↔PDF (`targetFormat`) | 25 МБ |
| POST | `/ebook/` | конвертация книг (`targetFormat`) | 25 МБ |
| POST | `/audio/` | извлечение/конвертация аудио (`targetFormat`) | 50 МБ |

На все `/api/**` действует per-IP rate limit (по умолчанию 60 запросов/мин,
настройка `app.rate-limit.per-minute`).

## Запуск локально

```bash
mvn spring-boot:run
# или
mvn clean package && java -jar target/iconverter-*.jar
```

Для конвертаций нужны установленные CLI-утилиты (ImageMagick, Ghostscript,
LibreOffice, Calibre, ffmpeg). В Docker-образе они уже есть.

## Сборка и деплой

```bash
docker compose up --build      # локально
```

Прод: push в `main` → GitHub Actions собирает multi-stage Docker-образ
(со всеми утилитами), пушит в GHCR и по SSH перезапускает контейнер на сервере.

## Тесты

```bash
mvn test
```

Юнит-тесты покрывают «чистую» логику (билдеры команд, валидацию форматов,
rate-limiter) и не требуют установленных конвертеров.

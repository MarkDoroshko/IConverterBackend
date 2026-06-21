package ru.iconverter.services.conversions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class PdfConversionService implements IPdfConversionService {

    private static final Logger log = LoggerFactory.getLogger(PdfConversionService.class);

    // UI levels → Ghostscript -dPDFSETTINGS presets.
    static final Map<String, String> LEVEL_TO_PDFSETTINGS = Map.of(
            "screen", "/screen",   // 72 dpi  — smallest
            "ebook", "/ebook",     // 150 dpi — balanced (default)
            "printer", "/printer"  // 300 dpi — highest quality
    );

    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    @Override
    public Resource compress(MultipartFile file, String level) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String setting = resolveSetting(level);

        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("pdf-in-", ".pdf");
            outputFile = createTempFile("pdf-out-", ".pdf");
            file.transferTo(inputFile.toFile());

            List<String> command = buildCommand(inputFile.toString(), outputFile.toString(), setting);
            log.info("Compress PDF ({} bytes) level={} → {}", file.getSize(), level, setting);
            log.debug("Ghostscript command: {}", command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }

            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Сжатие PDF превысило лимит времени");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                log.error("Ghostscript failed (exit {}): {}", exit, output);
                throw new RuntimeException("Ошибка сжатия PDF (код " + exit + ")");
            }

            byte[] result = Files.readAllBytes(outputFile);
            log.info("PDF compressed: {} → {} bytes", file.getSize(), result.length);
            return new ByteArrayResource(result);

        } catch (IOException e) {
            log.error("PDF I/O error", e);
            throw new RuntimeException("Ошибка при обработке PDF: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Сжатие PDF прервано", e);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public Resource fromImage(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        // Temp files (not pipes): ImageMagick reads the image file, writes a PDF.
        Path input = null;
        Path output = null;
        try {
            String ext = getExtension(file.getOriginalFilename());
            input = createTempFile("img2pdf-in-", ext.isEmpty() ? ".bin" : "." + ext);
            output = createTempFile("img2pdf-out-", ".pdf");
            file.transferTo(input.toFile());

            List<String> command = List.of("magick", input.toString(), "pdf:" + output.toString());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Конвертация изображения в PDF превысила лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("magick image→pdf failed: {}", out);
                throw new RuntimeException("Не удалось преобразовать изображение в PDF: "
                        + out.toString().trim().replaceAll("\\s+", " "));
            }
            byte[] bytes = Files.readAllBytes(output);
            log.info("Image→PDF: {} → {} bytes", file.getSize(), bytes.length);
            return new ByteArrayResource(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке изображения: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Конвертация прервана", e);
        } finally {
            cleanupQuietly(input);
            cleanupQuietly(output);
        }
    }

    static String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    @Override
    public Resource toImages(MultipartFile file, Integer dpi) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        int resolution = resolveDpi(dpi);

        Path inputFile = null;
        Path pagesDir = null;
        try {
            inputFile = createTempFile("pdf2jpg-in-", ".pdf");
            file.transferTo(inputFile.toFile());
            pagesDir = Files.createTempDirectory(Paths.get(tempDir), "pdf2jpg-out-");
            String outPattern = pagesDir.resolve("page-%03d.jpg").toString();

            List<String> command = buildToImagesCommand(inputFile.toString(), outPattern, resolution);
            log.info("PDF→JPG ({} bytes) dpi={}", file.getSize(), resolution);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Рендеринг PDF превысил лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("Ghostscript pdf→jpg failed: {}", output);
                throw new RuntimeException("Не удалось преобразовать PDF в изображения");
            }

            byte[] zip = zipDirectory(pagesDir);
            log.info("PDF→JPG: zipped pages → {} bytes", zip.length);
            return new ByteArrayResource(zip);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке PDF: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Рендеринг прерван", e);
        } finally {
            cleanupQuietly(inputFile);
            deleteDirQuietly(pagesDir);
        }
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────

    static int resolveDpi(Integer dpi) {
        int d = dpi == null ? 150 : dpi;
        if (d < 72 || d > 600) {
            throw new IllegalArgumentException("dpi must be 72..600");
        }
        return d;
    }

    static List<String> buildToImagesCommand(String input, String outPattern, int dpi) {
        return List.of(
                "gs",
                "-sDEVICE=jpeg",
                "-r" + dpi,
                "-dJPEGQ=90",
                "-dNOPAUSE",
                "-dBATCH",
                "-dQUIET",
                "-o", outPattern,
                input
        );
    }

    static String resolveSetting(String level) {
        String key = level == null ? "ebook" : level.trim().toLowerCase();
        String setting = LEVEL_TO_PDFSETTINGS.get(key);
        if (setting == null) {
            throw new IllegalArgumentException("Unsupported level: " + level
                    + ". Supported: " + LEVEL_TO_PDFSETTINGS.keySet());
        }
        return setting;
    }

    static List<String> buildCommand(String input, String output, String pdfSetting) {
        return List.of(
                "gs",
                "-sDEVICE=pdfwrite",
                "-dCompatibilityLevel=1.4",
                "-dPDFSETTINGS=" + pdfSetting,
                "-dNOPAUSE",
                "-dQUIET",
                "-dBATCH",
                "-dDetectDuplicateImages=true",
                "-sOutputFile=" + output,
                input
        );
    }

    private Path createTempFile(String prefix, String suffix) throws IOException {
        Path dir = Paths.get(tempDir);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return Files.createTempFile(dir, prefix, suffix);
    }

    private void cleanupQuietly(Path path) {
        if (path != null && Files.exists(path)) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                log.warn("Не удалось удалить временный файл: {}", path, e);
            }
        }
    }

    // Zip every regular file in `dir` (flat, sorted by name) into a byte[].
    private byte[] zipDirectory(Path dir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos);
             Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            if (sorted.isEmpty()) {
                throw new IOException("PDF не содержит страниц для рендеринга");
            }
            for (Path p : sorted) {
                zos.putNextEntry(new ZipEntry(p.getFileName().toString()));
                zos.write(Files.readAllBytes(p));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private void deleteDirQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Не удалось удалить: {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("Не удалось обойти директорию: {}", dir, e);
        }
    }
}

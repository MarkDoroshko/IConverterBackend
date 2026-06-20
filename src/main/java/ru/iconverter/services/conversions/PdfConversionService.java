package ru.iconverter.services.conversions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    // ── Pure helpers (unit-tested) ──────────────────────────────────────

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
}

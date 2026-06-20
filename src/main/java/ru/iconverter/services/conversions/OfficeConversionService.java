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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class OfficeConversionService implements IOfficeConversionService {

    private static final Logger log = LoggerFactory.getLogger(OfficeConversionService.class);

    // Output formats LibreOffice can write for our use cases.
    public static final Set<String> SUPPORTED_TARGET_FORMATS =
            Set.of("pdf", "docx", "doc", "odt", "rtf", "txt");

    // Accepted input extensions (Word↔PDF and friends).
    public static final Set<String> SUPPORTED_SOURCE_FORMATS =
            Set.of("pdf", "docx", "doc", "odt", "rtf", "txt", "html", "htm");

    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    @Override
    public Resource convert(MultipartFile file, String targetFormat) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String target = normalize(targetFormat);
        String source = normalize(getExtension(file.getOriginalFilename()));
        validate(source, target);

        Path workDir = null;
        Path profileDir = null;
        try {
            workDir = Files.createTempDirectory(Paths.get(tempDir), "office-");
            profileDir = Files.createTempDirectory(Paths.get(tempDir), "lo-profile-");
            Path input = workDir.resolve("input." + source);
            file.transferTo(input.toFile());

            List<String> command = buildCommand(target, workDir.toString(), profileDir.toString(), input.toString());
            log.info("Office convert {} → {} ({} bytes)", source, target, file.getSize());
            log.debug("LibreOffice command: {}", command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Конвертация документа превысила лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("LibreOffice failed (exit {}): {}", process.exitValue(), output);
                throw new RuntimeException("Не удалось конвертировать документ");
            }

            Path produced = findOutput(workDir, target);
            byte[] bytes = Files.readAllBytes(produced);
            log.info("Office convert done: {} → {} bytes", source, bytes.length);
            return new ByteArrayResource(bytes);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке документа: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Конвертация прервана", e);
        } finally {
            deleteDirQuietly(workDir);
            deleteDirQuietly(profileDir);
        }
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────

    static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("^\\.", "");
    }

    static String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) return "";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    static void validate(String source, String target) {
        if (!SUPPORTED_SOURCE_FORMATS.contains(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source
                    + ". Supported: " + SUPPORTED_SOURCE_FORMATS);
        }
        if (!SUPPORTED_TARGET_FORMATS.contains(target)) {
            throw new IllegalArgumentException("Unsupported target format: " + target
                    + ". Supported: " + SUPPORTED_TARGET_FORMATS);
        }
        if (source.equals(target)) {
            throw new IllegalArgumentException("Source and target formats are the same: " + target);
        }
    }

    // A unique per-call user profile (-env:UserInstallation) avoids the shared
    // profile lock that breaks concurrent headless soffice invocations.
    static List<String> buildCommand(String target, String outDir, String profileDir, String input) {
        return List.of(
                "soffice",
                "--headless",
                "--norestore",
                "--nolockcheck",
                "-env:UserInstallation=file://" + profileDir,
                "--convert-to", target,
                "--outdir", outDir,
                input
        );
    }

    // LibreOffice names the output after the input base name with the new ext.
    private Path findOutput(Path workDir, String target) throws IOException {
        try (Stream<Path> files = Files.list(workDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith("." + target))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IOException("LibreOffice не создал выходной файл ." + target));
        }
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

package ru.iconverter.services.conversions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class ImagesConversionService implements IImagesConversionService {

    private static final Logger logger = LoggerFactory.getLogger(ImagesConversionService.class);

    // Raster output formats we allow. Input format is auto-detected by
    // ImageMagick from the file content, so HEIC/HEIF and others work.
    public static final Set<String> SUPPORTED_TARGET_FORMATS =
            Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff");

    private static final int MIN_QUALITY = 1;
    private static final int MAX_QUALITY = 100;
    private static final int MAX_DIMENSION_LIMIT = 10000;

    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    @Override
    public ByteArrayResource convertImage(MultipartFile file, String format) throws IOException {
        return convertImage(file, format, null, null);
    }

    @Override
    public ByteArrayResource convertImage(MultipartFile file, String format,
                                          Integer quality, Integer maxSize) throws IOException {
        String target = normalizeFormat(format);
        validateTargetFormat(target);

        logger.info("Image conversion → {} (quality={}, maxSize={}), input {} bytes",
                target, quality, maxSize, file.getSize());

        // Temp files (not stdin/stdout pipes): robust and matches the Calibre
        // path. Input keeps its original extension to help format detection.
        String srcExt = getExtension(file.getOriginalFilename());
        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("img-in-", srcExt.isEmpty() ? ".bin" : "." + srcExt);
            outputFile = createTempFile("img-out-", "." + target);
            file.transferTo(inputFile.toFile());

            List<String> command = buildCommand(inputFile.toString(), outputFile.toString(), target, quality, maxSize);
            logger.debug("ImageMagick command: {}", command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Conversion timed out");
            }
            if (process.exitValue() != 0) {
                logger.error("ImageMagick failed (exit {}): {}", process.exitValue(), output);
                throw new IOException("Conversion failed: " + output.toString().trim());
            }

            byte[] result = Files.readAllBytes(outputFile);
            logger.info("Conversion completed. Output size: {} bytes", result.length);
            return new ByteArrayResource(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversion interrupted", e);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────

    static String normalizeFormat(String format) {
        if (format == null) return "";
        return format.trim().toLowerCase().replaceAll("^\\.", "");
    }

    static String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    static void validateTargetFormat(String target) {
        if (!SUPPORTED_TARGET_FORMATS.contains(target)) {
            throw new IllegalArgumentException("Unsupported target format: " + target
                    + ". Supported: " + SUPPORTED_TARGET_FORMATS);
        }
    }

    // magick <input> [-resize NxN>] [-quality N] <target>:<output>
    static List<String> buildCommand(String input, String output, String target,
                                     Integer quality, Integer maxSize) {
        List<String> cmd = new ArrayList<>();
        cmd.add("magick");
        cmd.add(input);
        if (maxSize != null) {
            if (maxSize < 1 || maxSize > MAX_DIMENSION_LIMIT) {
                throw new IllegalArgumentException("maxSize must be 1.." + MAX_DIMENSION_LIMIT);
            }
            cmd.add("-resize");
            cmd.add(maxSize + "x" + maxSize + ">");
        }
        if (quality != null) {
            if (quality < MIN_QUALITY || quality > MAX_QUALITY) {
                throw new IllegalArgumentException("quality must be " + MIN_QUALITY + ".." + MAX_QUALITY);
            }
            cmd.add("-quality");
            cmd.add(String.valueOf(quality));
        }
        cmd.add(target + ":" + output);
        return cmd;
    }

    private Path createTempFile(String prefix, String suffix) throws IOException {
        Path dir = Paths.get(tempDir);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return Files.createTempFile(dir, prefix, suffix);
    }

    private void cleanupQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                logger.warn("Не удалось удалить временный файл: {}", path, e);
            }
        }
    }
}

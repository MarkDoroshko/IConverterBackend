package ru.iconverter.services.conversions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class ImagesConversionService implements IImagesConversionService {

    private static final Logger logger = LoggerFactory.getLogger(ImagesConversionService.class);

    // Raster output formats we allow. Vector/PDF output from a raster source is
    // intentionally excluded — it produces a bitmap wrapped in a container, which
    // surprises users. Input format is auto-detected by ImageMagick from the
    // stream, so HEIC/HEIF and others work without an explicit input whitelist.
    public static final Set<String> SUPPORTED_TARGET_FORMATS =
            Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff");

    // Bounds for optional tuning params.
    private static final int MIN_QUALITY = 1;
    private static final int MAX_QUALITY = 100;
    private static final int MAX_DIMENSION_LIMIT = 10000; // px, sanity cap

    @Override
    public ByteArrayResource convertImage(MultipartFile file, String format) throws IOException {
        return convertImage(file, format, null, null);
    }

    @Override
    public ByteArrayResource convertImage(MultipartFile file, String format,
                                          Integer quality, Integer maxSize) throws IOException {
        String target = normalizeFormat(format);
        validateTargetFormat(target);

        logger.info("Image conversion → {} (quality={}, maxSize={}), input {} bytes, type {}",
                target, quality, maxSize, file.getSize(), file.getContentType());

        byte[] fileBytes = file.getBytes();
        List<String> command = buildCommand(target, quality, maxSize);
        logger.debug("ImageMagick command: {}", command);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        StringBuilder errorOutput = new StringBuilder();

        try (OutputStream processOutputStream = process.getOutputStream();
             InputStream processInputStream = process.getInputStream();
             InputStream errorStream = process.getErrorStream()) {

            // Write the uploaded bytes to the process stdin.
            processOutputStream.write(fileBytes);
            processOutputStream.close();

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = processInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            while ((bytesRead = errorStream.read(buffer)) != -1) {
                errorOutput.append(new String(buffer, 0, bytesRead));
            }

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Conversion timed out");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.error("ImageMagick failed (exit {}): {}", exitCode, errorOutput);
                throw new IOException("Conversion failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            logger.error("Conversion interrupted", e);
            Thread.currentThread().interrupt();
            throw new IOException("Conversion interrupted", e);
        }

        logger.info("Conversion completed. Output size: {} bytes", outputStream.size());
        return new ByteArrayResource(outputStream.toByteArray());
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────

    static String normalizeFormat(String format) {
        if (format == null) return "";
        return format.trim().toLowerCase().replaceAll("^\\.", "");
    }

    static void validateTargetFormat(String target) {
        if (!SUPPORTED_TARGET_FORMATS.contains(target)) {
            throw new IllegalArgumentException("Unsupported target format: " + target
                    + ". Supported: " + SUPPORTED_TARGET_FORMATS);
        }
    }

    // Build the `magick` invocation. Reads from stdin ("-"), writes the chosen
    // format to stdout ("format:-"). Optional resize (preserve aspect ratio,
    // only shrink) and quality come before the output target.
    static List<String> buildCommand(String target, Integer quality, Integer maxSize) {
        List<String> cmd = new ArrayList<>();
        cmd.add("magick");
        cmd.add("-");
        if (maxSize != null) {
            if (maxSize < 1 || maxSize > MAX_DIMENSION_LIMIT) {
                throw new IllegalArgumentException("maxSize must be 1.." + MAX_DIMENSION_LIMIT);
            }
            // ">" = only shrink images larger than the box, never enlarge.
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
        cmd.add(target + ":-");
        return cmd;
    }
}

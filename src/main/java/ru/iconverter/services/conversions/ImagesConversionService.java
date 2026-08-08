package ru.iconverter.services.conversions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    private static final int MIN_FAVICON_SIZE = 16;
    private static final int MAX_FAVICON_SIZE = 512;
    private static final List<Integer> DEFAULT_FAVICON_SIZES = List.of(16, 32, 48, 64, 128, 256);

    static final Map<String, List<String>> FILTER_OPS = Map.of(
            "grayscale", List.of("-colorspace", "Gray"),
            "sepia", List.of("-sepia-tone", "80%"),
            "negate", List.of("-negate"),
            "blur", List.of("-blur", "0x8"),
            "sharpen", List.of("-sharpen", "0x1"));

    private static final int MIN_OPACITY = 1;
    private static final int MAX_OPACITY = 100;
    private static final int DEFAULT_OPACITY = 50;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 400;
    private static final int DEFAULT_FONT_SIZE = 36;
    private static final String DEFAULT_WATERMARK_GRAVITY = "southeast";

    static final Set<String> OPTIMIZABLE_FORMATS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final int DEFAULT_OPTIMIZE_QUALITY = 80;

    // ML inference is slower than the ImageMagick/CLI ops elsewhere in this class.
    private static final int BACKGROUND_REMOVAL_TIMEOUT_SECONDS = 90;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

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
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }

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

    @Override
    public ByteArrayResource resize(MultipartFile file, Integer width, Integer height, String mode) throws IOException {
        String ext = outputExt(file);
        String geometry = buildResizeGeometry(width, height, mode);
        logger.info("Image resize → {} (mode={}), input {} bytes", geometry, mode, file.getSize());
        return runMagick(file, ext, List.of("-resize", geometry));
    }

    @Override
    public ByteArrayResource crop(MultipartFile file, int width, int height, String gravity) throws IOException {
        String ext = outputExt(file);
        logger.info("Image crop → {}x{} (gravity={}), input {} bytes", width, height, gravity, file.getSize());
        return runMagick(file, ext, buildCropOps(width, height, gravity));
    }

    @Override
    public ByteArrayResource favicon(MultipartFile file, String sizes) throws IOException {
        List<Integer> faviconSizes = parseFaviconSizes(sizes);
        logger.info("Favicon generation, sizes={}, input {} bytes", faviconSizes, file.getSize());

        String srcExt = getExtension(file.getOriginalFilename());
        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("favicon-in-", srcExt.isEmpty() ? ".bin" : "." + srcExt);
            outputFile = createTempFile("favicon-out-", ".ico");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> command = buildFaviconCommand(inputFile.toString(), outputFile.toString(), faviconSizes);
            runProcess(command, "Favicon generation");
            byte[] result = Files.readAllBytes(outputFile);
            logger.info("Favicon completed. Output size: {} bytes", result.length);
            return new ByteArrayResource(result);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public ByteArrayResource filter(MultipartFile file, String filter) throws IOException {
        String ext = outputExt(file);
        logger.info("Image filter → {}, input {} bytes", filter, file.getSize());
        return runMagick(file, ext, filterOps(filter));
    }

    @Override
    public ByteArrayResource watermark(MultipartFile file, MultipartFile watermarkImage, String text,
                                       String gravity, Integer opacity, Integer fontSize) throws IOException {
        boolean hasImage = watermarkImage != null && !watermarkImage.isEmpty();
        boolean hasText = text != null && !text.isBlank();
        if (hasImage == hasText) {
            throw new IllegalArgumentException("Provide exactly one of: watermark image or text");
        }

        String ext = outputExt(file);
        int opacityValue = opacity == null ? DEFAULT_OPACITY : opacity;
        String gravityValue = (gravity == null || gravity.isBlank()) ? DEFAULT_WATERMARK_GRAVITY : gravity;
        logger.info("Image watermark ({}) gravity={} opacity={}, input {} bytes",
                hasImage ? "image" : "text", gravityValue, opacityValue, file.getSize());

        Path inputFile = null;
        Path watermarkFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("wm-in-", "." + ext);
            outputFile = createTempFile("wm-out-", "." + ext);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }

            List<String> command;
            if (hasImage) {
                String wmExt = getExtension(watermarkImage.getOriginalFilename());
                watermarkFile = createTempFile("wm-mark-", wmExt.isEmpty() ? ".png" : "." + wmExt);
                try (InputStream in = watermarkImage.getInputStream()) {
                    Files.copy(in, watermarkFile, StandardCopyOption.REPLACE_EXISTING);
                }
                command = buildWatermarkImageCommand(inputFile.toString(), watermarkFile.toString(),
                        outputFile.toString(), gravityValue, opacityValue);
            } else {
                int fontSizeValue = fontSize == null ? DEFAULT_FONT_SIZE : fontSize;
                if (fontSizeValue < MIN_FONT_SIZE || fontSizeValue > MAX_FONT_SIZE) {
                    throw new IllegalArgumentException("fontSize must be " + MIN_FONT_SIZE + ".." + MAX_FONT_SIZE);
                }
                command = buildWatermarkTextCommand(inputFile.toString(), outputFile.toString(), text,
                        gravityValue, opacityValue, fontSizeValue);
            }
            runProcess(command, "Watermark");
            byte[] result = Files.readAllBytes(outputFile);
            logger.info("Watermark completed. Output size: {} bytes", result.length);
            return new ByteArrayResource(result);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(watermarkFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public ByteArrayResource optimize(MultipartFile file, Integer quality) throws IOException {
        String ext = outputExt(file);
        validateOptimizableFormat(ext);
        int q = quality == null ? DEFAULT_OPTIMIZE_QUALITY : quality;
        if (q < MIN_QUALITY || q > MAX_QUALITY) {
            throw new IllegalArgumentException("quality must be " + MIN_QUALITY + ".." + MAX_QUALITY);
        }
        logger.info("Image optimize ({}) quality={}, input {} bytes", ext, q, file.getSize());

        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("opt-in-", "." + ext);
            outputFile = createTempFile("opt-out-", "." + ext);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }

            List<String> command;
            if (ext.equals("jpg") || ext.equals("jpeg")) {
                // jpegoptim rewrites its argument in place, so seed the output path first.
                Files.copy(inputFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
                command = buildJpegoptimCommand(outputFile.toString(), q);
            } else if (ext.equals("png")) {
                command = buildPngquantCommand(inputFile.toString(), outputFile.toString(), q);
            } else if (ext.equals("gif")) {
                command = buildGifsicleCommand(inputFile.toString(), outputFile.toString(), q);
            } else {
                command = buildCwebpCommand(inputFile.toString(), outputFile.toString(), q);
            }
            runProcess(command, "Image optimization");
            byte[] result = Files.readAllBytes(outputFile);
            logger.info("Optimization completed. Input {} bytes, output {} bytes", file.getSize(), result.length);
            return new ByteArrayResource(result);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public ByteArrayResource removeBackground(MultipartFile file) throws IOException {
        logger.info("Background removal, input {} bytes", file.getSize());
        String srcExt = getExtension(file.getOriginalFilename());
        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("bgremove-in-", srcExt.isEmpty() ? ".bin" : "." + srcExt);
            outputFile = createTempFile("bgremove-out-", ".png");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }
            runProcess(buildRembgCommand(inputFile.toString(), outputFile.toString()),
                    "Background removal", BACKGROUND_REMOVAL_TIMEOUT_SECONDS);
            byte[] result = Files.readAllBytes(outputFile);
            logger.info("Background removal completed. Output size: {} bytes", result.length);
            return new ByteArrayResource(result);
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

    // Output keeps the input format; reject formats ImageMagick can't write here.
    static String outputExt(MultipartFile file) {
        String ext = getExtension(file.getOriginalFilename());
        if (ext.equals("jpeg")) ext = "jpg";
        validateTargetFormat(ext);
        return ext;
    }

    static void checkDim(int v) {
        if (v < 1 || v > MAX_DIMENSION_LIMIT) {
            throw new IllegalArgumentException("Размер должен быть 1.." + MAX_DIMENSION_LIMIT);
        }
    }

    // Build an ImageMagick -resize geometry from width/height/mode.
    static String buildResizeGeometry(Integer width, Integer height, String mode) {
        String m = mode == null ? "fit" : mode.trim().toLowerCase();
        if (m.equals("percent")) {
            if (width == null || width < 1 || width > 1000) {
                throw new IllegalArgumentException("Процент должен быть 1..1000");
            }
            return width + "%";
        }
        if (width == null && height == null) {
            throw new IllegalArgumentException("Укажите ширину и/или высоту");
        }
        if (width != null) checkDim(width);
        if (height != null) checkDim(height);
        String g = (width == null ? "" : width) + "x" + (height == null ? "" : height);
        return m.equals("exact") ? g + "!" : g; // "fit" preserves aspect ratio
    }

    private static final Set<String> GRAVITIES = Set.of(
            "center", "north", "south", "east", "west",
            "northwest", "northeast", "southwest", "southeast");

    static String normGravity(String gravity) {
        String g = gravity == null ? "center" : gravity.trim().toLowerCase();
        if (!GRAVITIES.contains(g)) {
            throw new IllegalArgumentException("Недопустимое значение gravity: " + gravity);
        }
        // ImageMagick wants CamelCase gravity names (Center, NorthWest, …).
        switch (g) {
            case "northwest": return "NorthWest";
            case "northeast": return "NorthEast";
            case "southwest": return "SouthWest";
            case "southeast": return "SouthEast";
            default: return Character.toUpperCase(g.charAt(0)) + g.substring(1);
        }
    }

    // Cover-crop: scale to fill WxH (^), then trim the overflow with -extent.
    static List<String> buildCropOps(int width, int height, String gravity) {
        checkDim(width);
        checkDim(height);
        String wh = width + "x" + height;
        return List.of("-resize", wh + "^", "-gravity", normGravity(gravity), "-extent", wh, "+repage");
    }

    static List<Integer> parseFaviconSizes(String sizes) {
        if (sizes == null || sizes.isBlank()) return DEFAULT_FAVICON_SIZES;
        List<Integer> result = new ArrayList<>();
        for (String s : sizes.split(",")) {
            int v;
            try {
                v = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid favicon size: " + s);
            }
            if (v < MIN_FAVICON_SIZE || v > MAX_FAVICON_SIZE) {
                throw new IllegalArgumentException(
                        "Favicon size must be " + MIN_FAVICON_SIZE + ".." + MAX_FAVICON_SIZE + ": " + v);
            }
            result.add(v);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No favicon sizes provided");
        }
        return result;
    }

    // magick <input> -define icon:auto-resize=16,32,48,... <output.ico>
    static List<String> buildFaviconCommand(String input, String output, List<Integer> sizes) {
        String sizesArg = sizes.stream().map(String::valueOf).collect(Collectors.joining(","));
        return List.of("magick", input, "-define", "icon:auto-resize=" + sizesArg, output);
    }

    static List<String> filterOps(String filter) {
        String key = normalizeFormat(filter);
        List<String> ops = FILTER_OPS.get(key);
        if (ops == null) {
            throw new IllegalArgumentException("Unsupported filter: " + filter + ". Supported: " + FILTER_OPS.keySet());
        }
        return ops;
    }

    static void validateOpacity(int opacity) {
        if (opacity < MIN_OPACITY || opacity > MAX_OPACITY) {
            throw new IllegalArgumentException("opacity must be " + MIN_OPACITY + ".." + MAX_OPACITY);
        }
    }

    static String opacityFraction(int opacity) {
        validateOpacity(opacity);
        return String.format(Locale.ROOT, "%.2f", opacity / 100.0);
    }

    // magick <input> ( <watermark> -alpha set -channel A -evaluate Multiply <opacity> +channel )
    //        -gravity <g> -compose over -composite <output>
    static List<String> buildWatermarkImageCommand(String input, String watermark, String output,
                                                    String gravity, int opacity) {
        String frac = opacityFraction(opacity);
        return List.of("magick", input,
                "(", watermark, "-alpha", "set", "-channel", "A", "-evaluate", "Multiply", frac, "+channel", ")",
                "-gravity", normGravity(gravity), "-compose", "over", "-composite", output);
    }

    // magick <input> -gravity <g> -fill rgba(255,255,255,<opacity>) -pointsize <n> -annotate +20+20 <text> <output>
    static List<String> buildWatermarkTextCommand(String input, String output, String text,
                                                  String gravity, int opacity, int fontSize) {
        String frac = opacityFraction(opacity);
        return List.of("magick", input,
                "-gravity", normGravity(gravity),
                "-fill", "rgba(255,255,255," + frac + ")",
                "-pointsize", String.valueOf(fontSize),
                "-annotate", "+20+20", text,
                output);
    }

    static void validateOptimizableFormat(String ext) {
        if (!OPTIMIZABLE_FORMATS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported format for optimization: " + ext
                    + ". Supported: " + OPTIMIZABLE_FORMATS);
        }
    }

    // jpegoptim rewrites <path> in place.
    static List<String> buildJpegoptimCommand(String path, int quality) {
        return List.of("jpegoptim", "--max=" + quality, "--strip-all", path);
    }

    // pngquant picks its own palette within the quality range; low bound 0 lets
    // it use as few colors as needed to hit the target (upper bound) quality.
    static List<String> buildPngquantCommand(String input, String output, int quality) {
        return List.of("pngquant", "--quality=0-" + quality, "--strip", "--force", "--output", output, input);
    }

    // gifsicle's --lossy scale runs the opposite way from "quality": higher = more compression.
    static List<String> buildGifsicleCommand(String input, String output, int quality) {
        int lossy = MAX_QUALITY - quality;
        return List.of("gifsicle", "-O3", "--lossy=" + lossy, "-o", output, input);
    }

    static List<String> buildCwebpCommand(String input, String output, int quality) {
        return List.of("cwebp", "-q", String.valueOf(quality), input, "-o", output);
    }

    static List<String> buildRembgCommand(String input, String output) {
        return List.of("rembg", "i", input, output);
    }

    // Run `magick <input> <ops...> <ext>:<output>` and return the bytes.
    private ByteArrayResource runMagick(MultipartFile file, String ext, List<String> ops) throws IOException {
        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("img-in-", "." + ext);
            outputFile = createTempFile("img-out-", "." + ext);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> command = new ArrayList<>();
            command.add("magick");
            command.add(inputFile.toString());
            command.addAll(ops);
            command.add(ext + ":" + outputFile.toString());
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
                throw new IOException("Operation timed out");
            }
            if (process.exitValue() != 0) {
                logger.error("ImageMagick failed (exit {}): {}", process.exitValue(), output);
                throw new IOException("Operation failed: " + output.toString().trim());
            }
            byte[] result = Files.readAllBytes(outputFile);
            logger.info("Image op completed. Output size: {} bytes", result.length);
            return new ByteArrayResource(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted", e);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    // Run an arbitrary ImageMagick command and wait for it; throws IOException on failure/timeout.
    private void runProcess(List<String> command, String opName) throws IOException {
        runProcess(command, opName, DEFAULT_TIMEOUT_SECONDS);
    }

    private void runProcess(List<String> command, String opName, int timeoutSeconds) throws IOException {
        logger.debug("ImageMagick command: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(opName + " timed out");
            }
            if (process.exitValue() != 0) {
                logger.error("{} failed (exit {}): {}", opName, process.exitValue(), output);
                throw new IOException(opName + " failed: " + output.toString().trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(opName + " interrupted", e);
        }
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

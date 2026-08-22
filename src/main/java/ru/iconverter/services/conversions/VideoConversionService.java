package ru.iconverter.services.conversions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.utils.ProcessUtils;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class VideoConversionService implements IVideoConversionService {

    private static final Logger log = LoggerFactory.getLogger(VideoConversionService.class);

    // Video transcoding is much heavier than audio — give ffmpeg more time
    // before we give up and kill the process.
    private static final long TIMEOUT_SECONDS = 300;

    public static final Set<String> SUPPORTED_TARGET_FORMATS =
            Set.of("mp4", "avi", "mov", "mkv", "webm");

    public static final Set<String> SUPPORTED_SOURCE_FORMATS = Set.of(
            "mp4", "m4v", "mov", "mkv", "avi", "webm", "flv", "wmv", "3gp", "ts");

    private static final Map<String, Integer> RESOLUTION_HEIGHTS =
            Map.of("2160", 2160, "1080", 1080, "720", 720, "480", 480);

    // Accepts plain seconds ("12.5") or HH:MM:SS(.ms) — both valid ffmpeg -ss/-to values.
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d+(\\.\\d+)?$|^\\d{1,2}:\\d{2}:\\d{2}(\\.\\d+)?$");

    private static final int GIF_MAX_DURATION_SECONDS = 15;
    private static final int GIF_MIN_FPS = 1;
    private static final int GIF_MAX_FPS = 30;
    private static final int GIF_DEFAULT_FPS = 10;
    private static final int GIF_MIN_WIDTH = 100;
    private static final int GIF_MAX_WIDTH = 1280;
    private static final int GIF_DEFAULT_WIDTH = 480;

    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    // See ru.iconverter.utils.ProcessUtils — bounds ffmpeg's virtual memory so
    // it fails cleanly instead of risking an OOM-killer hit on the JVM.
    @Value("${app.process.memory-limit-mb:1200}")
    private long processMemoryLimitMb;

    @Override
    public Resource convert(MultipartFile file, String targetFormat) {
        String target = normalize(targetFormat);
        String source = validateUpload(file);
        validate(source, target);

        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = writeToTemp(file, "video-in-", source);
            outputFile = createTempFile("video-out-", "." + target);

            List<String> command = buildCommand(inputFile.toString(), outputFile.toString(), target);
            log.info("Video convert {} → {} ({} bytes)", source, target, file.getSize());
            runFfmpeg(command, "Не удалось сконвертировать видео");

            return readResult(outputFile, "Video convert done: " + source + " → target " + target);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public Resource resize(MultipartFile file, String resolution) {
        String source = validateUpload(file);
        if (!SUPPORTED_SOURCE_FORMATS.contains(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source
                    + ". Supported: " + SUPPORTED_SOURCE_FORMATS);
        }
        int height = resolveHeight(resolution);

        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = writeToTemp(file, "video-in-", source);
            outputFile = createTempFile("video-out-", "." + source);

            List<String> command = buildResizeCommand(inputFile.toString(), outputFile.toString(), height);
            log.info("Video resize {} → {}p ({} bytes)", source, height, file.getSize());
            runFfmpeg(command, "Не удалось изменить разрешение видео");

            return readResult(outputFile, "Video resize done: " + source + " → " + height + "p");
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public Resource trim(MultipartFile file, String startTime, String endTime) {
        String source = validateUpload(file);
        if (!SUPPORTED_SOURCE_FORMATS.contains(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source
                    + ". Supported: " + SUPPORTED_SOURCE_FORMATS);
        }
        String start = validateTimestamp(startTime, "startTime", "0");
        String end = validateTimestamp(endTime, "endTime", null);

        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = writeToTemp(file, "video-in-", source);
            outputFile = createTempFile("video-out-", "." + source);

            List<String> command = buildTrimCommand(inputFile.toString(), outputFile.toString(), start, end);
            log.info("Video trim {} [{} → {}] ({} bytes)", source, start, end, file.getSize());
            runFfmpeg(command, "Не удалось обрезать видео");

            return readResult(outputFile, "Video trim done: " + source);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public Resource toGif(MultipartFile file, String startTime, String duration, Integer fps, Integer width) {
        String source = validateUpload(file);
        if (!SUPPORTED_SOURCE_FORMATS.contains(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source
                    + ". Supported: " + SUPPORTED_SOURCE_FORMATS);
        }
        String start = validateTimestamp(startTime, "startTime", "0");
        String dur = validateGifDuration(duration);
        int resolvedFps = validateGifFps(fps);
        int resolvedWidth = validateGifWidth(width);

        Path inputFile = null;
        Path paletteFile = null;
        Path outputFile = null;
        try {
            inputFile = writeToTemp(file, "video-in-", source);
            paletteFile = createTempFile("video-palette-", ".png");
            outputFile = createTempFile("video-out-", ".gif");

            List<String> paletteCmd = buildGifPaletteCommand(
                    inputFile.toString(), paletteFile.toString(), start, dur, resolvedFps, resolvedWidth);
            log.info("GIF palette gen for {} ({} bytes)", source, file.getSize());
            runFfmpeg(paletteCmd, "Не удалось создать GIF");

            List<String> renderCmd = buildGifRenderCommand(
                    inputFile.toString(), paletteFile.toString(), outputFile.toString(),
                    start, dur, resolvedFps, resolvedWidth);
            runFfmpeg(renderCmd, "Не удалось создать GIF");

            return readResult(outputFile, "GIF render done: " + source);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(paletteFile);
            cleanupQuietly(outputFile);
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
        if (!SUPPORTED_TARGET_FORMATS.contains(target)) {
            throw new IllegalArgumentException("Unsupported target format: " + target
                    + ". Supported: " + SUPPORTED_TARGET_FORMATS);
        }
        if (!SUPPORTED_SOURCE_FORMATS.contains(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source
                    + ". Supported: " + SUPPORTED_SOURCE_FORMATS);
        }
    }

    static int resolveHeight(String resolution) {
        String key = resolution == null ? "" : resolution.trim().toLowerCase().replaceAll("p$", "");
        Integer height = RESOLUTION_HEIGHTS.get(key);
        if (height == null) {
            throw new IllegalArgumentException("Unsupported resolution: " + resolution
                    + ". Supported: " + RESOLUTION_HEIGHTS.keySet());
        }
        return height;
    }

    static String validateTimestamp(String value, String paramName, String defaultValue) {
        if (value == null || value.isBlank()) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException(paramName + " is required");
        }
        String trimmed = value.trim();
        if (!TIMESTAMP.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid " + paramName + ": " + value
                    + ". Use seconds (e.g. 12.5) or HH:MM:SS");
        }
        return trimmed;
    }

    static String validateGifDuration(String duration) {
        if (duration == null || duration.isBlank()) {
            throw new IllegalArgumentException("duration is required");
        }
        double seconds;
        try {
            seconds = Double.parseDouble(duration.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration: " + duration);
        }
        if (seconds <= 0 || seconds > GIF_MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "duration must be between 0 and " + GIF_MAX_DURATION_SECONDS + " seconds");
        }
        return duration.trim();
    }

    static int validateGifFps(Integer fps) {
        int value = fps == null ? GIF_DEFAULT_FPS : fps;
        if (value < GIF_MIN_FPS || value > GIF_MAX_FPS) {
            throw new IllegalArgumentException("fps must be between " + GIF_MIN_FPS + " and " + GIF_MAX_FPS);
        }
        return value;
    }

    static int validateGifWidth(Integer width) {
        int value = width == null ? GIF_DEFAULT_WIDTH : width;
        if (value < GIF_MIN_WIDTH || value > GIF_MAX_WIDTH) {
            throw new IllegalArgumentException("width must be between " + GIF_MIN_WIDTH + " and " + GIF_MAX_WIDTH);
        }
        return value;
    }

    // ffmpeg -y -i <input> <codec args> <output>. Codec choice depends on the
    // target container so playback compatibility stays sane per format.
    static List<String> buildCommand(String input, String output, String target) {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-i", input));
        switch (target) {
            case "mp4":
            case "mov":
            case "mkv":
                cmd.addAll(List.of(
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                        "-c:a", "aac", "-b:a", "192k"));
                break;
            case "avi":
                cmd.addAll(List.of(
                        "-c:v", "mpeg4", "-q:v", "5",
                        "-c:a", "libmp3lame", "-q:a", "4"));
                break;
            case "webm":
                cmd.addAll(List.of(
                        "-c:v", "libvpx-vp9", "-crf", "30", "-b:v", "0",
                        "-c:a", "libopus", "-b:a", "128k"));
                break;
            default:
                throw new IllegalArgumentException("Unsupported target format: " + target);
        }
        cmd.add(output);
        return cmd;
    }

    // Scale to a target height, preserving aspect ratio (-2 rounds width to an even number).
    static List<String> buildResizeCommand(String input, String output, int height) {
        return List.of("ffmpeg", "-y", "-i", input,
                "-vf", "scale=-2:" + height,
                "-c:a", "copy", output);
    }

    // Output-seeking trim (accurate) with stream copy (fast, no re-encode).
    static List<String> buildTrimCommand(String input, String output, String start, String end) {
        return List.of("ffmpeg", "-y", "-i", input,
                "-ss", start, "-to", end,
                "-c", "copy", output);
    }

    static List<String> buildGifPaletteCommand(
            String input, String palette, String start, String duration, int fps, int width) {
        String filter = "fps=" + fps + ",scale=" + width + ":-1:flags=lanczos,palettegen";
        return List.of("ffmpeg", "-y", "-ss", start, "-t", duration, "-i", input,
                "-vf", filter, palette);
    }

    static List<String> buildGifRenderCommand(
            String input, String palette, String output, String start, String duration, int fps, int width) {
        String filter = "fps=" + fps + ",scale=" + width + ":-1:flags=lanczos[x];[x][1:v]paletteuse";
        return List.of("ffmpeg", "-y", "-ss", start, "-t", duration, "-i", input, "-i", palette,
                "-filter_complex", filter, output);
    }

    // ── Process / I/O plumbing ───────────────────────────────────────────

    private String validateUpload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        return normalize(getExtension(file.getOriginalFilename()));
    }

    private Path writeToTemp(MultipartFile file, String prefix, String extension) throws RuntimeException {
        try {
            Path path = createTempFile(prefix, extension.isEmpty() ? ".bin" : "." + extension);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return path;
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке видео: " + e.getMessage(), e);
        }
    }

    private void runFfmpeg(List<String> command, String failureMessage) {
        log.debug("ffmpeg command: {}", command);
        try {
            ProcessBuilder pb = new ProcessBuilder(ProcessUtils.withMemoryLimit(command, processMemoryLimitMb));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Операция превысила лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("ffmpeg failed (exit {}): {}", process.exitValue(), output);
                throw new RuntimeException(failureMessage);
            }
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке видео: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Операция прервана", e);
        }
    }

    private Resource readResult(Path outputFile, String logMessage) {
        try {
            byte[] bytes = Files.readAllBytes(outputFile);
            log.info("{} ({} bytes)", logMessage, bytes.length);
            return new ByteArrayResource(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке видео: " + e.getMessage(), e);
        }
    }

    private Path createTempFile(String prefix, String suffix) {
        try {
            Path dir = Paths.get(tempDir);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            return Files.createTempFile(dir, prefix, suffix);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке видео: " + e.getMessage(), e);
        }
    }

    private void cleanupQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Не удалось удалить временный файл: {}", path, e);
            }
        }
    }
}

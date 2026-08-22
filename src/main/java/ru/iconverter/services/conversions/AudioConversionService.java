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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class AudioConversionService implements IAudioConversionService {

    private static final Logger log = LoggerFactory.getLogger(AudioConversionService.class);

    // Audio formats we can write. m4a is an AAC stream in an MP4 container.
    public static final Set<String> SUPPORTED_TARGET_FORMATS =
            Set.of("mp3", "wav", "aac", "m4a", "ogg", "flac");

    // Accepted input containers: common video (audio is extracted) + audio.
    public static final Set<String> SUPPORTED_SOURCE_FORMATS = Set.of(
            "mp4", "m4v", "mov", "mkv", "avi", "webm", "flv", "wmv", "3gp", "ts",
            "mp3", "wav", "aac", "m4a", "ogg", "oga", "flac", "wma", "opus", "aiff");

    // Accepts plain seconds ("12.5") or HH:MM:SS(.ms) — both valid ffmpeg -ss/-to values.
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d+(\\.\\d+)?$|^\\d{1,2}:\\d{2}:\\d{2}(\\.\\d+)?$");

    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    // See ru.iconverter.utils.ProcessUtils — bounds ffmpeg's virtual memory so
    // it fails cleanly instead of risking an OOM-killer hit on the JVM.
    @Value("${app.process.memory-limit-mb:1200}")
    private long processMemoryLimitMb;

    @Override
    public Resource trim(MultipartFile file, String startTime, String endTime) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String source = normalize(getExtension(file.getOriginalFilename()));
        if (!SUPPORTED_SOURCE_FORMATS.contains(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source
                    + ". Supported: " + SUPPORTED_SOURCE_FORMATS);
        }
        String start = validateTimestamp(startTime, "startTime", "0");
        String end = validateTimestamp(endTime, "endTime", null);

        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("audio-in-", source.isEmpty() ? ".bin" : "." + source);
            outputFile = createTempFile("audio-out-", "." + source);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }

            List<String> command = buildTrimCommand(inputFile.toString(), outputFile.toString(), start, end);
            log.info("Audio trim {} [{} → {}] ({} bytes)", source, start, end, file.getSize());
            log.debug("ffmpeg command: {}", command);

            ProcessBuilder pb = new ProcessBuilder(ProcessUtils.withMemoryLimit(command, processMemoryLimitMb));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Обрезка аудио превысила лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("ffmpeg failed (exit {}): {}", process.exitValue(), output);
                throw new RuntimeException("Не удалось обрезать аудио");
            }

            byte[] bytes = Files.readAllBytes(outputFile);
            log.info("Audio trim done: {} → {} bytes", source, bytes.length);
            return new ByteArrayResource(bytes);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке аудио: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Обрезка прервана", e);
        } finally {
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    @Override
    public Resource convert(MultipartFile file, String targetFormat) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String target = normalize(targetFormat);
        String source = normalize(getExtension(file.getOriginalFilename()));
        validate(source, target);

        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = createTempFile("audio-in-", source.isEmpty() ? ".bin" : "." + source);
            outputFile = createTempFile("audio-out-", "." + target);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, inputFile, StandardCopyOption.REPLACE_EXISTING);
            }

            List<String> command = buildCommand(inputFile.toString(), outputFile.toString(), target);
            log.info("Audio convert {} → {} ({} bytes)", source, target, file.getSize());
            log.debug("ffmpeg command: {}", command);

            ProcessBuilder pb = new ProcessBuilder(ProcessUtils.withMemoryLimit(command, processMemoryLimitMb));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Конвертация аудио превысила лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("ffmpeg failed (exit {}): {}", process.exitValue(), output);
                if (noAudioStream(output.toString())) {
                    throw new IllegalArgumentException(
                            "В загруженном файле нет звуковой дорожки — извлекать нечего.");
                }
                throw new RuntimeException("Не удалось сконвертировать аудио");
            }

            byte[] bytes = Files.readAllBytes(outputFile);
            log.info("Audio convert done: {} → {} bytes", source, bytes.length);
            return new ByteArrayResource(bytes);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке аудио: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Конвертация прервана", e);
        } finally {
            cleanupQuietly(inputFile);
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

    // When a video has no audio track, `ffmpeg -vn` produces no output stream and
    // exits with one of these messages. Detect that to return a clear 400 instead
    // of a generic failure.
    static boolean noAudioStream(String ffmpegOutput) {
        if (ffmpegOutput == null) return false;
        String s = ffmpegOutput.toLowerCase();
        return s.contains("does not contain any stream")
                || s.contains("matches no streams")
                || s.contains("output file does not contain any stream");
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

    // Output-seeking trim (accurate) with stream copy (fast, no re-encode).
    static List<String> buildTrimCommand(String input, String output, String start, String end) {
        return List.of("ffmpeg", "-y", "-i", input,
                "-ss", start, "-to", end,
                "-c", "copy", output);
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

    // ffmpeg -y -i <input> -vn <codec args> <output>. -vn drops any video track,
    // so MP4/MOV/etc. yield just the audio.
    static List<String> buildCommand(String input, String output, String target) {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-i", input, "-vn"));
        switch (target) {
            case "mp3":
                cmd.addAll(List.of("-codec:a", "libmp3lame", "-q:a", "2"));
                break;
            case "aac":
            case "m4a":
                cmd.addAll(List.of("-codec:a", "aac", "-b:a", "192k"));
                break;
            case "ogg":
                cmd.addAll(List.of("-codec:a", "libvorbis", "-q:a", "5"));
                break;
            case "flac":
                cmd.addAll(List.of("-codec:a", "flac"));
                break;
            case "wav":
                // Default PCM encoding from the container extension.
                break;
            default:
                throw new IllegalArgumentException("Unsupported target format: " + target);
        }
        cmd.add(output);
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
                log.warn("Не удалось удалить временный файл: {}", path, e);
            }
        }
    }
}

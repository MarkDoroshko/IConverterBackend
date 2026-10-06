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
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

@Service
public class CalibreBookConversionService implements IBookConversionService {

    private static final Logger log = LoggerFactory.getLogger(CalibreBookConversionService.class);

    protected static final Set<String> SUPPORTED_SOURCE_FORMATS = Set.of("azw", "azw3", "azw4", "cbz", "cbr", "cb7", "cbc", "chm", "djvu", "docx", "epub", "fb2",
            "fbz", "html", "htmlz", "kepub", "lit", "lrf", "mobi", "odt", "pdf", "prc", "pdb", "pml", "rb", "rtf", "snb", "tcr", "txt", "txtz");
    protected static final Set<String> SUPPORTED_TARGET_FORMATS = Set.of("azw3", "epub", "docx", "fb2", "htmlz", "kepub", "oeb", "lit", "lrf", "mobi", "pdb", "pmlz",
            "rb", "pdf", "rtf", "snb", "tcr", "txt", "txtz", "zip");

    // Путь к временной директории (по умолчанию /tmp)
    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    // See ru.iconverter.utils.ProcessUtils — bounds ebook-convert's virtual
    // memory so it fails cleanly instead of risking an OOM-killer hit on the JVM.
    @Value("${app.process.memory-limit-mb:1200}")
    private long processMemoryLimitMb;

    // Substrings seen in ebook-convert's own output when it dies from hitting
    // the memory limit above (or genuine host memory pressure) rather than a
    // problem with the file itself — Calibre doesn't report this as a distinct
    // exit code, so the only signal is these known OOM messages in its stdout.
    private static final List<String> MEMORY_FAILURE_MARKERS = List.of(
            "cannot allocate memory",
            "memoryerror",
            "out of memory",
            "pthread_create: resource temporarily unavailable",
            "resource temporarily unavailable"
    );

    @Override
    public Resource convertBook(MultipartFile file, String sourceFormat, String targetFormat) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }

        // Нормализуем форматы
        sourceFormat = normalizeFormat(sourceFormat);
        targetFormat = normalizeFormat(targetFormat);

        // Валидация форматов
        validateFormats(sourceFormat, targetFormat);

        // Если sourceFormat не указан, определяем по расширению
        if (sourceFormat == null || sourceFormat.isEmpty()) {
            sourceFormat = getFileExtension(file.getOriginalFilename()).toLowerCase();
            if (!SUPPORTED_SOURCE_FORMATS.contains(sourceFormat)) {
                throw new IllegalArgumentException("Не удалось определить или неподдерживаемый исходный формат: " + sourceFormat);
            }
            log.info("Определён исходный формат по расширению: {}", sourceFormat);
        }

        Path inputFile = null;
        Path outputFile = null;

        try {
            // Создаём временные файлы с правильными расширениями
            inputFile = createInputFile(file, sourceFormat);
            outputFile = createOutputFile(targetFormat);

            // Записываем файл на диск
            file.transferTo(inputFile.toFile());

            log.info("Конвертация: {} -> {} ({} -> {})",
                    inputFile.getFileName(), outputFile.getFileName(), sourceFormat, targetFormat);

            // Запускаем ebook-convert
            List<String> command = List.of("ebook-convert", inputFile.toString(), outputFile.toString());
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ProcessUtils.withMemoryLimit(command, processMemoryLimitMb));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Считываем вывод команды
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // Full tool output (which can include Python tracebacks) is only ever
                // logged server-side, never returned to the client as-is.
                log.error("Ошибка конвертации (код: {}): {}", exitCode, output);
                if (isMemoryFailure(output.toString())) {
                    throw new RuntimeException("Не удалось конвертировать файл: серверу не хватило памяти " +
                            "для обработки файла такого размера или сложности. Попробуйте уменьшить размер файла " +
                            "(например, сжать изображения внутри него) или конвертировать его частями.");
                }
                throw new RuntimeException("Ошибка конвертации (код: " + exitCode + "): " + output);
            }

            log.info("Конвертация успешна: {} -> {}", sourceFormat, targetFormat);

            // Читаем результат
            byte[] fileContent = Files.readAllBytes(outputFile);
            return new ByteArrayResource(fileContent);

        } catch (IOException e) {
            log.error("Ошибка ввода-вывода при работе с файлами", e);
            throw new RuntimeException("Ошибка при сохранении или чтении файла: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Конвертация прервана", e);
            throw new RuntimeException("Процесс конвертации был прерван", e);
        } catch (RuntimeException e) {
            // Already a clean, user-facing message (built above) — re-throw as-is
            // instead of re-wrapping it in the generic "Неизвестная ошибка" below.
            throw e;
        } catch (Exception e) {
            log.error("Неизвестная ошибка при конвертации", e);
            throw new RuntimeException("Ошибка конвертации: " + e.getMessage(), e);
        } finally {
            // Гарантированная очистка временных файлов
            cleanupQuietly(inputFile);
            cleanupQuietly(outputFile);
        }
    }

    private String normalizeFormat(String format) {
        if (format == null) return null;
        return format.toLowerCase().replaceAll("^\\.", ""); // убираем точку, если есть
    }

    private void validateFormats(String sourceFormat, String targetFormat) {
        if (!SUPPORTED_SOURCE_FORMATS.contains(sourceFormat)) {
            throw new IllegalArgumentException("Неподдерживаемый исходный формат: " + sourceFormat +
                    ". Поддерживаемые: " + SUPPORTED_SOURCE_FORMATS);
        }
        if (!SUPPORTED_TARGET_FORMATS.contains(targetFormat)) {
            throw new IllegalArgumentException("Неподдерживаемый целевой формат: " + targetFormat +
                    ". Поддерживаемые: " + SUPPORTED_TARGET_FORMATS);
        }
        if (sourceFormat.equals(targetFormat)) {
            throw new IllegalArgumentException("Исходный и целевой форматы одинаковы: " + targetFormat);
        }
    }

    protected boolean isMemoryFailure(String processOutput) {
        String lower = processOutput.toLowerCase();
        return MEMORY_FAILURE_MARKERS.stream().anyMatch(lower::contains);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") < 0) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    private Path createInputFile(MultipartFile file, String sourceFormat) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String prefix = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(0, originalFilename.lastIndexOf("."))
                : "input";

        Path tempDirPath = Paths.get(tempDir);
        if (!Files.exists(tempDirPath)) {
            Files.createDirectories(tempDirPath);
        }

        return Files.createTempFile(tempDirPath, prefix + "-", "." + sourceFormat);
    }

    private Path createOutputFile(String targetFormat) throws IOException {
        Path tempDirPath = Paths.get(tempDir);
        if (!Files.exists(tempDirPath)) {
            Files.createDirectories(tempDirPath);
        }
        return Files.createTempFile(tempDirPath, "output-", "." + targetFormat);
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

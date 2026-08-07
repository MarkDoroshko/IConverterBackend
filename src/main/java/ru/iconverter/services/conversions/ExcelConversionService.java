package ru.iconverter.services.conversions;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ExcelConversionService implements IExcelConversionService {

    private static final Logger log = LoggerFactory.getLogger(ExcelConversionService.class);

    // Output formats LibreOffice Calc can write for our use cases.
    public static final Set<String> SUPPORTED_TARGET_FORMATS = Set.of("xlsx", "csv", "pdf");

    // Accepted input extensions for the LibreOffice path. PDF is intentionally
    // NOT here — LibreOffice can't turn a PDF back into a spreadsheet (Draw
    // can't export to Calc); see fromPdf() for the separate text-extraction path.
    public static final Set<String> SUPPORTED_SOURCE_FORMATS = Set.of("xlsx", "xls", "csv");

    // Targets fromPdf() can produce.
    public static final Set<String> SUPPORTED_PDF_TARGET_FORMATS = Set.of("xlsx", "csv");

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
            workDir = Files.createTempDirectory(Paths.get(tempDir), "excel-");
            profileDir = Files.createTempDirectory(Paths.get(tempDir), "lo-profile-");
            Path input = workDir.resolve("input." + source);
            file.transferTo(input.toFile());

            List<String> command = buildCommand(target, workDir.toString(), profileDir.toString(), input.toString());
            log.info("Excel convert {} → {} ({} bytes)", source, target, file.getSize());
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
                throw new RuntimeException("Конвертация таблицы превысила лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("LibreOffice failed (exit {}): {}", process.exitValue(), output);
                throw new RuntimeException("Не удалось конвертировать таблицу");
            }

            Path produced = findOutput(workDir, target);
            byte[] bytes = Files.readAllBytes(produced);
            log.info("Excel convert done: {} → {} bytes", source, bytes.length);
            return new ByteArrayResource(bytes);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке таблицы: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Конвертация прервана", e);
        } finally {
            deleteDirQuietly(workDir);
            deleteDirQuietly(profileDir);
        }
    }

    @Override
    public Resource fromPdf(MultipartFile file, String targetFormat) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String target = normalize(targetFormat);
        if (!SUPPORTED_PDF_TARGET_FORMATS.contains(target)) {
            throw new IllegalArgumentException("Unsupported target format for PDF source: " + target
                    + ". Supported: " + SUPPORTED_PDF_TARGET_FORMATS);
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory(Paths.get(tempDir), "pdf2excel-");
            Path input = workDir.resolve("input.pdf");
            file.transferTo(input.toFile());
            Path textOutput = workDir.resolve("output.txt");

            List<String> command = buildPdftotextCommand(input.toString(), textOutput.toString());
            log.info("PDF→{} ({} bytes)", target, file.getSize());
            log.debug("pdftotext command: {}", command);

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
                throw new RuntimeException("Извлечение текста из PDF превысило лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("pdftotext failed (exit {}): {}", process.exitValue(), output);
                throw new RuntimeException("Не удалось извлечь текст из PDF");
            }

            List<String> lines = Files.readAllLines(textOutput, StandardCharsets.UTF_8);
            List<List<String>> rows = parseRows(lines);
            if (rows.isEmpty()) {
                throw new RuntimeException("В PDF не найден текст для извлечения. "
                        + "Возможно, это отсканированный документ без текстового слоя — попробуйте OCR.");
            }

            byte[] bytes = "csv".equals(target) ? rowsToCsv(rows) : rowsToXlsx(rows);
            log.info("PDF→{} done: {} → {} bytes", target, file.getSize(), bytes.length);
            return new ByteArrayResource(bytes);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке PDF: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Конвертация прервана", e);
        } finally {
            deleteDirQuietly(workDir);
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
    // profile lock that breaks concurrent headless soffice invocations. CSV
    // import/export uses LibreOffice's default dialect (comma-separated, UTF-8).
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

    static List<String> buildPdftotextCommand(String input, String output) {
        // -layout preserves the original spacing so column boundaries survive
        // as runs of whitespace — that's what splitColumns() relies on.
        return List.of("pdftotext", "-layout", input, output);
    }

    // Splits a line into columns on runs of 2+ whitespace characters, since
    // -layout uses single spaces within a column's text but pads gaps between
    // columns with several spaces. This is a heuristic: it works for simple,
    // consistently-spaced tables and will misfire on multi-line cells, merged
    // cells, or layouts pdftotext otherwise struggles to preserve.
    static List<String> splitColumns(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) return List.of();
        return Arrays.stream(trimmed.split("\\s{2,}"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // Blank lines (paragraph/page gaps) are dropped rather than kept as empty
    // rows, to avoid a sparse, mostly-empty spreadsheet.
    static List<List<String>> parseRows(List<String> lines) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            List<String> cols = splitColumns(line);
            if (!cols.isEmpty()) rows.add(cols);
        }
        return rows;
    }

    static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private byte[] rowsToCsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> cols : rows) {
            sb.append(cols.stream().map(ExcelConversionService::csvEscape).collect(Collectors.joining(",")));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] rowsToXlsx(List<List<String>> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                List<String> cols = rows.get(r);
                for (int c = 0; c < cols.size(); c++) {
                    row.createCell(c).setCellValue(cols.get(c));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
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

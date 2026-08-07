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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    static final Set<String> OCR_TARGET_FORMATS = Set.of("txt", "pdf");

    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    @Override
    public Resource merge(List<MultipartFile> files) {
        if (files == null || files.size() < 2) {
            throw new IllegalArgumentException("Загрузите минимум два PDF-файла.");
        }
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        try {
            for (MultipartFile f : files) {
                if (f.isEmpty()) {
                    throw new IllegalArgumentException("Один из загруженных файлов пустой.");
                }
                Path in = createTempFile("pdf-merge-in-", ".pdf");
                copyToFile(f, in);
                inputs.add(in);
            }
            output = createTempFile("pdf-merge-out-", ".pdf");

            List<String> command = buildMergeCommand(
                    inputs.stream().map(Path::toString).toList(), output.toString());
            log.info("Merge {} PDFs", files.size());
            log.debug("Ghostscript command: {}", command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Объединение PDF превысило лимит времени");
            }
            if (process.exitValue() != 0) {
                log.error("Ghostscript merge failed (exit {}): {}", process.exitValue(), out);
                throw new RuntimeException("Не удалось объединить PDF. Проверьте, что все файлы — корректные PDF.");
            }

            byte[] result = Files.readAllBytes(output);
            log.info("Merged {} PDFs → {} bytes", files.size(), result.length);
            return new ByteArrayResource(result);

        } catch (IOException e) {
            log.error("PDF merge I/O error", e);
            throw new RuntimeException("Ошибка при объединении PDF: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Объединение PDF прервано", e);
        } finally {
            for (Path p : inputs) cleanupQuietly(p);
            cleanupQuietly(output);
        }
    }

    // gs -dBATCH -dNOPAUSE -q -sDEVICE=pdfwrite -sOutputFile=out in1 in2 …
    static List<String> buildMergeCommand(List<String> inputs, String output) {
        List<String> cmd = new ArrayList<>(List.of(
                "gs", "-dBATCH", "-dNOPAUSE", "-q",
                "-sDEVICE=pdfwrite", "-dCompatibilityLevel=1.4",
                "-sOutputFile=" + output));
        cmd.addAll(inputs);
        return cmd;
    }

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
            copyToFile(file, inputFile);

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
            copyToFile(file, input);

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
            copyToFile(file, inputFile);
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

    @Override
    public Resource ocr(MultipartFile file, String targetFormat) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String target = resolveOcrTarget(targetFormat);

        Path inputFile = null;
        Path pagesDir = null;
        try {
            inputFile = createTempFile("pdf-ocr-in-", ".pdf");
            copyToFile(file, inputFile);
            pagesDir = Files.createTempDirectory(Paths.get(tempDir), "pdf-ocr-pages-");
            String outPrefix = pagesDir.resolve("page").toString();

            List<String> rasterCommand = buildOcrRasterCommand(inputFile.toString(), outPrefix);
            log.info("PDF OCR raster ({} bytes)", file.getSize());
            runProcess(rasterCommand, 120, "Рендеринг PDF для OCR превысил лимит времени",
                    "Не удалось подготовить страницы PDF для OCR");

            List<Path> pages;
            try (Stream<Path> files = Files.list(pagesDir)) {
                pages = files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jpg"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            }
            if (pages.isEmpty()) {
                throw new RuntimeException("PDF не содержит страниц для распознавания");
            }

            byte[] result = "txt".equals(target) ? ocrToText(pages) : ocrToSearchablePdf(pages, pagesDir);
            log.info("PDF OCR done: {} pages → {} bytes", pages.size(), result.length);
            return new ByteArrayResource(result);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при распознавании PDF: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OCR прерван", e);
        } finally {
            cleanupQuietly(inputFile);
            deleteDirQuietly(pagesDir);
        }
    }

    private byte[] ocrToText(List<Path> pages) throws IOException, InterruptedException {
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            Path page = pages.get(i);
            String outputBase = stripExtension(page.toString());
            runProcess(buildTesseractCommand(page.toString(), outputBase, "txt"), 60,
                    "Распознавание страницы превысило лимит времени", "Не удалось распознать текст на странице");
            String pageText = Files.readString(Paths.get(outputBase + ".txt"), StandardCharsets.UTF_8);
            if (i > 0) combined.append("\n\f\n");
            combined.append(pageText.strip());
        }
        return combined.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] ocrToSearchablePdf(List<Path> pages, Path pagesDir) throws IOException, InterruptedException {
        List<String> pagePdfs = new ArrayList<>();
        for (Path page : pages) {
            String outputBase = stripExtension(page.toString());
            runProcess(buildTesseractCommand(page.toString(), outputBase, "pdf"), 60,
                    "Распознавание страницы превысило лимит времени", "Не удалось распознать текст на странице");
            pagePdfs.add(outputBase + ".pdf");
        }
        if (pagePdfs.size() == 1) {
            return Files.readAllBytes(Paths.get(pagePdfs.get(0)));
        }
        Path merged = pagesDir.resolve("merged.pdf");
        runProcess(buildMergeCommand(pagePdfs, merged.toString()), 60,
                "Объединение распознанных страниц превысило лимит времени", "Не удалось объединить распознанные страницы");
        return Files.readAllBytes(merged);
    }

    private void runProcess(List<String> command, int timeoutSeconds, String timeoutMessage, String failureMessage)
            throws IOException, InterruptedException {
        log.debug("Running command: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new RuntimeException(timeoutMessage);
        }
        if (process.exitValue() != 0) {
            log.error("Command failed (exit {}): {}", process.exitValue(), out);
            throw new RuntimeException(failureMessage);
        }
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────

    static String resolveOcrTarget(String targetFormat) {
        String target = targetFormat == null ? "txt" : targetFormat.trim().toLowerCase();
        if (!OCR_TARGET_FORMATS.contains(target)) {
            throw new IllegalArgumentException("Unsupported OCR target format: " + targetFormat
                    + ". Supported: " + OCR_TARGET_FORMATS);
        }
        return target;
    }

    static String stripExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    // -jpeg + -r 300 for a resolution Tesseract recognizes reliably. pdftoppm
    // pads page numbers to a consistent width based on the total page count,
    // so lexicographic filename sorting matches page order.
    static List<String> buildOcrRasterCommand(String input, String outPrefix) {
        return List.of("pdftoppm", "-jpeg", "-r", "300", input, outPrefix);
    }

    // configType is "txt" (plain text output) or "pdf" (searchable PDF: the
    // original page image with an invisible OCR text layer).
    static List<String> buildTesseractCommand(String input, String outputBase, String configType) {
        return List.of("tesseract", input, outputBase, "-l", "rus+eng", configType);
    }

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

    // Reliable write of the upload to a pre-created temp file. file.transferTo()
    // can no-op when the destination already exists (Tomcat move semantics), so
    // copy the stream explicitly with REPLACE_EXISTING.
    private void copyToFile(MultipartFile file, Path dest) throws IOException {
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
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

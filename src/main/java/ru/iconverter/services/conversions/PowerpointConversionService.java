package ru.iconverter.services.conversions;

import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class PowerpointConversionService implements IPowerpointConversionService {

    private static final Logger log = LoggerFactory.getLogger(PowerpointConversionService.class);

    public static final Set<String> SUPPORTED_PRESENTATION_FORMATS = Set.of("pptx", "ppt");

    // Images POI can embed directly without a re-encode step.
    private static final Map<String, PictureType> EXTENSION_TO_PICTURE_TYPE = Map.of(
            "jpg", PictureType.JPEG,
            "jpeg", PictureType.JPEG,
            "png", PictureType.PNG,
            "gif", PictureType.GIF,
            "bmp", PictureType.BMP
    );

    // Standard widescreen (16:9) slide size, in points.
    private static final Dimension SLIDE_SIZE = new Dimension(960, 540);

    @Value("${app.temp-dir:/tmp}")
    private String tempDir;

    @Override
    public Resource toPdf(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String source = normalize(getExtension(file.getOriginalFilename()));
        validatePresentation(source);

        Path workDir = null;
        Path profileDir = null;
        try {
            workDir = Files.createTempDirectory(Paths.get(tempDir), "pptx-");
            profileDir = Files.createTempDirectory(Paths.get(tempDir), "lo-profile-");
            Path input = workDir.resolve("input." + source);
            file.transferTo(input.toFile());

            runSoffice(input, workDir, profileDir, "pdf");
            Path produced = findOutput(workDir, "pdf");
            byte[] bytes = Files.readAllBytes(produced);
            log.info("PPTX→PDF: {} → {} bytes", file.getSize(), bytes.length);
            return new ByteArrayResource(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке презентации: " + e.getMessage(), e);
        } finally {
            deleteDirQuietly(workDir);
            deleteDirQuietly(profileDir);
        }
    }

    @Override
    public Resource toImages(MultipartFile file, Integer dpi) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String source = normalize(getExtension(file.getOriginalFilename()));
        validatePresentation(source);
        int resolution = PdfConversionService.resolveDpi(dpi);

        Path workDir = null;
        Path profileDir = null;
        Path pagesDir = null;
        try {
            workDir = Files.createTempDirectory(Paths.get(tempDir), "pptx-");
            profileDir = Files.createTempDirectory(Paths.get(tempDir), "lo-profile-");
            Path input = workDir.resolve("input." + source);
            file.transferTo(input.toFile());

            runSoffice(input, workDir, profileDir, "pdf");
            Path pdf = findOutput(workDir, "pdf");

            pagesDir = Files.createTempDirectory(Paths.get(tempDir), "pptx2jpg-out-");
            String outPattern = pagesDir.resolve("slide-%03d.jpg").toString();
            List<String> command = PdfConversionService.buildToImagesCommand(pdf.toString(), outPattern, resolution);
            log.info("PPTX→images ({} bytes) dpi={}", file.getSize(), resolution);
            runProcess(command, 60, "Рендеринг презентации превысил лимит времени",
                    "Не удалось преобразовать презентацию в изображения");

            byte[] zip = zipDirectory(pagesDir);
            log.info("PPTX→images: zipped slides → {} bytes", zip.length);
            return new ByteArrayResource(zip);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке презентации: " + e.getMessage(), e);
        } finally {
            deleteDirQuietly(workDir);
            deleteDirQuietly(profileDir);
            deleteDirQuietly(pagesDir);
        }
    }

    @Override
    public Resource fromImage(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String ext = normalize(getExtension(file.getOriginalFilename()));
        PictureType pictureType = EXTENSION_TO_PICTURE_TYPE.get(ext);
        if (pictureType == null) {
            throw new IllegalArgumentException("Unsupported image format: " + ext
                    + ". Supported: " + EXTENSION_TO_PICTURE_TYPE.keySet());
        }

        try {
            byte[] imageBytes = file.getBytes();
            try (XMLSlideShow ppt = new XMLSlideShow()) {
                ppt.setPageSize(SLIDE_SIZE);
                addImageSlide(ppt, imageBytes, pictureType);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ppt.write(out);
                log.info("Image→PPTX: {} → {} bytes", file.getSize(), out.size());
                return new ByteArrayResource(out.toByteArray());
            }
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при создании презентации: " + e.getMessage(), e);
        }
    }

    @Override
    public Resource fromPdf(MultipartFile file, Integer dpi) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Загруженный файл пустой.");
        }
        String source = normalize(getExtension(file.getOriginalFilename()));
        if (!"pdf".equals(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source + ". Supported: pdf");
        }
        int resolution = PdfConversionService.resolveDpi(dpi);

        Path workDir = null;
        Path pagesDir = null;
        try {
            workDir = Files.createTempDirectory(Paths.get(tempDir), "pdf2pptx-");
            Path input = workDir.resolve("input.pdf");
            file.transferTo(input.toFile());

            pagesDir = Files.createTempDirectory(Paths.get(tempDir), "pdf2pptx-pages-");
            String outPattern = pagesDir.resolve("page-%03d.jpg").toString();
            List<String> command = PdfConversionService.buildToImagesCommand(input.toString(), outPattern, resolution);
            log.info("PDF→PPTX ({} bytes) dpi={}", file.getSize(), resolution);
            runProcess(command, 60, "Рендеринг PDF превысил лимит времени",
                    "Не удалось преобразовать PDF в изображения");

            List<Path> pages;
            try (Stream<Path> files = Files.list(pagesDir)) {
                pages = files.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            }
            if (pages.isEmpty()) {
                throw new RuntimeException("PDF не содержит страниц для рендеринга");
            }

            try (XMLSlideShow ppt = new XMLSlideShow()) {
                ppt.setPageSize(SLIDE_SIZE);
                for (Path page : pages) {
                    addImageSlide(ppt, Files.readAllBytes(page), PictureType.JPEG);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ppt.write(out);
                log.info("PDF→PPTX: {} pages → {} bytes", pages.size(), out.size());
                return new ByteArrayResource(out.toByteArray());
            }
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при обработке PDF: " + e.getMessage(), e);
        } finally {
            deleteDirQuietly(workDir);
            deleteDirQuietly(pagesDir);
        }
    }

    private void addImageSlide(XMLSlideShow ppt, byte[] imageBytes, PictureType pictureType) throws IOException {
        Dimension pixelSize = readPixelSize(imageBytes);
        XSLFSlide slide = ppt.createSlide();
        XSLFPictureData pictureData = ppt.addPicture(imageBytes, pictureType);
        XSLFPictureShape picture = slide.createPicture(pictureData);
        picture.setAnchor(fitToSlide(pixelSize));
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

    static void validatePresentation(String source) {
        if (!SUPPORTED_PRESENTATION_FORMATS.contains(source)) {
            throw new IllegalArgumentException("Unsupported source format: " + source
                    + ". Supported: " + SUPPORTED_PRESENTATION_FORMATS);
        }
    }

    // Assumes a standard 96 DPI source image (pixels → points at 72 dpi).
    static Rectangle2D fitToSlide(Dimension pixelSize) {
        double widthPt = pixelSize.getWidth() * 72.0 / 96.0;
        double heightPt = pixelSize.getHeight() * 72.0 / 96.0;
        double scale = Math.min(SLIDE_SIZE.getWidth() / widthPt, SLIDE_SIZE.getHeight() / heightPt);
        double w = widthPt * scale;
        double h = heightPt * scale;
        double x = (SLIDE_SIZE.getWidth() - w) / 2;
        double y = (SLIDE_SIZE.getHeight() - h) / 2;
        return new Rectangle2D.Double(x, y, w, h);
    }

    private Dimension readPixelSize(byte[] imageBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IllegalArgumentException("Не удалось прочитать изображение.");
        }
        return new Dimension(image.getWidth(), image.getHeight());
    }

    // A unique per-call user profile (-env:UserInstallation) avoids the shared
    // profile lock that breaks concurrent headless soffice invocations.
    private void runSoffice(Path input, Path outDir, Path profileDir, String target) throws IOException {
        List<String> command = List.of(
                "soffice",
                "--headless",
                "--norestore",
                "--nolockcheck",
                "-env:UserInstallation=file://" + profileDir,
                "--convert-to", target,
                "--outdir", outDir.toString(),
                input.toString()
        );
        runProcess(command, 120, "Конвертация презентации превысила лимит времени",
                "Не удалось конвертировать презентацию");
    }

    private void runProcess(List<String> command, int timeoutSeconds, String timeoutMessage, String failureMessage)
            throws IOException {
        log.debug("Running command: {}", command);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException(timeoutMessage);
            }
            if (process.exitValue() != 0) {
                log.error("Command failed (exit {}): {}", process.exitValue(), output);
                throw new RuntimeException(failureMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Конвертация прервана", e);
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

    // Zip every regular file in `dir` (flat, sorted by name) into a byte[].
    private byte[] zipDirectory(Path dir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos);
             Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            if (sorted.isEmpty()) {
                throw new IOException("Презентация не содержит слайдов для рендеринга");
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

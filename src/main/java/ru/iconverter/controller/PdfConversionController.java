package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.services.conversions.IPdfConversionService;

import java.util.List;

@RestController
@RequestMapping("/api/convert/pdf")
public class PdfConversionController {

    private static final Logger log = LoggerFactory.getLogger(PdfConversionController.class);

    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;

    private final IPdfConversionService pdfConversionService;

    public PdfConversionController(IPdfConversionService pdfConversionService) {
        this.pdfConversionService = pdfConversionService;
    }

    @PostMapping(value = "/merge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> merge(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.size() < 2) {
            return badRequest("Upload at least two PDF files");
        }
        for (MultipartFile f : files) {
            if (f.isEmpty()) return badRequest("One of the uploaded files is empty");
            if (f.getSize() > MAX_FILE_SIZE) {
                log.warn("PDF exceeds limit: {} bytes", f.getSize());
                return badRequest("Each file must not exceed 25 MB");
            }
        }

        var merged = pdfConversionService.merge(files);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=merged.pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(merged);
    }

    @PostMapping(value = "/compress", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> compress(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "level", defaultValue = "ebook") String level) {

        if (file.isEmpty()) {
            return badRequest("Uploaded file is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("PDF exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 25 MB");
        }

        // Validation errors (bad level) → 400 via GlobalExceptionHandler.
        var compressed = pdfConversionService.compress(file, level);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=compressed.pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(compressed);
    }

    @PostMapping(value = "/from-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> fromImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) return badRequest("File size must not exceed 25 MB");

        var pdf = pdfConversionService.fromImage(file);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted.pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping(value = "/to-jpg", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> toJpg(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dpi", required = false) Integer dpi) {
        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) return badRequest("File size must not exceed 25 MB");

        var zip = pdfConversionService.toImages(file, dpi);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=pages.zip");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.valueOf("application/zip"))
                .body(zip);
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + message + "\"}");
    }
}

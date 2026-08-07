package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.services.conversions.IPowerpointConversionService;

@RestController
@RequestMapping("/api/convert/powerpoint")
public class PowerpointConversionController {

    private static final Logger log = LoggerFactory.getLogger(PowerpointConversionController.class);

    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;

    private final IPowerpointConversionService powerpointConversionService;

    public PowerpointConversionController(IPowerpointConversionService powerpointConversionService) {
        this.powerpointConversionService = powerpointConversionService;
    }

    @PostMapping(value = "/to-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> toPdf(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Presentation exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 25 MB");
        }

        Resource pdf = powerpointConversionService.toPdf(file);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted.pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping(value = "/to-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> toImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dpi", required = false) Integer dpi) {
        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) return badRequest("File size must not exceed 25 MB");

        Resource zip = powerpointConversionService.toImages(file, dpi);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=slides.zip");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.valueOf("application/zip"))
                .body(zip);
    }

    @PostMapping(value = "/from-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> fromImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) return badRequest("File size must not exceed 25 MB");

        Resource pptx = powerpointConversionService.fromImage(file);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted.pptx");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(pptx);
    }

    @PostMapping(value = "/from-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> fromPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dpi", required = false) Integer dpi) {
        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) return badRequest("File size must not exceed 25 MB");

        Resource pptx = powerpointConversionService.fromPdf(file, dpi);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted.pptx");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(pptx);
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + message + "\"}");
    }
}

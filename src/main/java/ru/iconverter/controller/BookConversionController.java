package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.services.conversions.IBookConversionService;
import ru.iconverter.services.IStringService;

@RestController
@RequestMapping("/api/convert/ebook")
public class BookConversionController {

    private static final Logger log = LoggerFactory.getLogger(BookConversionController.class);

    private final IBookConversionService bookConversionService;
    private final IStringService stringService;

    public BookConversionController(IBookConversionService bookConversionService,
                                    IStringService stringService) {
        this.bookConversionService = bookConversionService;
        this.stringService = stringService;
    }

    @PostMapping(value = "/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convertBook(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String targetFormat) {

        long MAX_FILE_SIZE = 25 * 1024 * 1024; // 50 MB
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("File size exceeds limit: {} bytes (max: {})", file.getSize(), MAX_FILE_SIZE);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\": \"File size must not exceed 25 MB\"}");
        }

        log.info("Convert book {} to {}", file.getOriginalFilename(), targetFormat);

        var sourceFormat = stringService.getFileExtension(file.getOriginalFilename());
        var converted = bookConversionService.convertBook(file, sourceFormat, targetFormat);

        log.info("Convert created.");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted_book." + targetFormat);
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try {
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(converted.contentLength())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(converted);
        } catch (Exception e) {
            log.error("Не удалось создать ResponseEntity.", e);
            throw new RuntimeException(e);
        }
    }
}

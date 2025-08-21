package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
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
    public ResponseEntity<Resource> convertBook(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String targetFormat) {

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
            log.info("Не удалось создать ResponseEntity.");
            throw new RuntimeException(e);
        }
    }
}

package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import ru.iconverter.services.IStringService;
import ru.iconverter.services.conversions.IBookConversionService;
import ru.iconverter.services.conversions.IOfficeConversionService;

@RestController
@RequestMapping("/api/convert/office")
public class OfficeConversionController {

    private static final Logger log = LoggerFactory.getLogger(OfficeConversionController.class);

    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;

    private final IOfficeConversionService officeConversionService;
    private final IBookConversionService bookConversionService;
    private final IStringService stringService;

    public OfficeConversionController(IOfficeConversionService officeConversionService,
                                      IBookConversionService bookConversionService,
                                      IStringService stringService) {
        this.officeConversionService = officeConversionService;
        this.bookConversionService = bookConversionService;
        this.stringService = stringService;
    }

    @PostMapping(value = "/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String targetFormat) {

        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Document exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 25 MB");
        }

        // PDF as a SOURCE goes to Calibre: LibreOffice opens PDFs in Draw and
        // cannot export them to Writer formats (docx/odt/...), producing no file.
        // Calibre's ebook-convert extracts the PDF text and writes a real docx.
        String source = stringService.getFileExtension(file.getOriginalFilename()).toLowerCase();
        Resource converted = "pdf".equals(source)
                ? bookConversionService.convertBook(file, "pdf", targetFormat)
                : officeConversionService.convert(file, targetFormat);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=converted." + targetFormat.toLowerCase());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(converted);
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + message + "\"}");
    }
}

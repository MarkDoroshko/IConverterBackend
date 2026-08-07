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
import ru.iconverter.services.IStringService;
import ru.iconverter.services.conversions.IExcelConversionService;

@RestController
@RequestMapping("/api/convert/excel")
public class ExcelConversionController {

    private static final Logger log = LoggerFactory.getLogger(ExcelConversionController.class);

    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;

    private final IExcelConversionService excelConversionService;
    private final IStringService stringService;

    public ExcelConversionController(IExcelConversionService excelConversionService, IStringService stringService) {
        this.excelConversionService = excelConversionService;
        this.stringService = stringService;
    }

    @PostMapping(value = "/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String targetFormat) {

        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Spreadsheet exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 25 MB");
        }

        // PDF as a SOURCE can't go through LibreOffice (Draw can't export to
        // Calc), so it's routed to a best-effort pdftotext+POI text extraction
        // path instead. See ExcelConversionService.fromPdf().
        String source = stringService.getFileExtension(file.getOriginalFilename()).toLowerCase();
        Resource converted = "pdf".equals(source)
                ? excelConversionService.fromPdf(file, targetFormat)
                : excelConversionService.convert(file, targetFormat);

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

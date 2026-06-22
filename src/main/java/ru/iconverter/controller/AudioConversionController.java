package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.services.conversions.IAudioConversionService;

import java.util.Map;

@RestController
@RequestMapping("/api/convert/audio")
public class AudioConversionController {

    private static final Logger log = LoggerFactory.getLogger(AudioConversionController.class);

    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;

    private static final Map<String, String> MIME = Map.of(
            "mp3", "audio/mpeg", "wav", "audio/wav", "aac", "audio/aac",
            "m4a", "audio/mp4", "ogg", "audio/ogg", "flac", "audio/flac");

    private final IAudioConversionService audioConversionService;

    public AudioConversionController(IAudioConversionService audioConversionService) {
        this.audioConversionService = audioConversionService;
    }

    @PostMapping(value = "/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String targetFormat) {

        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Audio exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 25 MB");
        }

        var converted = audioConversionService.convert(file, targetFormat);

        String ext = targetFormat == null ? "" : targetFormat.trim().toLowerCase();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted." + ext);
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(MIME.getOrDefault(ext, "application/octet-stream")))
                .body(converted);
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + message + "\"}");
    }
}

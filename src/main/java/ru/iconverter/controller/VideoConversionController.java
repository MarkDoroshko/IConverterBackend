package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.services.conversions.IVideoConversionService;

import java.util.Map;

@RestController
@RequestMapping("/api/convert/video")
public class VideoConversionController {

    private static final Logger log = LoggerFactory.getLogger(VideoConversionController.class);

    // Matches the framework-wide multipart cap (spring.servlet.multipart.max-file-size).
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    private static final Map<String, String> MIME = Map.of(
            "mp4", "video/mp4", "avi", "video/x-msvideo", "mov", "video/quicktime",
            "mkv", "video/x-matroska", "webm", "video/webm");

    private final IVideoConversionService videoConversionService;

    public VideoConversionController(IVideoConversionService videoConversionService) {
        this.videoConversionService = videoConversionService;
    }

    @PostMapping(value = "/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String targetFormat) {

        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Video exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 50 MB");
        }

        var converted = videoConversionService.convert(file, targetFormat);
        return fileResponse(converted, targetFormat);
    }

    @PostMapping(value = "/resize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> resize(
            @RequestParam("file") MultipartFile file,
            @RequestParam("resolution") String resolution) {

        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Video exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 50 MB");
        }

        var resized = videoConversionService.resize(file, resolution);
        return fileResponse(resized, extensionOf(file.getOriginalFilename()));
    }

    @PostMapping(value = "/trim", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> trim(
            @RequestParam("file") MultipartFile file,
            @RequestParam("startTime") String startTime,
            @RequestParam("endTime") String endTime) {

        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Video exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 50 MB");
        }

        var trimmed = videoConversionService.trim(file, startTime, endTime);
        return fileResponse(trimmed, extensionOf(file.getOriginalFilename()));
    }

    @PostMapping(value = "/gif", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> gif(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startTime", defaultValue = "0") String startTime,
            @RequestParam("duration") String duration,
            @RequestParam(value = "fps", required = false) Integer fps,
            @RequestParam(value = "width", required = false) Integer width) {

        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Video exceeds limit: {} bytes", file.getSize());
            return badRequest("File size must not exceed 50 MB");
        }

        var gif = videoConversionService.toGif(file, startTime, duration, fps, width);
        return fileResponse(gif, "gif");
    }

    private ResponseEntity<?> fileResponse(Object body, String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=converted." + ext);
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(MIME.getOrDefault(ext, "application/octet-stream")))
                .body(body);
    }

    private String extensionOf(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) return "";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + message + "\"}");
    }
}

package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.services.IMediaTypeService;
import ru.iconverter.services.conversions.IImagesConversionService;

import java.io.IOException;

@RestController
@RequestMapping("/api/convert/images")
public class ImagesConversionController {

    private static final Logger log = LoggerFactory.getLogger(ImagesConversionController.class);

    // Match the ebook limit and the 25 MB promise shown in the UI.
    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;

    private final IImagesConversionService imagesConversionService;
    private final IMediaTypeService mediaTypeService;

    public ImagesConversionController(IImagesConversionService imagesConversionService,
                                      IMediaTypeService mediaTypeService) {
        this.imagesConversionService = imagesConversionService;
        this.mediaTypeService = mediaTypeService;
    }

    @PostMapping(value = "/")
    public ResponseEntity<?> convertImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String format,
            @RequestParam(value = "quality", required = false) Integer quality,
            @RequestParam(value = "maxSize", required = false) Integer maxSize) throws IOException {

        if (file.isEmpty()) {
            return badRequest("Uploaded file is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Image exceeds limit: {} bytes (max {})", file.getSize(), MAX_FILE_SIZE);
            return badRequest("File size must not exceed 25 MB");
        }

        // Validates the target format (throws IllegalArgumentException → 400 via advice).
        var mediaType = mediaTypeService.getMediaType(format);
        var resource = imagesConversionService.convertImage(file, format, quality, maxSize);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .body((Resource) resource);
    }

    @PostMapping(value = "/resize")
    public ResponseEntity<?> resizeImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "width", required = false) Integer width,
            @RequestParam(value = "height", required = false) Integer height,
            @RequestParam(value = "mode", required = false) String mode) throws IOException {

        ResponseEntity<?> bad = validate(file);
        if (bad != null) return bad;

        var resource = imagesConversionService.resize(file, width, height, mode);
        return ResponseEntity.ok()
                .contentType(mediaTypeFor(file))
                .body((Resource) resource);
    }

    @PostMapping(value = "/crop")
    public ResponseEntity<?> cropImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("width") int width,
            @RequestParam("height") int height,
            @RequestParam(value = "gravity", required = false) String gravity) throws IOException {

        ResponseEntity<?> bad = validate(file);
        if (bad != null) return bad;

        var resource = imagesConversionService.crop(file, width, height, gravity);
        return ResponseEntity.ok()
                .contentType(mediaTypeFor(file))
                .body((Resource) resource);
    }

    // Output keeps the input format; derive content type from the file extension.
    private MediaType mediaTypeFor(MultipartFile file) {
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return mediaTypeService.getMediaType(ext);
    }

    private ResponseEntity<?> validate(MultipartFile file) {
        if (file.isEmpty()) return badRequest("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Image exceeds limit: {} bytes (max {})", file.getSize(), MAX_FILE_SIZE);
            return badRequest("File size must not exceed 25 MB");
        }
        return null;
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + message + "\"}");
    }
}

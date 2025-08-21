package ru.iconverter.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.iconverter.services.IMediaTypeService;
import ru.iconverter.services.conversions.IImagesConversionService;

import java.io.IOException;

@RestController
@RequestMapping("/api/convert/images")
public class ImagesConversionController {

    private final IImagesConversionService imagesConversionService;
    private final IMediaTypeService mediaTypeService;

    public ImagesConversionController(IImagesConversionService imagesConversionService,
                                      IMediaTypeService mediaTypeService) {
        this.imagesConversionService = imagesConversionService;
        this.mediaTypeService = mediaTypeService;
    }

    @PostMapping(value = "/")
    public ResponseEntity<Resource> convertImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetFormat") String format) throws IOException {

        var mediaType = mediaTypeService.getMediaType(format);
        var resource = imagesConversionService.convertImage(file, format);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(resource);
    }
}

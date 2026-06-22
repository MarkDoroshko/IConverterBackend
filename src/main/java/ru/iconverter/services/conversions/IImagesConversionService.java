package ru.iconverter.services.conversions;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface IImagesConversionService {

    ByteArrayResource convertImage(MultipartFile file, String format) throws IOException;

    ByteArrayResource convertImage(MultipartFile file, String format,
                                   Integer quality, Integer maxSize) throws IOException;

    // Resize keeping the original format. mode: "fit" (default, keep aspect),
    // "exact" (stretch to WxH), "percent" (width = percentage).
    ByteArrayResource resize(MultipartFile file, Integer width, Integer height, String mode) throws IOException;

    // Crop to exactly width×height using a cover strategy (scale to fill, then
    // crop the overflow), anchored by gravity (center/north/…).
    ByteArrayResource crop(MultipartFile file, int width, int height, String gravity) throws IOException;
}

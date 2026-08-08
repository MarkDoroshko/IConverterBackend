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

    // Multi-resolution .ico favicon. sizes: comma-separated pixel sizes (e.g. "16,32,48"),
    // null/blank falls back to a sensible default set.
    ByteArrayResource favicon(MultipartFile file, String sizes) throws IOException;

    // Apply a named filter (grayscale, sepia, negate, blur, sharpen) keeping the original format.
    ByteArrayResource filter(MultipartFile file, String filter) throws IOException;

    // Overlay a watermark image or text onto file, keeping the original format.
    // Exactly one of watermarkImage/text must be provided.
    ByteArrayResource watermark(MultipartFile file, MultipartFile watermarkImage, String text,
                               String gravity, Integer opacity, Integer fontSize) throws IOException;
}

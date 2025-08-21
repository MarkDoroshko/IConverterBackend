package ru.iconverter.utils;

import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

public class MediaTypeUtils {

    private static final Map<String, MediaType> EXTENSION_TO_MEDIA_TYPE = new HashMap<>();

    static {
        EXTENSION_TO_MEDIA_TYPE.put("jpg", MediaType.IMAGE_JPEG);
        EXTENSION_TO_MEDIA_TYPE.put("jpeg", MediaType.IMAGE_JPEG);
        EXTENSION_TO_MEDIA_TYPE.put("png", MediaType.IMAGE_PNG);
        EXTENSION_TO_MEDIA_TYPE.put("gif", MediaType.IMAGE_GIF);
        EXTENSION_TO_MEDIA_TYPE.put("tiff", MediaType.valueOf("image/tiff"));
        EXTENSION_TO_MEDIA_TYPE.put("bmp", MediaType.valueOf("image/bmp"));
        EXTENSION_TO_MEDIA_TYPE.put("webp", MediaType.valueOf("image/webp"));
        EXTENSION_TO_MEDIA_TYPE.put("svg", MediaType.valueOf("image/svg+xml"));
        EXTENSION_TO_MEDIA_TYPE.put("pdf", MediaType.APPLICATION_PDF);
        // Добавьте другие форматы по мере необходимости
    }

    /**
     * Определяет MediaType на основе расширения файла.
     *
     * @param format расширение файла (например, "jpg", "png").
     * @return соответствующий MediaType или APPLICATION_OCTET_STREAM, если формат неизвестен.
     */
    public static MediaType getMediaTypeForFormat(String format) {
        return EXTENSION_TO_MEDIA_TYPE.getOrDefault(format.toLowerCase(), MediaType.APPLICATION_OCTET_STREAM);
    }
}

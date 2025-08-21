package ru.iconverter.services;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import ru.iconverter.utils.MediaTypeUtils;

@Service
public class MediaTypeService implements IMediaTypeService {

    @Override
    public MediaType getMediaType(String format) {
        var mediaType = MediaTypeUtils.getMediaTypeForFormat(format);
        if (mediaType.equals(MediaType.APPLICATION_OCTET_STREAM)) {
            throw new IllegalArgumentException("Unsupported format: " + format);
        }
        return mediaType;
    }
}

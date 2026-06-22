package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IAudioConversionService {

    // Extract/convert the audio track of an uploaded media file to the target
    // audio format (mp3, wav, aac, m4a, ogg, flac) via ffmpeg.
    Resource convert(MultipartFile file, String targetFormat);
}

package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IVideoConversionService {

    // Transcode an uploaded video between containers/codecs (mp4, avi, mov,
    // mkv, webm) via ffmpeg.
    Resource convert(MultipartFile file, String targetFormat);

    // Rescale the video to a target height (2160/1080/720/480), keeping the
    // source container/format and aspect ratio.
    Resource resize(MultipartFile file, String resolution);

    // Cut [startTime, endTime] out of the video without re-encoding.
    Resource trim(MultipartFile file, String startTime, String endTime);

    // Render an animated GIF from a clip of the video (two-pass palette for quality).
    Resource toGif(MultipartFile file, String startTime, String duration, Integer fps, Integer width);
}

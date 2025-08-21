package ru.iconverter.services.conversions;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface IImagesConversionService {

    ByteArrayResource convertImage(MultipartFile file, String format) throws IOException;
}

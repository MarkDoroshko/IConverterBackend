package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IBookConversionService {

    Resource convertBook(MultipartFile file, String sourceFormat, String targetFormat);
}

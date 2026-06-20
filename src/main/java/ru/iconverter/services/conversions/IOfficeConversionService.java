package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IOfficeConversionService {

    // Convert an office document via headless LibreOffice. `targetFormat` is the
    // output extension (pdf, docx, odt, rtf, txt). Source format is taken from
    // the uploaded file's extension. Returns the converted bytes.
    Resource convert(MultipartFile file, String targetFormat);
}

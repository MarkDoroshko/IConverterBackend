package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IPdfConversionService {

    // Compress a PDF via Ghostscript. `level` is one of screen|ebook|printer
    // (smallest → highest quality). Returns the compressed PDF bytes.
    Resource compress(MultipartFile file, String level);
}

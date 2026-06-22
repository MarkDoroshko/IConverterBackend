package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface IPdfConversionService {

    // Merge several PDFs into one (via Ghostscript), preserving the given order.
    Resource merge(List<MultipartFile> files);

    // Compress a PDF via Ghostscript. `level` is one of screen|ebook|printer
    // (smallest → highest quality). Returns the compressed PDF bytes.
    Resource compress(MultipartFile file, String level);

    // Wrap a single raster image into a PDF (via ImageMagick). Returns PDF bytes.
    Resource fromImage(MultipartFile file);

    // Render every PDF page to a JPG at `dpi` and return a ZIP of the pages.
    Resource toImages(MultipartFile file, Integer dpi);
}

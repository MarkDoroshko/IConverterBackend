package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IPowerpointConversionService {

    // PPTX/PPT → PDF via headless LibreOffice Impress.
    Resource toPdf(MultipartFile file);

    // PPTX/PPT → one JPG per slide, zipped. Renders via LibreOffice → PDF →
    // Ghostscript, reusing the same rasterizer as PdfConversionService.
    Resource toImages(MultipartFile file, Integer dpi);

    // A single image → a one-slide PPTX (image centered, scaled to fit).
    Resource fromImage(MultipartFile file);

    // PDF → PPTX: each page is rasterized to an image and placed on its own
    // slide (image-only, not editable text — same visual-only trade-off as
    // fromImage). Renders via Ghostscript, then composes slides via POI.
    Resource fromPdf(MultipartFile file, Integer dpi);
}

package ru.iconverter.services.conversions;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface IExcelConversionService {

    // Convert a spreadsheet via headless LibreOffice Calc. `targetFormat` is the
    // output extension (xlsx, csv, pdf). Source format is taken from the
    // uploaded file's extension. Returns the converted bytes.
    Resource convert(MultipartFile file, String targetFormat);

    // Best-effort PDF → spreadsheet. LibreOffice can't do this directly (Draw
    // can't export to Calc), so this extracts text via `pdftotext -layout` and
    // splits columns on runs of whitespace. Works for simple tables; complex or
    // scanned layouts will not come through cleanly. `targetFormat` is xlsx or csv.
    Resource fromPdf(MultipartFile file, String targetFormat);
}

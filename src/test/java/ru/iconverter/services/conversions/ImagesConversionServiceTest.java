package ru.iconverter.services.conversions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.iconverter.services.conversions.ImagesConversionService.*;

// Pure-logic tests for the image conversion command builder and validation.
// No Spring context, no ImageMagick required.
class ImagesConversionServiceTest {

    @Test
    void normalizeFormat_lowercasesAndStripsDotAndSpaces() {
        assertThat(normalizeFormat("  .JPG ")).isEqualTo("jpg");
        assertThat(normalizeFormat("PNG")).isEqualTo("png");
        assertThat(normalizeFormat(null)).isEqualTo("");
    }

    @Test
    void validateTargetFormat_acceptsSupported() {
        for (String f : List.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff")) {
            validateTargetFormat(f); // should not throw
        }
    }

    @Test
    void validateTargetFormat_rejectsUnsupported() {
        assertThatThrownBy(() -> validateTargetFormat("svg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateTargetFormat("pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateTargetFormat("exe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getExtension_extractsLowercaseExt() {
        assertThat(getExtension("photo.JPG")).isEqualTo("jpg");
        assertThat(getExtension("a.b.heic")).isEqualTo("heic");
        assertThat(getExtension("noext")).isEqualTo("");
    }

    @Test
    void buildCommand_minimal() {
        assertThat(buildCommand("/in.png", "/out.png", "png", null, null))
                .containsExactly("magick", "/in.png", "png:/out.png");
    }

    @Test
    void buildCommand_withQuality() {
        assertThat(buildCommand("/in.png", "/out.jpg", "jpg", 80, null))
                .containsExactly("magick", "/in.png", "-quality", "80", "jpg:/out.jpg");
    }

    @Test
    void buildCommand_withResize_onlyShrinks() {
        assertThat(buildCommand("/in.png", "/out.webp", "webp", null, 1024))
                .containsExactly("magick", "/in.png", "-resize", "1024x1024>", "webp:/out.webp");
    }

    @Test
    void buildCommand_withResizeAndQuality_orderIsResizeThenQuality() {
        assertThat(buildCommand("/in.png", "/out.jpg", "jpg", 70, 800))
                .containsExactly("magick", "/in.png", "-resize", "800x800>", "-quality", "70", "jpg:/out.jpg");
    }

    @Test
    void buildCommand_rejectsBadQuality() {
        assertThatThrownBy(() -> buildCommand("/i", "/o", "jpg", 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildCommand("/i", "/o", "jpg", 101, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildCommand_rejectsBadResize() {
        assertThatThrownBy(() -> buildCommand("/i", "/o", "jpg", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildCommand("/i", "/o", "jpg", null, 99999)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildResizeGeometry_modes() {
        assertThat(buildResizeGeometry(800, 600, "fit")).isEqualTo("800x600");
        assertThat(buildResizeGeometry(800, 600, "exact")).isEqualTo("800x600!");
        assertThat(buildResizeGeometry(800, null, "fit")).isEqualTo("800x");
        assertThat(buildResizeGeometry(null, 600, "fit")).isEqualTo("x600");
        assertThat(buildResizeGeometry(50, null, "percent")).isEqualTo("50%");
    }

    @Test
    void buildResizeGeometry_rejectsBadInput() {
        assertThatThrownBy(() -> buildResizeGeometry(null, null, "fit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildResizeGeometry(0, 100, "fit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildResizeGeometry(99999, null, "fit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildResizeGeometry(0, null, "percent")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildCropOps_coverCropSequence() {
        assertThat(buildCropOps(1080, 1080, "center"))
                .containsExactly("-resize", "1080x1080^", "-gravity", "Center", "-extent", "1080x1080", "+repage");
    }

    @Test
    void normGravity_mapsToImageMagickNames() {
        assertThat(normGravity(null)).isEqualTo("Center");
        assertThat(normGravity("north")).isEqualTo("North");
        assertThat(normGravity("southeast")).isEqualTo("SouthEast");
        assertThatThrownBy(() -> normGravity("middle")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── favicon ──────────────────────────────────────────────────────────

    @Test
    void parseFaviconSizes_defaultsWhenBlank() {
        assertThat(parseFaviconSizes(null)).containsExactly(16, 32, 48, 64, 128, 256);
        assertThat(parseFaviconSizes("")).containsExactly(16, 32, 48, 64, 128, 256);
        assertThat(parseFaviconSizes("  ")).containsExactly(16, 32, 48, 64, 128, 256);
    }

    @Test
    void parseFaviconSizes_parsesCommaList() {
        assertThat(parseFaviconSizes("16, 32,64")).containsExactly(16, 32, 64);
    }

    @Test
    void parseFaviconSizes_rejectsOutOfRangeOrInvalid() {
        assertThatThrownBy(() -> parseFaviconSizes("8")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parseFaviconSizes("1024")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parseFaviconSizes("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildFaviconCommand_joinsSizes() {
        assertThat(buildFaviconCommand("/in.png", "/out.ico", List.of(16, 32, 64)))
                .containsExactly("magick", "/in.png", "-define", "icon:auto-resize=16,32,64", "/out.ico");
    }

    // ── filters ──────────────────────────────────────────────────────────

    @Test
    void filterOps_knownFilters() {
        assertThat(filterOps("grayscale")).containsExactly("-colorspace", "Gray");
        assertThat(filterOps("SEPIA")).containsExactly("-sepia-tone", "80%");
        assertThat(filterOps("negate")).containsExactly("-negate");
    }

    @Test
    void filterOps_rejectsUnknown() {
        assertThatThrownBy(() -> filterOps("cartoon")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── watermark ────────────────────────────────────────────────────────

    @Test
    void opacityFraction_formatsAsDecimal() {
        assertThat(opacityFraction(50)).isEqualTo("0.50");
        assertThat(opacityFraction(1)).isEqualTo("0.01");
        assertThat(opacityFraction(100)).isEqualTo("1.00");
    }

    @Test
    void opacityFraction_rejectsOutOfRange() {
        assertThatThrownBy(() -> opacityFraction(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> opacityFraction(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildWatermarkImageCommand_buildsCompositeSequence() {
        assertThat(buildWatermarkImageCommand("/in.png", "/wm.png", "/out.png", "southeast", 50))
                .containsExactly("magick", "/in.png",
                        "(", "/wm.png", "-alpha", "set", "-channel", "A", "-evaluate", "Multiply", "0.50", "+channel", ")",
                        "-gravity", "SouthEast", "-compose", "over", "-composite", "/out.png");
    }

    @Test
    void buildWatermarkTextCommand_buildsAnnotateSequence() {
        assertThat(buildWatermarkTextCommand("/in.png", "/out.png", "Hello", "north", 25, 20))
                .containsExactly("magick", "/in.png",
                        "-gravity", "North", "-fill", "rgba(255,255,255,0.25)",
                        "-pointsize", "20", "-annotate", "+20+20", "Hello", "/out.png");
    }

    // ── optimize ─────────────────────────────────────────────────────────

    @Test
    void validateOptimizableFormat_acceptsSupported() {
        for (String f : List.of("jpg", "jpeg", "png", "gif", "webp")) {
            validateOptimizableFormat(f); // should not throw
        }
    }

    @Test
    void validateOptimizableFormat_rejectsUnsupported() {
        assertThatThrownBy(() -> validateOptimizableFormat("bmp")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateOptimizableFormat("tiff")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildJpegoptimCommand_rewritesInPlace() {
        assertThat(buildJpegoptimCommand("/photo.jpg", 75))
                .containsExactly("jpegoptim", "--max=75", "--strip-all", "/photo.jpg");
    }

    @Test
    void buildPngquantCommand_capsQualityRange() {
        assertThat(buildPngquantCommand("/in.png", "/out.png", 80))
                .containsExactly("pngquant", "--quality=0-80", "--strip", "--force", "--output", "/out.png", "/in.png");
    }

    @Test
    void buildGifsicleCommand_invertsQualityToLossyLevel() {
        assertThat(buildGifsicleCommand("/in.gif", "/out.gif", 80))
                .containsExactly("gifsicle", "-O3", "--lossy=20", "-o", "/out.gif", "/in.gif");
        assertThat(buildGifsicleCommand("/in.gif", "/out.gif", 100))
                .containsExactly("gifsicle", "-O3", "--lossy=0", "-o", "/out.gif", "/in.gif");
    }

    @Test
    void buildCwebpCommand_passesQualityDirectly() {
        assertThat(buildCwebpCommand("/in.webp", "/out.webp", 60))
                .containsExactly("cwebp", "-q", "60", "/in.webp", "-o", "/out.webp");
    }

    @Test
    void buildBackgroundRemovalResizeCommand_capsAt1600_onlyShrinks() {
        assertThat(buildBackgroundRemovalResizeCommand("/in.jpg", "/out.png"))
                .containsExactly("magick", "/in.jpg", "-resize", "1600x1600>", "png:/out.png");
    }
}

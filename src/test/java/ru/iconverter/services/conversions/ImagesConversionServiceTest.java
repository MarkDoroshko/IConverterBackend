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
}

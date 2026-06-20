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
    void buildCommand_minimal() {
        assertThat(buildCommand("png", null, null))
                .containsExactly("magick", "-", "png:-");
    }

    @Test
    void buildCommand_withQuality() {
        assertThat(buildCommand("jpg", 80, null))
                .containsExactly("magick", "-", "-quality", "80", "jpg:-");
    }

    @Test
    void buildCommand_withResize_onlyShrinks() {
        assertThat(buildCommand("webp", null, 1024))
                .containsExactly("magick", "-", "-resize", "1024x1024>", "webp:-");
    }

    @Test
    void buildCommand_withResizeAndQuality_orderIsResizeThenQuality() {
        assertThat(buildCommand("jpg", 70, 800))
                .containsExactly("magick", "-", "-resize", "800x800>", "-quality", "70", "jpg:-");
    }

    @Test
    void buildCommand_rejectsBadQuality() {
        assertThatThrownBy(() -> buildCommand("jpg", 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildCommand("jpg", 101, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildCommand_rejectsBadResize() {
        assertThatThrownBy(() -> buildCommand("jpg", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buildCommand("jpg", null, 99999)).isInstanceOf(IllegalArgumentException.class);
    }
}

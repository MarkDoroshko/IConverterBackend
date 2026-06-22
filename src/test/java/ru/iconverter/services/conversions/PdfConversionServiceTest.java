package ru.iconverter.services.conversions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.iconverter.services.conversions.PdfConversionService.*;

// Pure-logic tests for the Ghostscript command builder and level mapping.
// No Spring context, no Ghostscript required.
class PdfConversionServiceTest {

    @Test
    void resolveSetting_mapsKnownLevels() {
        assertThat(resolveSetting("screen")).isEqualTo("/screen");
        assertThat(resolveSetting("ebook")).isEqualTo("/ebook");
        assertThat(resolveSetting("printer")).isEqualTo("/printer");
    }

    @Test
    void resolveSetting_isCaseInsensitiveAndTrims() {
        assertThat(resolveSetting("  EBOOK ")).isEqualTo("/ebook");
    }

    @Test
    void resolveSetting_defaultsToEbookForNull() {
        assertThat(resolveSetting(null)).isEqualTo("/ebook");
    }

    @Test
    void resolveSetting_rejectsUnknown() {
        assertThatThrownBy(() -> resolveSetting("ultra"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildCommand_hasGhostscriptFlagsInOrder() {
        List<String> cmd = buildCommand("/tmp/in.pdf", "/tmp/out.pdf", "/ebook");
        assertThat(cmd).startsWith("gs", "-sDEVICE=pdfwrite");
        assertThat(cmd).contains("-dPDFSETTINGS=/ebook", "-dNOPAUSE", "-dBATCH");
        assertThat(cmd).containsSequence("-sOutputFile=/tmp/out.pdf", "/tmp/in.pdf");
    }

    @Test
    void resolveDpi_defaultsTo150AndValidatesRange() {
        assertThat(resolveDpi(null)).isEqualTo(150);
        assertThat(resolveDpi(300)).isEqualTo(300);
        assertThatThrownBy(() -> resolveDpi(50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolveDpi(1000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildToImagesCommand_rendersJpegAtDpi() {
        List<String> cmd = buildToImagesCommand("/tmp/in.pdf", "/tmp/page-%03d.jpg", 200);
        assertThat(cmd).startsWith("gs", "-sDEVICE=jpeg", "-r200");
        assertThat(cmd).containsSequence("-o", "/tmp/page-%03d.jpg", "/tmp/in.pdf");
    }

    @Test
    void buildMergeCommand_appendsInputsInOrder() {
        List<String> cmd = buildMergeCommand(List.of("/tmp/a.pdf", "/tmp/b.pdf", "/tmp/c.pdf"), "/tmp/out.pdf");
        assertThat(cmd).startsWith("gs", "-dBATCH", "-dNOPAUSE", "-q", "-sDEVICE=pdfwrite");
        assertThat(cmd).contains("-sOutputFile=/tmp/out.pdf");
        assertThat(cmd).endsWith("/tmp/a.pdf", "/tmp/b.pdf", "/tmp/c.pdf");
    }
}

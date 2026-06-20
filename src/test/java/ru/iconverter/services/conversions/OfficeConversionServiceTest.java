package ru.iconverter.services.conversions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.iconverter.services.conversions.OfficeConversionService.*;

// Pure-logic tests for the LibreOffice command builder and validation.
// No Spring context, no LibreOffice required.
class OfficeConversionServiceTest {

    @Test
    void normalize_lowercasesStripsDot() {
        assertThat(normalize("  .DOCX ")).isEqualTo("docx");
        assertThat(normalize(null)).isEqualTo("");
    }

    @Test
    void getExtension_extractsExt() {
        assertThat(getExtension("report.final.docx")).isEqualTo("docx");
        assertThat(getExtension("noext")).isEqualTo("");
        assertThat(getExtension(null)).isEqualTo("");
    }

    @Test
    void validate_acceptsWordToPdf() {
        validate("docx", "pdf"); // no throw
        validate("pdf", "docx"); // no throw
    }

    @Test
    void validate_rejectsUnsupported() {
        assertThatThrownBy(() -> validate("exe", "pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("docx", "exe")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsSameFormat() {
        assertThatThrownBy(() -> validate("pdf", "pdf")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildCommand_headlessConvertWithIsolatedProfile() {
        List<String> cmd = buildCommand("pdf", "/work", "/profile", "/work/input.docx");
        assertThat(cmd).startsWith("soffice", "--headless");
        assertThat(cmd).contains("-env:UserInstallation=file:///profile");
        assertThat(cmd).containsSequence("--convert-to", "pdf");
        assertThat(cmd).containsSequence("--outdir", "/work");
        assertThat(cmd).endsWith("/work/input.docx");
    }
}

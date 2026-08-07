package ru.iconverter.services.conversions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static ru.iconverter.services.conversions.ExcelConversionService.*;

// Pure-logic tests for the LibreOffice command builder and validation.
// No Spring context, no LibreOffice required.
class ExcelConversionServiceTest {

    @Test
    void normalize_lowercasesStripsDot() {
        assertThat(normalize("  .XLSX ")).isEqualTo("xlsx");
        assertThat(normalize(null)).isEqualTo("");
    }

    @Test
    void getExtension_extractsExt() {
        assertThat(getExtension("report.final.xlsx")).isEqualTo("xlsx");
        assertThat(getExtension("noext")).isEqualTo("");
        assertThat(getExtension(null)).isEqualTo("");
    }

    @Test
    void validate_acceptsSupportedPairs() {
        assertDoesNotThrow(() -> validate("xlsx", "csv"));
        assertDoesNotThrow(() -> validate("csv", "xlsx"));
        assertDoesNotThrow(() -> validate("xlsx", "pdf"));
        assertDoesNotThrow(() -> validate("xls", "xlsx"));
    }

    @Test
    void validate_rejectsPdfSource() {
        // PDF→spreadsheet is not supported by the LibreOffice path (Draw can't
        // export to Calc); PDF must stay a target-only format here.
        assertThatThrownBy(() -> validate("pdf", "xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsUnsupported() {
        assertThatThrownBy(() -> validate("exe", "csv"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("xlsx", "exe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsSameFormat() {
        assertThatThrownBy(() -> validate("xlsx", "xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildCommand_headlessConvertWithIsolatedProfile() {
        List<String> cmd = buildCommand("pdf", "/work", "/profile", "/work/input.xlsx");
        assertThat(cmd).startsWith("soffice", "--headless");
        assertThat(cmd).contains("-env:UserInstallation=file:///profile");
        assertThat(cmd).containsSequence("--convert-to", "pdf");
        assertThat(cmd).containsSequence("--outdir", "/work");
        assertThat(cmd).endsWith("/work/input.xlsx");
    }

    @Test
    void buildPdftotextCommand_usesLayoutFlag() {
        List<String> cmd = buildPdftotextCommand("/work/input.pdf", "/work/output.txt");
        assertThat(cmd).containsExactly("pdftotext", "-layout", "/work/input.pdf", "/work/output.txt");
    }

    @Test
    void splitColumns_splitsOnRunsOfWhitespace() {
        assertThat(splitColumns("Name        Age    City"))
                .containsExactly("Name", "Age", "City");
        assertThat(splitColumns("New York        NY"))
                .containsExactly("New York", "NY");
    }

    @Test
    void splitColumns_singleSpaceStaysInOneColumn() {
        assertThat(splitColumns("just one column")).containsExactly("just one column");
    }

    @Test
    void splitColumns_blankLineIsEmpty() {
        assertThat(splitColumns("   ")).isEmpty();
        assertThat(splitColumns("")).isEmpty();
    }

    @Test
    void parseRows_dropsBlankLinesKeepsDataRows() {
        List<List<String>> rows = parseRows(List.of(
                "Name        Age",
                "",
                "Alice       30",
                "   ",
                "Bob         25"
        ));
        assertThat(rows).containsExactly(
                List.of("Name", "Age"),
                List.of("Alice", "30"),
                List.of("Bob", "25")
        );
    }

    @Test
    void csvEscape_quotesValuesContainingCommaOrQuote() {
        assertThat(csvEscape("plain")).isEqualTo("plain");
        assertThat(csvEscape("a,b")).isEqualTo("\"a,b\"");
        assertThat(csvEscape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

}

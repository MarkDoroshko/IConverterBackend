package ru.iconverter.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.iconverter.services.DnsLookupService.normalizeHostname;
import static ru.iconverter.services.DnsLookupService.validateHostname;

// Pure validation tests. No network access, no real DNS queries.
class DnsLookupServiceTest {

    @Test
    void validateHostname_acceptsValidHosts() {
        assertThatCode(() -> validateHostname("example.com")).doesNotThrowAnyException();
        assertThatCode(() -> validateHostname("sub.domain.example.co.uk")).doesNotThrowAnyException();
        assertThatCode(() -> validateHostname("  example.com  ")).doesNotThrowAnyException();
    }

    @Test
    void validateHostname_rejectsBlank() {
        assertThatThrownBy(() -> validateHostname(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateHostname("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateHostname("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeHostname_convertsUnicodeToPunycode() {
        assertThat(normalizeHostname("  ПРИМЕР.РФ ")).isEqualTo("xn--e1afmkfd.xn--p1ai");
        assertThat(normalizeHostname("xn--e1afmkfd.xn--p1ai")).isEqualTo("xn--e1afmkfd.xn--p1ai");
        assertThat(normalizeHostname("Example.COM")).isEqualTo("example.com");
    }

    @Test
    void normalizeHostname_rejectsBlank() {
        assertThatThrownBy(() -> normalizeHostname(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizeHostname("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateHostname_rejectsMalformed() {
        assertThatThrownBy(() -> validateHostname("not a host")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateHostname("no-tld")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateHostname("-leading.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateHostname("trailing-.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateHostname("has space.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateHostname("http://example.com")).isInstanceOf(IllegalArgumentException.class);
    }
}

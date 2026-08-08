package ru.iconverter.services;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.iconverter.services.WhoisService.parseReferral;
import static ru.iconverter.services.WhoisService.parseWhoisServer;
import static ru.iconverter.services.WhoisService.validateDomain;

// Pure validation/parsing tests. No network access, no real WHOIS queries.
class WhoisServiceTest {

    @Test
    void validateDomain_acceptsValid() {
        assertThatCode(() -> validateDomain("example.com")).doesNotThrowAnyException();
        assertThatCode(() -> validateDomain("iconverter.ru")).doesNotThrowAnyException();
    }

    @Test
    void validateDomain_rejectsBlankOrMalformed() {
        assertThatThrownBy(() -> validateDomain(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateDomain("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateDomain("not a domain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validateDomain("no-tld")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseReferral_findsReferLine() {
        String ianaResponse = """
                % IANA WHOIS server
                domain:       COM

                organisation: VeriSign Global Registry Services
                refer:        whois.verisign-grs.com

                whois:        whois.verisign-grs.com
                """;
        assertThat(parseReferral(ianaResponse)).contains("whois.verisign-grs.com");
    }

    @Test
    void parseReferral_absentReturnsEmpty() {
        assertThat(parseReferral("no relevant fields here")).isEqualTo(Optional.empty());
    }

    @Test
    void parseWhoisServer_findsVariousLabels() {
        assertThat(parseWhoisServer("Whois Server: whois.registrar.example")).contains("whois.registrar.example");
        assertThat(parseWhoisServer("Registrar WHOIS Server: whois://whois.registrar.example")).contains("whois.registrar.example");
        assertThat(parseWhoisServer("ReferralServer: whois://whois.registrar.example")).contains("whois.registrar.example");
    }

    @Test
    void parseWhoisServer_absentReturnsEmpty() {
        assertThat(parseWhoisServer("Domain Name: EXAMPLE.COM")).isEqualTo(Optional.empty());
    }
}

package ru.iconverter.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ConversionQuotaFilterTest {

    @Test
    void allowsConversionsBelowHourlyQuotaAndRejectsTheNextOne() throws Exception {
        ConversionQuotaFilter filter = new ConversionQuotaFilter(2, 10);

        assertThat(invoke(filter, "203.0.113.10").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "203.0.113.10").getStatus()).isEqualTo(200);

        MockHttpServletResponse rejected = invoke(filter, "203.0.113.10");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(rejected.getContentAsString()).contains("лимит");
    }

    @Test
    void quotaIsPerIpAndOnlyForConversionPosts() throws Exception {
        ConversionQuotaFilter filter = new ConversionQuotaFilter(1, 10);

        assertThat(invoke(filter, "203.0.113.11").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "203.0.113.12").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "203.0.113.11").getStatus()).isEqualTo(429);

        MockHttpServletResponse get = invokeGet(filter, "203.0.113.11");
        assertThat(get.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresOtherApiEndpoints() throws Exception {
        ConversionQuotaFilter filter = new ConversionQuotaFilter(1, 10);

        assertThat(invoke(filter, "203.0.113.13").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "203.0.113.13").getStatus()).isEqualTo(429);

        MockHttpServletResponse devtools = invokeAt(filter, "203.0.113.13", "/api/devtools/dns");
        assertThat(devtools.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse invoke(ConversionQuotaFilter filter, String ip) throws Exception {
        return invokeAt(filter, ip, "/api/convert/images/");
    }

    private static MockHttpServletResponse invokeAt(ConversionQuotaFilter filter, String ip, String uri)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletResponse invokeGet(ConversionQuotaFilter filter, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/convert/images/");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

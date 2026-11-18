package ru.iconverter.utils;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestUtilsTest {

    private static MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void directClient_addressIsUsedAsIs() {
        MockHttpServletRequest request = requestFrom("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Real-IP", "5.6.7.8");

        assertThat(RequestUtils.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void behindProxy_prefersXRealIpSetByOurNginx() {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");

        assertThat(RequestUtils.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void behindProxy_ignoresSpoofedLeftmostXffEntry() {
        MockHttpServletRequest request = requestFrom("172.18.0.1");
        // Client sent "1.2.3.4", nginx appended the real address (as
        // $proxy_add_x_forwarded_for used to) — only the last entry is ours.
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");

        assertThat(RequestUtils.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void behindProxy_skipsGarbageEntriesAndUsesLastValidOne() {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "not-an-ip, 203.0.113.7, broken");

        assertThat(RequestUtils.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void behindProxy_withoutHeaders_fallsBackToPeer() {
        assertThat(RequestUtils.clientIp(requestFrom("::1"))).isEqualTo("::1");
    }

    @Test
    void hostnamesAreNotAcceptedAsAddresses() {
        // Would otherwise make InetAddress#getByName perform a DNS lookup.
        assertThat(RequestUtils.normalizeIp("evil.example.com")).isNull();
        assertThat(RequestUtils.normalizeIp("")).isNull();
        assertThat(RequestUtils.normalizeIp(null)).isNull();
        assertThat(RequestUtils.normalizeIp("[2001:db8::1]")).isEqualTo("2001:db8::1");
    }
}

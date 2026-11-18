package ru.iconverter.utils;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkUtilsTest {

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new AssertionError("not a literal: " + literal, e);
        }
    }

    @Test
    void publicAddressesAreAllowed() {
        assertThat(NetworkUtils.isPublicAddress(address("8.8.8.8"))).isTrue();
        assertThat(NetworkUtils.isPublicAddress(address("203.0.113.7"))).isTrue();
        assertThat(NetworkUtils.isPublicAddress(address("2001:4860:4860::8888"))).isTrue();
    }

    @Test
    void internalAndSpecialAddressesAreRejected() {
        assertThat(NetworkUtils.isPublicAddress(address("127.0.0.1"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("10.1.2.3"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("172.16.5.4"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("192.168.0.10"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("169.254.169.254"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("100.64.0.1"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("192.0.0.10"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("198.18.0.1"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("240.0.0.1"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("0.0.0.0"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("::1"))).isFalse();
        assertThat(NetworkUtils.isPublicAddress(address("fc00::1"))).isFalse();
    }

    @Test
    void nullIsNotPublic() {
        assertThat(NetworkUtils.isPublicAddress(null)).isFalse();
    }
}

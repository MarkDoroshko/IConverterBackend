package ru.iconverter.utils;

import java.net.InetAddress;

/**
 * Helpers for deciding whether a resolved address is on the public internet.
 *
 * <p>Used by the DNS/WHOIS tools: both endpoints are unauthenticated and take
 * a hostname from the user, so answering with internal addresses (or following
 * a referral to one) would turn the API into a reconnaissance oracle for the
 * server's own network.
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    public static boolean isPublicAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            int third = raw[2] & 0xff;
            if (first == 0) {
                return false;                       // 0.0.0.0/8
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false;                       // CGNAT 100.64.0.0/10
            }
            if (first == 192 && second == 0 && third == 0) {
                return false;                       // 192.0.0.0/24 (IETF protocol assignments)
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false;                       // benchmarking 198.18.0.0/15
            }
            return first < 240;                     // 240/4 reserved, 255.255.255.255 broadcast
        }
        if (raw.length == 16) {
            return (raw[0] & 0xfe) != 0xfc;         // IPv6 unique-local fc00::/7
        }
        return false;
    }
}

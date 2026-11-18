package ru.iconverter.utils;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class RequestUtils {

    private RequestUtils() {
    }

    /**
     * Client IP used as the rate-limit key.
     *
     * <p>In production nginx terminates TLS and proxies {@code /api/**} from
     * 127.0.0.1, so the socket peer is the proxy, not the client. Forwarding
     * headers are therefore only trusted when the peer is a trusted proxy.
     *
     * <p>The important detail: the <em>left</em> entries of {@code X-Forwarded-For}
     * are entirely client-controlled (nginx used to append with
     * {@code $proxy_add_x_forwarded_for}), so taking the first one — as this
     * class used to — let anyone mint a fresh rate-limit bucket per request.
     * {@code X-Real-IP} is set by nginx to {@code $remote_addr} (overwriting
     * whatever the client sent), and the <em>last</em> XFF entry is the one our
     * own proxy appended; only those two are trustworthy.
     */
    public static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "0.0.0.0";
        }
        if (!isTrustedProxy(remote)) {
            return remote;
        }

        String realIp = normalizeIp(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = normalizeIp(parts[i]);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return remote;
    }

    /** Loopback and RFC 1918 peers are our own reverse proxy / docker bridge. */
    static boolean isTrustedProxy(String addr) {
        InetAddress address = parse(addr);
        return address != null && (address.isLoopbackAddress() || address.isSiteLocalAddress());
    }

    /**
     * Returns the address as a canonical-enough literal, or {@code null} when
     * the value is not an IP at all. The character whitelist matters: it keeps
     * {@link InetAddress#getByName} from ever doing a DNS lookup on
     * attacker-supplied text.
     */
    static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || value.length() > 45) {
            return null;
        }
        if (!value.matches("[0-9a-fA-F:.%]+")) {
            return null;
        }
        return parse(value) == null ? null : value;
    }

    private static InetAddress parse(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }
}

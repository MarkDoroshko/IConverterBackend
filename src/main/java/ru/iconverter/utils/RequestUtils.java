package ru.iconverter.utils;

import jakarta.servlet.http.HttpServletRequest;

public class RequestUtils {

    private RequestUtils() {
    }

    // Behind nginx the real client IP is in X-Forwarded-For; fall back to the
    // socket address for direct/local access.
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

package ru.iconverter.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP rate limit on the API. Conversions spawn heavy external processes
 * (ImageMagick/Ghostscript/LibreOffice/Calibre), so an unbounded client could
 * exhaust CPU/memory. Limits each client IP to N requests per minute on /api/**
 * and answers 429 once the quota is exceeded.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final FixedWindowRateLimiter limiter;
    private final int maxPerMinute;

    public RateLimitFilter(@Value("${app.rate-limit.per-minute:60}") int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
        this.limiter = new FixedWindowRateLimiter(maxPerMinute, 60_000L);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String ip = clientIp(request);
        if (!limiter.allow(ip, System.currentTimeMillis())) {
            log.warn("Rate limit exceeded for {} ({} req/min)", ip, maxPerMinute);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            response.getWriter().write(
                    "{\"error\": \"Слишком много запросов. Попробуйте через минуту.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    // Behind nginx the real client IP is in X-Forwarded-For; fall back to the
    // socket address for direct/local access.
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

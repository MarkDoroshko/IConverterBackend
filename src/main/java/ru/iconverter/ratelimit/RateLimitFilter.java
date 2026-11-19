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
import ru.iconverter.utils.RequestUtils;

import java.io.IOException;

/**
 * Per-IP rate limit on the API. Conversions spawn heavy external processes
 * (ImageMagick/Ghostscript/LibreOffice/Calibre), so an unbounded client could
 * exhaust CPU/memory. Limits each client IP to N requests per minute on /api/**
 * and answers 429 once the quota is exceeded.
 *
 * <p>The lookup endpoints (/api/devtools/dns, /whois) get a much tighter budget:
 * they cause outbound DNS/TCP traffic and are only meant for humans clicking a
 * form, not for scripted enumeration.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final FixedWindowRateLimiter apiLimiter;
    private final FixedWindowRateLimiter devtoolsLimiter;
    private final int maxPerMinute;

    public RateLimitFilter(@Value("${app.rate-limit.per-minute:60}") int maxPerMinute,
                           @Value("${app.rate-limit.devtools-per-minute:10}") int devtoolsPerMinute) {
        this.maxPerMinute = maxPerMinute;
        this.apiLimiter = new FixedWindowRateLimiter(maxPerMinute, 60_000L);
        this.devtoolsLimiter = new FixedWindowRateLimiter(devtoolsPerMinute, 60_000L);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        FixedWindowRateLimiter limiter = uri.startsWith("/api/devtools/") ? devtoolsLimiter : apiLimiter;
        String ip = RequestUtils.clientIp(request);
        if (!limiter.allow(ip, System.currentTimeMillis())) {
            log.warn("Rate limit exceeded for {} on {} ({} req/min)", ip, uri,
                    limiter == devtoolsLimiter ? "devtools" : maxPerMinute);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write(
                    "{\"error\": \"Слишком много запросов. Попробуйте через минуту.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

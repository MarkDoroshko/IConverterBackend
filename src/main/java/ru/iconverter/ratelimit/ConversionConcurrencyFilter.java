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
import java.util.concurrent.Semaphore;

/**
 * Caps how many conversions run at the same time.
 *
 * <p>The per-IP request rate limit bounds requests per minute, not concurrency:
 * 60 requests in one second still mean 60 simultaneous ffmpeg/LibreOffice
 * processes, which is enough to exhaust the container's ~1.9 GB and get the
 * JVM OOM-killed. Across many IPs it is trivial to reach. This filter answers
 * 503 immediately (instead of queueing and timing out) once every slot is busy.
 *
 * <p>Ordered after {@link RateLimitFilter} so rejected-by-rate requests never
 * occupy a permit.
 */
@Component
@Order(2)
public class ConversionConcurrencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConversionConcurrencyFilter.class);

    private final Semaphore permits;
    private final int maxConcurrent;

    public ConversionConcurrencyFilter(@Value("${app.conversion.max-concurrent:3}") int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.permits = new Semaphore(this.maxConcurrent, true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean conversion = "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/convert/");
        if (!conversion) {
            chain.doFilter(request, response);
            return;
        }
        if (!permits.tryAcquire()) {
            log.warn("All {} conversion slots are busy, rejecting {}", maxConcurrent, request.getRequestURI());
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "10");
            response.getWriter().write(
                    "{\"error\": \"Сервер сейчас загружен. Попробуйте через несколько секунд.\"}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}

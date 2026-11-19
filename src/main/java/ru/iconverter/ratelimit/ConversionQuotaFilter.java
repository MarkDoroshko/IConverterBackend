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
 * Longer-horizon per-IP budget for the conversion endpoints.
 *
 * <p>The per-minute limiter bounds bursts and the concurrency semaphore bounds
 * simultaneous processes, but neither stops a slow, cheap client from using the
 * service as free CPU for hours. This adds an hourly and a daily quota per IP.
 * They are deliberately generous for a human/batch user and only catch
 * sustained abuse. Counters are in-memory (same model as the minute limiter);
 * a restart clears them, which is acceptable for a DoS guard.
 */
@Component
@Order(2)
public class ConversionQuotaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConversionQuotaFilter.class);

    private final FixedWindowRateLimiter hourly;
    private final FixedWindowRateLimiter daily;
    private final int hourlyLimit;
    private final int dailyLimit;

    public ConversionQuotaFilter(
            @Value("${app.conversion.hourly-per-ip:100}") int hourlyLimit,
            @Value("${app.conversion.daily-per-ip:400}") int dailyLimit) {
        this.hourlyLimit = Math.max(1, hourlyLimit);
        this.dailyLimit = Math.max(1, dailyLimit);
        this.hourly = new FixedWindowRateLimiter(this.hourlyLimit, 60L * 60 * 1000);
        this.daily = new FixedWindowRateLimiter(this.dailyLimit, 24L * 60 * 60 * 1000);
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

        String ip = RequestUtils.clientIp(request);
        long now = System.currentTimeMillis();

        if (!hourly.allow(ip, now)) {
            reject(response, "hourly", ip, 3600);
            return;
        }
        if (!daily.allow(ip, now)) {
            reject(response, "daily", ip, 86400);
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String window, String ip, int retryAfter)
            throws IOException {
        log.warn("Conversion {} quota exceeded for {}", window, ip);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.getWriter().write(
                "{\"error\": \"Исчерпан лимит конвертаций для вашего IP. Попробуйте позже.\"}");
    }
}

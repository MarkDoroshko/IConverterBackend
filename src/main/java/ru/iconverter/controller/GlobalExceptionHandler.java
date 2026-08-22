package ru.iconverter.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import ru.iconverter.entity.ErrorLog;
import ru.iconverter.ratelimit.FixedWindowRateLimiter;
import ru.iconverter.repository.ErrorLogRepository;
import ru.iconverter.utils.RequestUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

// Maps common failures to clean JSON responses instead of leaking 500s and
// stack traces. Validation problems are the client's fault (400); conversion
// I/O failures are server-side (500). Every handled exception is also
// persisted to the error_log table (best-effort — a logging failure must
// never affect the response sent to the client), capped per-IP so a client
// that keeps hitting an error path can't grow the table (and disk) without
// bound — the general API rate limiter allows the same request rate whether
// it succeeds or fails, so it doesn't protect against this on its own.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorLogRepository errorLogRepository;
    private final FixedWindowRateLimiter errorLogLimiter;

    public GlobalExceptionHandler(ErrorLogRepository errorLogRepository,
                                   @Value("${app.error-log.rate-limit-per-minute:20}") int rateLimitPerMinute) {
        this.errorLogRepository = errorLogRepository;
        this.errorLogLimiter = new FixedWindowRateLimiter(rateLimitPerMinute, 60_000L);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadInput(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("Bad request: {}", e.getMessage());
        persist("WARN", e, request, false);
        return json(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        persist("WARN", e, request, false);
        return json(HttpStatus.BAD_REQUEST, "Файл слишком большой");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> handleIo(IOException e, HttpServletRequest request) {
        log.error("Conversion I/O error", e);
        persist("ERROR", e, request, true);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, "Conversion failed. Please try again.");
    }

    // Our conversion services throw RuntimeException with a user-safe message
    // (e.g. "Не удалось преобразовать изображение в PDF"). Surface it instead of
    // a bare Spring 500 page so the client/operator sees the actual cause.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e, HttpServletRequest request) {
        log.error("Conversion error", e);
        persist("ERROR", e, request, true);
        return json(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getMessage() == null ? "Conversion failed" : e.getMessage());
    }

    private void persist(String level, Exception e, HttpServletRequest request, boolean withStackTrace) {
        String clientIp = RequestUtils.clientIp(request);
        if (!errorLogLimiter.allow(clientIp, System.currentTimeMillis())) {
            return;
        }
        try {
            errorLogRepository.save(ErrorLog.builder()
                    .timestamp(LocalDateTime.now())
                    .level(level)
                    .exceptionClass(e.getClass().getName())
                    .message(truncate(e.getMessage(), 1000))
                    .stackTrace(withStackTrace ? stackTraceOf(e) : null)
                    .requestUri(request.getRequestURI())
                    .httpMethod(request.getMethod())
                    .clientIp(clientIp)
                    .userAgent(truncate(request.getHeader("User-Agent"), 512))
                    .build());
        } catch (Exception saveFailure) {
            log.error("Failed to persist error log entry", saveFailure);
        }
    }

    private static String stackTraceOf(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private ResponseEntity<?> json(HttpStatus status, String message) {
        String safe = message == null ? "Unexpected error" : message.replace("\"", "'");
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + safe + "\"}");
    }
}

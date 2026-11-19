package ru.iconverter.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Replaces Spring Boot's default {@code /error} body.
 *
 * <p>The default error attributes contain a timestamp, the request path and
 * sometimes the exception message. None of that helps an API client and all of
 * it is useful to an attacker fingerprinting the service. This controller keeps
 * the HTTP status and returns a single generic field, matching the shape used by
 * {@link GlobalExceptionHandler}.
 *
 * <p>Declaring an {@link ErrorController} bean disables Boot's
 * {@code BasicErrorController} auto-configuration.
 */
@RestController
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<Map<String, String>> error(HttpServletRequest request) {
        int statusCode = statusCode(request);
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        if (status.is5xxServerError()) {
            Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            log.error("Unhandled error for {} {}", request.getMethod(), request.getRequestURI(),
                    exception instanceof Throwable t ? t : null);
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", messageFor(status)));
    }

    private static int statusCode(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return value instanceof Integer code ? code : 500;
    }

    private static String messageFor(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "Bad request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 413 -> "Payload too large";
            case 415 -> "Unsupported media type";
            case 429 -> "Too many requests";
            case 503 -> "Service unavailable";
            default -> status.is5xxServerError() ? "Internal server error" : "Request failed";
        };
    }
}

package ru.iconverter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

// Maps common failures to clean JSON responses instead of leaking 500s and
// stack traces. Validation problems are the client's fault (400); conversion
// I/O failures are server-side (500).
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadInput(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return json(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleTooLarge(MaxUploadSizeExceededException e) {
        return json(HttpStatus.BAD_REQUEST, "Файл слишком большой");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> handleIo(IOException e) {
        log.error("Conversion I/O error", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, "Conversion failed. Please try again.");
    }

    // Our conversion services throw RuntimeException with a user-safe message
    // (e.g. "Не удалось преобразовать изображение в PDF"). Surface it instead of
    // a bare Spring 500 page so the client/operator sees the actual cause.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e) {
        log.error("Conversion error", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getMessage() == null ? "Conversion failed" : e.getMessage());
    }

    private ResponseEntity<?> json(HttpStatus status, String message) {
        String safe = message == null ? "Unexpected error" : message.replace("\"", "'");
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"" + safe + "\"}");
    }
}

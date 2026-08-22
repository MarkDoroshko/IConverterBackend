package ru.iconverter.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.iconverter.entity.ErrorLog;
import ru.iconverter.repository.ErrorLogRepository;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final ErrorLogRepository repository = mock(ErrorLogRepository.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(repository, 20);

    private HttpServletRequest requestStub() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/convert/pdf/compress");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        return request;
    }

    @Test
    void badInputReturns400AndPersistsWarnLevel() {
        ResponseEntity<?> response = handler.handleBadInput(new IllegalArgumentException("bad file"), requestStub());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repository).save(any(ErrorLog.class));
    }

    @Test
    void ioExceptionReturns500AndPersistsErrorLevelWithStackTrace() {
        ResponseEntity<?> response = handler.handleIo(new IOException("disk full"), requestStub());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(repository).save(any(ErrorLog.class));
    }

    @Test
    void repositoryFailureDoesNotAffectResponse() {
        when(repository.save(any())).thenThrow(new RuntimeException("db unavailable"));

        ResponseEntity<?> response = handler.handleRuntime(new RuntimeException("conversion failed"), requestStub());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().toString()).contains("conversion failed");
    }

    @Test
    void stopsPersistingOnceThePerIpRateLimitIsHit() {
        GlobalExceptionHandler limitedHandler = new GlobalExceptionHandler(repository, 3);

        for (int i = 0; i < 5; i++) {
            ResponseEntity<?> response = limitedHandler.handleBadInput(
                    new IllegalArgumentException("bad file"), requestStub());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        verify(repository, org.mockito.Mockito.times(3)).save(any(ErrorLog.class));
    }
}

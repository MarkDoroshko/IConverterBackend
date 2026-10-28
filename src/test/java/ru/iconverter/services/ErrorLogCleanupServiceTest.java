package ru.iconverter.services;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import ru.iconverter.repository.ErrorLogRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorLogCleanupServiceTest {

    @Test
    void deleteOldEntries_deletesRowsOlderThanRetention() {
        ErrorLogRepository repository = mock(ErrorLogRepository.class);
        when(repository.deleteByTimestampBefore(any())).thenReturn(3L);

        new ErrorLogCleanupService(repository, 30).deleteOldEntries();

        LocalDateTime expected = LocalDateTime.now().minusDays(30);
        verify(repository).deleteByTimestampBefore(argThat(cutoff ->
                !cutoff.isBefore(expected.minusMinutes(1)) && !cutoff.isAfter(expected.plusMinutes(1))));
    }

    // Regression: without a transaction the derived delete query throws
    // InvalidDataAccessApiUsageException every night and nothing is purged.
    @Test
    void deleteOldEntries_runsInTransaction() throws NoSuchMethodException {
        assertThat(ErrorLogCleanupService.class.getMethod("deleteOldEntries")
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}

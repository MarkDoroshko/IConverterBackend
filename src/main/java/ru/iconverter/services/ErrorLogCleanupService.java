package ru.iconverter.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.iconverter.repository.ErrorLogRepository;

import java.time.LocalDateTime;

// Keeps error_log from growing unbounded on the production VPS (shared,
// memory-constrained host — see repo memory on server resource limits).
@Service
public class ErrorLogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ErrorLogCleanupService.class);

    private final ErrorLogRepository errorLogRepository;
    private final int retentionDays;

    public ErrorLogCleanupService(ErrorLogRepository errorLogRepository,
                                   @Value("${app.error-log.retention-days:30}") int retentionDays) {
        this.errorLogRepository = errorLogRepository;
        this.retentionDays = retentionDays;
    }

    // Derived delete queries need an active transaction; without one Spring
    // Data throws InvalidDataAccessApiUsageException and nothing is purged.
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void deleteOldEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long deleted = errorLogRepository.deleteByTimestampBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} error_log entries older than {} days", deleted, retentionDays);
        }
    }
}

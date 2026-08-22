package ru.iconverter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iconverter.entity.ErrorLog;

import java.time.LocalDateTime;

public interface ErrorLogRepository extends JpaRepository<ErrorLog, Long> {

    long deleteByTimestampBefore(LocalDateTime cutoff);
}

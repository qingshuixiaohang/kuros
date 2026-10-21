package com.kuros.kurosbackend.report.repository;

import com.kuros.kurosbackend.report.domain.ContentReport;
import com.kuros.kurosbackend.report.domain.ReportStatus;
import com.kuros.kurosbackend.report.domain.ReportTargetType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContentReportRepository extends JpaRepository<ContentReport, String> {

    Page<ContentReport> findByStatus(ReportStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from ContentReport report where report.id = :id")
    Optional<ContentReport> findByIdForUpdate(@Param("id") String id);

    boolean existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
            String reporterId,
            ReportTargetType targetType,
            String targetId,
            ReportStatus status
    );
}

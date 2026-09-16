package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.ContentReport;
import com.kuros.kurosbackend.domain.ReportStatus;
import com.kuros.kurosbackend.domain.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentReportRepository extends JpaRepository<ContentReport, String> {

    Page<ContentReport> findByStatus(ReportStatus status, Pageable pageable);

    boolean existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
            String reporterId,
            ReportTargetType targetType,
            String targetId,
            ReportStatus status
    );
}

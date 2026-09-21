package com.kuros.kurosbackend.report.repository;

import com.kuros.kurosbackend.report.domain.ContentReport;
import com.kuros.kurosbackend.report.domain.ReportStatus;
import com.kuros.kurosbackend.report.domain.ReportTargetType;
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

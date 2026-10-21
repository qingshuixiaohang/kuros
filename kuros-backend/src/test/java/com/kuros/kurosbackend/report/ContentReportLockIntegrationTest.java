package com.kuros.kurosbackend.report;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.report.domain.ContentReport;
import com.kuros.kurosbackend.report.domain.ReportReason;
import com.kuros.kurosbackend.report.domain.ReportTargetType;
import com.kuros.kurosbackend.report.repository.ContentReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.cache.type=none")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ContentReportLockIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("report-lock"));
    }

    @Autowired
    private ContentReportRepository reportRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 悲观锁阻止第二个事务在首个事务提交前读取同一举报() throws Exception {
        ContentReport report = reportRepository.saveAndFlush(new ContentReport(
                "report-lock-1",
                "reporter-1",
                ReportTargetType.POST,
                "post-1",
                ReportReason.SPAM,
                LocalDateTime.now()
        ));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);
        TransactionStatus firstTransaction =
                transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            assertThat(reportRepository.findByIdForUpdate(report.getId())).isPresent();
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            Future<Optional<ContentReport>> secondTransaction = executor.submit(
                    () -> transactionTemplate.execute(status -> {
                        secondTransactionStarted.countDown();
                        return reportRepository.findByIdForUpdate(report.getId());
                    })
            );
            assertThat(secondTransactionStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> secondTransaction.get(400, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            transactionManager.commit(firstTransaction);
            assertThat(secondTransaction.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            if (!firstTransaction.isCompleted()) {
                transactionManager.rollback(firstTransaction);
            }
            executor.shutdownNow();
        }
    }
}

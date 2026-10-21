package com.kuros.kurosbackend.report;

import com.kuros.kurosbackend.comment.domain.CommunityComment;
import com.kuros.kurosbackend.comment.repository.CommunityCommentRepository;
import com.kuros.kurosbackend.comment.service.CommunityCommentService;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.post.service.PostPublishingService;
import com.kuros.kurosbackend.report.api.CreateReportRequest;
import com.kuros.kurosbackend.report.api.HandleReportRequest;
import com.kuros.kurosbackend.report.domain.ContentReport;
import com.kuros.kurosbackend.report.domain.ReportReason;
import com.kuros.kurosbackend.report.domain.ReportStatus;
import com.kuros.kurosbackend.report.domain.ReportTargetType;
import com.kuros.kurosbackend.report.repository.ContentReportRepository;
import com.kuros.kurosbackend.report.service.ContentReportService;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentReportServiceBoundaryTest {

    private boolean lockRequested;

    @Test
    void 举报服务字段与构造参数只允许举报仓储和内容领域服务() {
        Set<Class<?>> dependencies = Stream.concat(
                        Arrays.stream(ContentReportService.class.getDeclaredFields())
                                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                                .filter(field -> !field.isSynthetic())
                                .map(Field::getType),
                        Arrays.stream(ContentReportService.class.getDeclaredConstructors())
                                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                )
                .collect(Collectors.toSet());

        assertThat(dependencies)
                .containsExactlyInAnyOrder(
                        ContentReportRepository.class,
                        PostPublishingService.class,
                        CommunityCommentService.class
                );
    }

    @Test
    void 确认帖子举报时委派帖子领域删除() {
        ContentReport report = report(ReportTargetType.POST, "post-1");
        RecordingPostService postService = new RecordingPostService();
        RecordingCommentService commentService = new RecordingCommentService();
        ContentReportService service = new ContentReportService(repositoryFor(report), postService, commentService);

        service.handle(report.getId(), "admin-1", new HandleReportRequest("CONFIRM", null));

        assertThat(lockRequested).isTrue();
        assertThat(postService.deletedTargetId).isEqualTo("post-1");
        assertThat(commentService.deletedTargetId).isNull();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.CONFIRMED);
    }

    @Test
    void 确认评论举报时委派评论领域删除() {
        ContentReport report = report(ReportTargetType.COMMENT, "comment-1");
        RecordingPostService postService = new RecordingPostService();
        RecordingCommentService commentService = new RecordingCommentService();
        ContentReportService service = new ContentReportService(repositoryFor(report), postService, commentService);

        service.handle(report.getId(), "admin-1", new HandleReportRequest("CONFIRM", null));

        assertThat(lockRequested).isTrue();
        assertThat(commentService.deletedTargetId).isEqualTo("comment-1");
        assertThat(postService.deletedTargetId).isNull();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.CONFIRMED);
    }

    @Test
    void 不存在的帖子举报保持原有错误码() {
        PostPublishingService postService = new PostPublishingService(
                postRepositoryWithoutMatch(), null, null, null, null, null, null, null, null
        );
        CommunityCommentService commentService = new CommunityCommentService(null, null, null, null);
        ContentReportService service = new ContentReportService(
                repositoryThatMustNotBeUsed(), postService, commentService
        );

        assertThatThrownBy(() -> service.create(
                "POST", "post-1", "reporter-1", new CreateReportRequest("SPAM")
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting(exception -> ((ResourceNotFoundException) exception).getCode())
                .isEqualTo("POST_NOT_FOUND");
    }

    @Test
    void 已删除评论举报保持原有错误码() {
        CommunityComment deletedComment = new CommunityComment(
                "comment-1", "post-1", "author-1", null, "已删除", LocalDateTime.now()
        );
        deletedComment.delete(LocalDateTime.now());
        CommunityCommentService commentService = new CommunityCommentService(
                commentRepositoryReturning(deletedComment), null, null, null
        );
        PostPublishingService postService = new PostPublishingService(
                null, null, null, null, null, null, null, null, null
        );
        ContentReportService service = new ContentReportService(
                repositoryThatMustNotBeUsed(), postService, commentService
        );

        assertThatThrownBy(() -> service.create(
                "COMMENT", "comment-1", "reporter-1", new CreateReportRequest("ABUSE")
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting(exception -> ((ResourceNotFoundException) exception).getCode())
                .isEqualTo("COMMENT_NOT_FOUND");
    }

    private ContentReport report(ReportTargetType targetType, String targetId) {
        return new ContentReport(
                "report-1", "reporter-1", targetType, targetId, ReportReason.SPAM, LocalDateTime.now()
        );
    }

    private ContentReportRepository repositoryFor(ContentReport report) {
        return (ContentReportRepository) Proxy.newProxyInstance(
                ContentReportRepository.class.getClassLoader(),
                new Class<?>[]{ContentReportRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByIdForUpdate" -> {
                        lockRequested = true;
                        yield Optional.of(report);
                    }
                    case "save" -> args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private ContentReportRepository repositoryThatMustNotBeUsed() {
        return (ContentReportRepository) Proxy.newProxyInstance(
                ContentReportRepository.class.getClassLoader(),
                new Class<?>[]{ContentReportRepository.class},
                (proxy, method, args) -> {
                    throw new AssertionError("举报目标校验失败后不应访问举报仓储: " + method.getName());
                }
        );
    }

    private CommunityPostRepository postRepositoryWithoutMatch() {
        return (CommunityPostRepository) Proxy.newProxyInstance(
                CommunityPostRepository.class.getClassLoader(),
                new Class<?>[]{CommunityPostRepository.class},
                (proxy, method, args) -> method.getName().equals("findByIdAndStatus")
                        ? Optional.empty()
                        : throwUnsupported(method.getName())
        );
    }

    private CommunityCommentRepository commentRepositoryReturning(CommunityComment comment) {
        return (CommunityCommentRepository) Proxy.newProxyInstance(
                CommunityCommentRepository.class.getClassLoader(),
                new Class<?>[]{CommunityCommentRepository.class},
                (proxy, method, args) -> method.getName().equals("findById")
                        ? Optional.of(comment)
                        : throwUnsupported(method.getName())
        );
    }

    private Object throwUnsupported(String methodName) {
        throw new UnsupportedOperationException(methodName);
    }

    private static final class RecordingPostService extends PostPublishingService {
        private String deletedTargetId;

        private RecordingPostService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void deleteForModeration(String postId) {
            deletedTargetId = postId;
        }
    }

    private static final class RecordingCommentService extends CommunityCommentService {
        private String deletedTargetId;

        private RecordingCommentService() {
            super(null, null, null, null);
        }

        @Override
        public void deleteForModeration(String commentId) {
            deletedTargetId = commentId;
        }
    }
}

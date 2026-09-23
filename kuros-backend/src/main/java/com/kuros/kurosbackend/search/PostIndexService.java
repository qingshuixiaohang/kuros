package com.kuros.kurosbackend.search;

import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.ContentTag;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子索引服务（切片 #14 se-03）：把 DB 里的帖子「回源组装」成 {@link PostSearchDoc} 写进 ES。
 *
 * <p>CDC（se-05）与手动全量重建都调它——它是搜索数据的唯一写入口。
 *
 * <p>为什么「回源组装」而不直接用 binlog 的 FlatMessage.data 拼文档（ADR 0007 决策）：
 * binlog 只带被改表的行字段，拼不出完整文档——① tags 在 post_tags 关联表（需 join）；
 * ② authorName 在 kuros-user 另一个库（需 Feign）；③ binlog 值全是 String，转 long/enum/date 易错。
 * 故拿到 postId 后一律回 DB + Feign 重新组装权威文档，binlog 仅当「哪个 postId 变了」的触发信号。
 *
 * <p>幂等：以 {@code _id = postId} index 覆盖写，重复索引/消息重投只覆盖不产生副本——
 * 这是 CDC「至少一次」投递下保证最终一致的关键（消费端无需去重）。
 */
@Service
public class PostIndexService {

    private static final Logger log = LoggerFactory.getLogger(PostIndexService.class);

    private final CommunityPostRepository postRepository;
    private final UserDirectoryFacade userDirectory;
    private final ElasticsearchOperations operations;
    private final SearchIndexManager indexManager;

    public PostIndexService(CommunityPostRepository postRepository,
                            UserDirectoryFacade userDirectory,
                            ElasticsearchOperations operations,
                            SearchIndexManager indexManager) {
        this.postRepository = postRepository;
        this.userDirectory = userDirectory;
        this.operations = operations;
        this.indexManager = indexManager;
    }

    /**
     * 索引（或更新）单篇帖子——CDC 增量与手动补索引的入口。
     *
     * <p>逻辑删（status→DELETED）：帖子仍查得到 → 照常组装写入（status=DELETED），文档保留，
     * 由 se-04 搜索期 {@code filter status=PUBLISHED} 排除（与「已删帖可恢复」语义一致，不物理删）。
     * <p>物理删/回源查不到：兜底 {@code delete by _id}，避免 ES 残留孤儿文档。
     */
    public void index(String postId) {
        indexManager.ensureAlias();
        Optional<CommunityPost> found = postRepository.findByIdWithTags(postId);
        if (found.isEmpty()) {
            // 回源查不到 = 已被物理删除（或从未存在）→ 兜底删 ES 文档
            operations.delete(postId, PostSearchDoc.class);
            log.debug("回源未命中 postId={}，已从索引删除（物理删兜底）", postId);
            return;
        }
        Map<String, UserBriefDto> authors = userDirectory.findAuthors(List.of(found.get().getAuthorId()));
        PostSearchDoc doc = assemble(found.get(), authors);
        // save 走 @Document 的别名 post_search（单写索引时 ES 自动路由到其背后的真实索引），_id=postId 覆盖写幂等
        operations.save(doc);
    }

    /** 物理删除索引文档（se-05 收到 DELETE 类型 binlog 时调用）。 */
    public void delete(String postId) {
        operations.delete(postId, PostSearchDoc.class);
    }

    /**
     * 全量重建索引（零停机，运维经 {@code POST /api/v1/admin/search/reindex} 触发）。
     * 由 {@link SearchIndexManager} 建 v{n+1} → 本方法把全量帖子 bulk 写入新索引 → 原子切别名 → 删旧索引。
     *
     * @return 本次重建索引的文档数
     */
    public long reindexAll() {
        return indexManager.reindex(newIndex -> {
            List<CommunityPost> posts = postRepository.findAllWithTags();
            // 作者名批量查（一次 Feign），避免逐帖 N 次往返；Feign 失败降级为空 map → 占位昵称
            Set<String> authorIds = posts.stream().map(CommunityPost::getAuthorId).collect(Collectors.toSet());
            Map<String, UserBriefDto> authors = userDirectory.findAuthors(authorIds);
            List<PostSearchDoc> docs = posts.stream().map(post -> assemble(post, authors)).toList();
            if (!docs.isEmpty()) {
                // 必须显式写新版本索引坐标（而非别名）——否则 bulk 会命中别名当前指向的旧索引
                operations.save(docs, newIndex);
            }
            return (long) docs.size();
        });
    }

    /**
     * 回源组装：JPA 实体（含 fetch join 的 tags）+ Feign 作者名 → ES 文档。
     * 单独抽出供单篇 index 与全量 reindex 复用，保证两条路径产出完全一致的文档。
     */
    private PostSearchDoc assemble(CommunityPost post, Map<String, UserBriefDto> authors) {
        UserBriefDto author = authors.get(post.getAuthorId());
        String authorName = author != null ? author.nickname() : UserDirectoryFacade.FALLBACK_NICKNAME;
        List<String> tags = post.getTags().stream().map(ContentTag::getName).sorted().toList();
        return PostSearchDoc.builder()
                .postId(post.getId())
                .title(post.getTitle())
                .excerpt(post.getExcerpt())
                .content(post.getContent())
                .authorId(post.getAuthorId())
                .authorName(authorName)
                .category(post.getCategory())
                .type(post.getType().name())
                .status(post.getStatus().name())
                .tags(tags)
                .viewCount(post.getViewCount())
                .likeCount(post.getLikeCount())
                .favoriteCount(post.getFavoriteCount())
                .commentCount(post.getCommentCount())
                .publishedAt(post.getPublishedAt())
                .build();
    }

    /** 供 se-04/测试按 postId 读回索引文档（走别名）。 */
    Optional<PostSearchDoc> findById(String postId) {
        return Optional.ofNullable(operations.get(postId, PostSearchDoc.class));
    }

    /** 供测试刷新别名背后的索引，使写入立即可搜（默认 1s 自动 refresh，测试不等）。 */
    void refresh() {
        operations.indexOps(IndexCoordinates.of(PostSearchDoc.INDEX_ALIAS)).refresh();
    }
}

package com.kuros.kurosbackend.post.repository;

import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, String> {

    @Query("""
            select distinct p from CommunityPost p
            left join p.tags tag
            where p.status = :status
              and (:category is null or p.category = :category)
              and (:tag is null or tag.name = :tag)
              and (:keyword is null or lower(p.title) like lower(concat('%', :keyword, '%'))
                   or lower(p.excerpt) like lower(concat('%', :keyword, '%')))
            """)
    Page<CommunityPost> findVisiblePosts(
            @Param("status") PostStatus status,
            @Param("category") String category,
            @Param("tag") String tag,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    /**
     * keyset 游标翻页——最新流（切片 #13 / rp-05）。排序键 (published_at, id) 均 DESC。
     *
     * 为什么不用 offset？offset 翻页要扫弃前 offset 行（O(offset+n)）且要 COUNT(*) 算总页；
     * keyset 用「排序键严格小于游标」的谓词直接定位起点，翻到多深都是 O(log n + limit)、不查总数。
     *
     * 谓词 (published_at &lt; :ts) OR (published_at = :ts AND id &lt; :id)：同发布时间时用 id 字典序兜底全序，
     * 保证不丢不重。:hasCursor=false 时首个析取项恒真 → 退化为「取最新 limit 条」（第一页）。
     * 返回 List（非 Page）避开 COUNT(*)；limit 由 Pageable 施加（调用方传 limit+1 探测 hasMore）。
     */
    @Query("""
            select distinct p from CommunityPost p
            left join p.tags tag
            where p.status = :status
              and (:category is null or p.category = :category)
              and (:tag is null or tag.name = :tag)
              and (:keyword is null or lower(p.title) like lower(concat('%', :keyword, '%'))
                   or lower(p.excerpt) like lower(concat('%', :keyword, '%')))
              and (:hasCursor = false
                   or p.publishedAt < :cursorPublishedAt
                   or (p.publishedAt = :cursorPublishedAt and p.id < :cursorId))
            order by p.publishedAt desc, p.id desc
            """)
    List<CommunityPost> findLatestByCursor(
            @Param("status") PostStatus status,
            @Param("category") String category,
            @Param("tag") String tag,
            @Param("keyword") String keyword,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorPublishedAt") LocalDateTime cursorPublishedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * keyset 游标翻页——热门流（切片 #13 / rp-05）。排序键 (like_count, comment_count, published_at, id) 均 DESC。
     *
     * 四元组比较展开为 OR 链（JPQL 无 row-value 语法）：逐级相等才比下一级，任一级严格小于即命中下一页。
     * 同热度（like+comment 相等）时用 published_at、再用 id 兜底全序，保证不丢不重。
     */
    @Query("""
            select distinct p from CommunityPost p
            left join p.tags tag
            where p.status = :status
              and (:category is null or p.category = :category)
              and (:tag is null or tag.name = :tag)
              and (:keyword is null or lower(p.title) like lower(concat('%', :keyword, '%'))
                   or lower(p.excerpt) like lower(concat('%', :keyword, '%')))
              and (:hasCursor = false
                   or p.likeCount < :cursorLike
                   or (p.likeCount = :cursorLike and p.commentCount < :cursorComment)
                   or (p.likeCount = :cursorLike and p.commentCount = :cursorComment and p.publishedAt < :cursorPublishedAt)
                   or (p.likeCount = :cursorLike and p.commentCount = :cursorComment and p.publishedAt = :cursorPublishedAt and p.id < :cursorId))
            order by p.likeCount desc, p.commentCount desc, p.publishedAt desc, p.id desc
            """)
    List<CommunityPost> findHotByCursor(
            @Param("status") PostStatus status,
            @Param("category") String category,
            @Param("tag") String tag,
            @Param("keyword") String keyword,
            @Param("hasCursor") boolean hasCursor,
            @Param("cursorLike") long cursorLike,
            @Param("cursorComment") long cursorComment,
            @Param("cursorPublishedAt") LocalDateTime cursorPublishedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    Optional<CommunityPost> findByIdAndStatus(String id, PostStatus status);

    /**
     * 按 id 连同 tags 一次性抓取（切片 #14 se-03 回源组装用）。
     *
     * 为什么不用 findById：tags 是 LAZY ManyToMany，而 {@code PostIndexService.index} 组装文档时
     * 会在读 DB 之后接着做 ES 写入（跨 I/O）——若靠 LAZY 代理，脱离事务后访问 tags 会抛 LazyInitializationException。
     * fetch join 在同一条 SQL 内把 tags 拉齐，无需让 ES 写入被裹在 DB 事务里（长事务占连接）。
     */
    @Query("select p from CommunityPost p left join fetch p.tags where p.id = :id")
    Optional<CommunityPost> findByIdWithTags(@Param("id") String id);

    /**
     * 全量抓取（含 tags）供零停机重建遍历（切片 #14 se-03）。distinct 去重 collection fetch join 因 tags 笛卡尔积产生的重复行。
     * 注：本项目帖子量级小，一次性全量可接受；生产海量数据应改为分页/流式（按 id 游标批次），避免一次载入全部实体。
     */
    @Query("select distinct p from CommunityPost p left join fetch p.tags")
    List<CommunityPost> findAllWithTags();

    Page<CommunityPost> findByAuthorIdAndStatus(String authorId, PostStatus status, Pageable pageable);

    long countByAuthorIdAndStatus(String authorId, PostStatus status);

    @Query("select coalesce(sum(p.likeCount), 0) from CommunityPost p where p.authorId = :authorId and p.status = :status")
    long sumLikeCountByAuthorIdAndStatus(@Param("authorId") String authorId, @Param("status") PostStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPost p set p.likeCount = p.likeCount + 1 where p.id = :postId")
    int incrementLikeCount(@Param("postId") String postId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPost p set p.likeCount = case when p.likeCount > 0 then p.likeCount - 1 else 0 end where p.id = :postId")
    int decrementLikeCount(@Param("postId") String postId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPost p set p.favoriteCount = p.favoriteCount + 1 where p.id = :postId")
    int incrementFavoriteCount(@Param("postId") String postId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommunityPost p set p.favoriteCount = case when p.favoriteCount > 0 then p.favoriteCount - 1 else 0 end where p.id = :postId")
    int decrementFavoriteCount(@Param("postId") String postId);
}

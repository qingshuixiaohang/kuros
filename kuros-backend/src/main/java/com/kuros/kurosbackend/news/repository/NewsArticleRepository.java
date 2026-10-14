package com.kuros.kurosbackend.news.repository;

import com.kuros.kurosbackend.news.domain.NewsArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, String> {

    /**
     * 列表查询：只取 enabled，按 sort_order 稳定排序（同分再按发布时间倒序，
     * 保证排序是确定的——分页游走时顺序不能漂）。
     *
     * 刻意不带 {@code paragraphs}：列表只需要标题/摘要，带上段落会让每行多一次
     * 关联装载（读路径第 13 篇的教训）。
     */
    Page<NewsArticle> findByEnabledTrue(Pageable pageable);

    Page<NewsArticle> findByEnabledTrueAndCategory(String category, Pageable pageable);

    /**
     * 详情查询：{@code EntityGraph} 一次把段落 join 回来。
     * 不用默认的懒加载——那会在序列化时触发一次额外查询，且事务已结束的话直接
     * LazyInitializationException。一次查询拿完整详情，N+1 无从发生。
     */
    @EntityGraph(attributePaths = "paragraphs")
    Optional<NewsArticle> findBySlugAndEnabledTrue(String slug);
}

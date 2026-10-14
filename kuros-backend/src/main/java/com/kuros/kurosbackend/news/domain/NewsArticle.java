package com.kuros.kurosbackend.news.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 资讯文章（前端审计 H-9：原先是前端 mock.ts 的 newsItems 数组）。
 *
 * 用 JPA 实体而不是 record：正文段落是 {@link NewsParagraph} 集合，
 * 需要由 ORM 管理生命周期。文章本体与 game_characters 保持同一套形状约定
 * （slug 对外、UUID 主键、enabled 软开关、sort_order 稳定排序）。
 */
@Entity
@Table(name = "news_articles")
public class NewsArticle {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(length = 80, nullable = false, unique = true)
    private String slug;

    @Column(length = 120, nullable = false)
    private String title;

    @Column(length = 32, nullable = false)
    private String category;

    @Column(length = 500, nullable = false)
    private String summary;

    @Column(name = "published_at", nullable = false)
    private LocalDate publishedAt;

    @Column(nullable = false)
    private boolean hot;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 正文段落。{@code fetch = LAZY}（JPA 多对一默认）意味着列表查询不会带上它，
     * 只有详情查询才需要显式 join fetch——否则 N+1 会以「每条资讯一段 SQL」
     * 的形式回来（读路径踩过同样的坑，见 docs/learning 第 13 篇）。
     */
    @OneToMany(mappedBy = "article")
    @OrderBy("paragraphIndex ASC")
    private List<NewsParagraph> paragraphs = new ArrayList<>();

    protected NewsArticle() {
    }

    public String getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDate getPublishedAt() {
        return publishedAt;
    }

    public boolean isHot() {
        return hot;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<NewsParagraph> getParagraphs() {
        return paragraphs;
    }
}

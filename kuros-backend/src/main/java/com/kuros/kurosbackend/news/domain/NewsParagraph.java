package com.kuros.kurosbackend.news.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 资讯正文的一个段落。
 *
 * 为什么用子表而不是在 NewsArticle 上放一个 TEXT：前端按段渲染（段间距、
 * 段首缩进都不同），段序需要能显式维护；放在一个字段里靠 split("
") 拆分
 * 会把格式约定藏到两端各自实现，改一头另一头静默错位。
 */
@Entity
@Table(name = "news_article_paragraphs")
public class NewsParagraph {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private NewsArticle article;

    @Column(name = "paragraph_index", nullable = false)
    private int paragraphIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    protected NewsParagraph() {
    }

    public String getId() {
        return id;
    }

    public NewsArticle getArticle() {
        return article;
    }

    public int getParagraphIndex() {
        return paragraphIndex;
    }

    public String getContent() {
        return content;
    }
}

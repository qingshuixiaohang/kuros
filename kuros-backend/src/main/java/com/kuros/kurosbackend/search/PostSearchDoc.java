package com.kuros.kurosbackend.search;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子全文检索文档（切片 #14 se-03）：ES 索引 {@code post_search} 的映射实体。
 *
 * <p>为什么 {@code createIndex = false}：{@code post_search} 是「别名」而非真实索引——真实索引是
 * {@code post_search_v1 / v2 / ...}，由 {@link SearchIndexManager} 显式创建并挂别名，以支持零停机全量重建
 * （新索引 bulk 写完 → 原子切别名 → 删旧索引）。若放任 Spring Data ES 自动建索引（{@code createIndex=true} 默认），
 * 它会建一个名为 {@code post_search} 的「真实索引」，与别名同名冲突，零停机重建便无从谈起。
 *
 * <p>分词策略（ik 标准用法，se-01 spike 已实测隔字命中）：建索引用 {@code ik_max_word}（细粒度切全，召回优先），
 * 搜索用 {@code ik_smart}（粗粒度，精度优先）。
 *
 * <p>为什么 boost 权重（title&gt;excerpt&gt;content）不写进 mapping：ES 5.0 起 index-time boost 已废弃并被忽略，
 * 字段权重改由 se-04 查询期的 {@code multi_match fields=["title^3","excerpt^2","content"]} 施加（query-time boost）。
 * 把 boost 留在查询侧，调权重无需重建索引。
 */
@Document(indexName = PostSearchDoc.INDEX_ALIAS, createIndex = false)
public class PostSearchDoc {

    /** 对外读写别名；真实索引 {@code post_search_v{n}} 由 {@link SearchIndexManager} 管理。 */
    public static final String INDEX_ALIAS = "post_search";

    /**
     * = ES {@code _id}：以 postId 为文档主键，index 覆盖写天然幂等（重复索引不产生副本）。
     *
     * <p>为什么在 {@code @Id} 之外还要显式 {@code @Field(Keyword)}：se-04 的三种排序都以 postId 作
     * search_after 的 tie-breaker（保证排序全序、翻页不重不漏），而**排序要求字段被索引且有 doc_values**。
     * 仅 {@code @Id} 时 Spring Data ES 只把它当 {@code _id} 元字段处理，不保证在 mapping 里生成可排序的
     * {@code postId} 字段——对未映射字段排序会触发 {@code search_phase_execution_exception: all shards failed}。
     * 显式声明为 keyword 后，postId 同时是 {@code _id} 与一个可排序/可过滤的 keyword 字段（值不重复，同源于 _source）。
     */
    @Id
    @Field(type = FieldType.Keyword)
    private String postId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String excerpt;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    @Field(type = FieldType.Keyword)
    private String authorId;

    /**
     * 作者昵称反范式冗余进索引（决策见 ADR 0007）：省掉每条搜索结果的跨服务 Feign 往返，
     * 代价是用户改名时需同步索引（可接受，改名低频）。text(ik) 支持按作者名搜中文，
     * {@code .keyword} 子字段支持精确过滤/聚合。
     */
    @MultiField(
            mainField = @Field(name = "authorName", type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword)
    )
    private String authorName;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String type;

    /**
     * 冗余 PUBLISHED / DELETED：逻辑删（status→DELETED）不物理删文档，靠 se-04 查询期
     * {@code filter status=PUBLISHED} 排除——与「已删除帖子可恢复」语义一致（ADR 0007 决策）。
     */
    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Long)
    private long viewCount;

    @Field(type = FieldType.Long)
    private long likeCount;

    @Field(type = FieldType.Long)
    private long favoriteCount;

    @Field(type = FieldType.Long)
    private long commentCount;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime publishedAt;

    /** Spring Data ES 回读文档时用无参构造 + 字段反射填充（se-01 spike 已验证此路径）。 */
    PostSearchDoc() {
    }

    private PostSearchDoc(Builder builder) {
        this.postId = builder.postId;
        this.title = builder.title;
        this.excerpt = builder.excerpt;
        this.content = builder.content;
        this.authorId = builder.authorId;
        this.authorName = builder.authorName;
        this.category = builder.category;
        this.type = builder.type;
        this.status = builder.status;
        this.tags = builder.tags;
        this.viewCount = builder.viewCount;
        this.likeCount = builder.likeCount;
        this.favoriteCount = builder.favoriteCount;
        this.commentCount = builder.commentCount;
        this.publishedAt = builder.publishedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getPostId() {
        return postId;
    }

    public String getTitle() {
        return title;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public String getContent() {
        return content;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getCategory() {
        return category;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getTags() {
        return tags;
    }

    public long getViewCount() {
        return viewCount;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public long getFavoriteCount() {
        return favoriteCount;
    }

    public long getCommentCount() {
        return commentCount;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    /** 14 字段的建造者：组装集中在 {@link PostIndexService}，用 builder 避免长参数列表的顺序错位。 */
    public static final class Builder {
        private String postId;
        private String title;
        private String excerpt;
        private String content;
        private String authorId;
        private String authorName;
        private String category;
        private String type;
        private String status;
        private List<String> tags;
        private long viewCount;
        private long likeCount;
        private long favoriteCount;
        private long commentCount;
        private LocalDateTime publishedAt;

        public Builder postId(String postId) { this.postId = postId; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder excerpt(String excerpt) { this.excerpt = excerpt; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder authorId(String authorId) { this.authorId = authorId; return this; }
        public Builder authorName(String authorName) { this.authorName = authorName; return this; }
        public Builder category(String category) { this.category = category; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder tags(List<String> tags) { this.tags = tags; return this; }
        public Builder viewCount(long viewCount) { this.viewCount = viewCount; return this; }
        public Builder likeCount(long likeCount) { this.likeCount = likeCount; return this; }
        public Builder favoriteCount(long favoriteCount) { this.favoriteCount = favoriteCount; return this; }
        public Builder commentCount(long commentCount) { this.commentCount = commentCount; return this; }
        public Builder publishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; return this; }

        public PostSearchDoc build() {
            return new PostSearchDoc(this);
        }
    }
}

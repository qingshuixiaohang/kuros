package com.kuros.kurosbackend.search;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;

import java.util.List;

/**
 * 搜索排序模式（切片 #14 se-04）：{@code sort=relevance|latest|hot}。
 *
 * <p>为什么把「ES 排序子句」与「search_after 游标 token 类型」都放进本枚举、co-locate 在一处：
 * search_after 的值序列必须与排序子句**逐位对应**（顺序 + 类型都要匹配 ES 返回的 sortValues），
 * 二者一旦漂移翻页就会错乱。把 {@link #sortOptions()} 与 {@link #searchAfter(List)} 定义在同一个枚举里，
 * 改排序键时被迫同时改游标解码，从结构上杜绝「加了个排序字段忘了改 cursor」这类 bug。
 *
 * <p>为什么每种排序都以 {@code postId}（keyword）作末位 tie-breaker：search_after 要求排序键能定出**全序**，
 * 否则同分值/同时间的多条文档翻页时会重复或跳过。_score、publishedAt、计数都可能并列，追加唯一的 postId 兜底全序
 * （呼应 #13 帖子列表游标的 (排序键..., id) 设计）。
 */
public enum SearchSort {

    /** 相关性：按 _score 降序（multi_match boost 的打分结果），postId 兜底。 */
    RELEVANCE,
    /** 最新：按 publishedAt 降序，postId 兜底。 */
    LATEST,
    /** 热度：按 likeCount、commentCount 降序，再 publishedAt 降序，postId 兜底（对齐 #13 DB 版 hot 排序键）。 */
    HOT;

    /** 解析查询参数；无法识别（含 null/空）一律回退 RELEVANCE，不因非法 sort 值 400（宽容读端点）。 */
    public static SearchSort fromParam(String param) {
        if (param == null || param.isBlank()) {
            return RELEVANCE;
        }
        return switch (param.trim().toLowerCase()) {
            case "latest" -> LATEST;
            case "hot" -> HOT;
            default -> RELEVANCE;
        };
    }

    /** 本排序模式对应的 ES 排序子句（顺序即 search_after 值顺序）。 */
    List<SortOptions> sortOptions() {
        return switch (this) {
            case RELEVANCE -> List.of(
                    SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))),
                    postIdAsc());
            case LATEST -> List.of(
                    SortOptions.of(s -> s.field(f -> f.field("publishedAt").order(SortOrder.Desc))),
                    postIdAsc());
            case HOT -> List.of(
                    SortOptions.of(s -> s.field(f -> f.field("likeCount").order(SortOrder.Desc))),
                    SortOptions.of(s -> s.field(f -> f.field("commentCount").order(SortOrder.Desc))),
                    SortOptions.of(s -> s.field(f -> f.field("publishedAt").order(SortOrder.Desc))),
                    postIdAsc());
        };
    }

    private static SortOptions postIdAsc() {
        return SortOptions.of(s -> s.field(f -> f.field("postId").order(SortOrder.Asc)));
    }

    /**
     * 把解码后的游标 token（全 String）按本排序的 sortValues schema 还原为 ES search_after 需要的类型序列。
     *
     * <p>类型必须与 ES 返回的 sortValues 一致：_score→Float、date/long 字段→Long（date 的 sortValue 是 epoch millis）、
     * keyword→String。类型不符 ES 会拒绝或错位，故此处按模式精确定型。
     *
     * @throws AuthRequestException token 数与排序键不符或数值/日期解析失败（客户端篡改或跨 sort 复用游标）→ 400 INVALID_CURSOR
     */
    List<Object> searchAfter(List<String> tokens) {
        int expected = switch (this) {
            case RELEVANCE, LATEST -> 2;
            case HOT -> 4;
        };
        if (tokens.size() != expected) {
            throw invalidCursor();
        }
        try {
            return switch (this) {
                case RELEVANCE -> List.of(Float.parseFloat(tokens.get(0)), tokens.get(1));
                case LATEST -> List.of(Long.parseLong(tokens.get(0)), tokens.get(1));
                case HOT -> List.of(
                        Long.parseLong(tokens.get(0)),
                        Long.parseLong(tokens.get(1)),
                        Long.parseLong(tokens.get(2)),
                        tokens.get(3));
            };
        } catch (NumberFormatException e) {
            throw invalidCursor();
        }
    }

    private static AuthRequestException invalidCursor() {
        return new AuthRequestException("INVALID_CURSOR", "搜索游标格式无效");
    }
}

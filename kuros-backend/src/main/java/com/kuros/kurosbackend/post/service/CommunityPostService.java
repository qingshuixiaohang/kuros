package com.kuros.kurosbackend.post.service;

import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.api.CursorCodec;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import com.kuros.kurosbackend.post.api.PostDetailContent;
import com.kuros.kurosbackend.post.api.PostDetailResponse;
import com.kuros.kurosbackend.post.api.PostMediaResponse;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.domain.PostMedia;
import com.kuros.kurosbackend.shared.cache.CacheNames;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.post.repository.PostMediaRepository;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CommunityPostService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityPostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final UserDirectoryFacade userDirectory;
    private final TwoLevelCache twoLevelCache;
    private final PostInteractionCountReader interactionCountReader;
    private final String publicBaseUrl;

    public CommunityPostService(
            CommunityPostRepository postRepository,
            PostMediaRepository postMediaRepository,
            MediaAssetRepository mediaAssetRepository,
            UserDirectoryFacade userDirectory,
            TwoLevelCache twoLevelCache,
            PostInteractionCountReader interactionCountReader,
            @Value("${app.storage.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.userDirectory = userDirectory;
        this.twoLevelCache = twoLevelCache;
        this.interactionCountReader = interactionCountReader;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
    }

    public PageResult<PostSummaryResponse> findPublished(
            int page,
            int pageSize,
            String sort,
            String category,
            String tag,
            String keyword
    ) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, sortOf(sort));
        Page<CommunityPost> posts = postRepository.findVisiblePosts(
                PostStatus.PUBLISHED,
                blankToNull(category),
                blankToNull(tag),
                blankToNull(keyword),
                pageable
        );
        // split-08：整页作者一次批量回填（Feign），避免逐帖单查的 N+1 跨服务调用
        Map<String, UserBriefDto> authors = userDirectory.findAuthors(
                posts.getContent().stream().map(CommunityPost::getAuthorId).toList());
        // M5/C1：整页媒体一次批量装载，避免逐帖单查的 DB 层 N+1
        Map<String, List<PostMediaResponse>> mediaByPost = mediaFor(
                posts.getContent().stream().map(CommunityPost::getId).toList());
        List<PostSummaryResponse> items = posts.getContent().stream()
                .map(post -> toSummary(post, authors, mediaByPost))
                .toList();
        PageMeta meta = new PageMeta(normalizedPage, normalizedPageSize, posts.getTotalElements(), posts.getTotalPages());
        return new PageResult<>(items, meta);
    }

    /**
     * 帖子详情读路径（切片 #13 加固）：内容字段走两级缓存（L1 Caffeine → L2 Redis → DB），
     * 点赞/收藏计数在组装响应时从 #11 实时 Redis 源叠加（ADR 0006 D3 计数解耦）。
     *
     * 为什么只缓存详情而不缓存分页列表？
     * 详情是「读多写少 + 单键高命中」的典型热点；分页列表参数组合多、命中率低，缓存收益小（且 rp-05 改游标分页）。
     *
     * lazyPost 的惰性是「命中路径零 DB 查询」的关键：
     * - 内容命中 L1/L2（或空值哨兵）→ loader 不执行 → 不查 DB；
     * - 计数命中 Redis（热帖读一次后即回填，TTL 86400）→ 基线 supplier 不执行 → 不查 DB；
     * 只有「内容全 miss 需重建」或「Redis 计数键 miss 需回填基线」才触发实体查询，
     * 且两者共享同一次加载（AtomicReference 记忆化），冷路径也只查一次 DB。
     *
     * 为什么 loader 查无帖子时返回 null 而不直接抛异常（rp-02）？
     * 抛异常会穿透 TwoLevelCache，令空值哨兵无从写入——不存在的 id 每次都会重新打到 DB（缓存穿透）。
     * 返回 null 让缓存层写下 __NULL__ 哨兵，第二次起命中哨兵直接返回 null、不再查 DB；
     * 「不存在」的对外语义（404）由本方法在拿到 null 内容后统一抛 ResourceNotFoundException。
     */
    public PostDetailResponse findPublishedById(String id) {
        AtomicReference<CommunityPost> postRef = new AtomicReference<>();
        Supplier<CommunityPost> lazyPost = () -> postRef.updateAndGet(cached -> cached != null ? cached
                : postRepository.findByIdAndStatus(id, PostStatus.PUBLISHED).orElse(null));

        PostDetailContent content = twoLevelCache.get(CacheNames.POST_DETAIL, id, PostDetailContent.class, () -> {
            CommunityPost post = lazyPost.get();
            if (post == null) {
                return null; // 查无此帖：返回 null 让缓存层写空值哨兵，而非在此抛异常穿透缓存
            }
            // 详情单帖：批量接口取单元素（内部契约只有 batch，无需为单查扩面）
            return toContent(post, userDirectory.findAuthors(List.of(post.getAuthorId())));
        });
        if (content == null) {
            // 内容 null = 本次查无 或 命中空值哨兵，二者对外语义一致：帖子不存在或已删除
            throw new ResourceNotFoundException("帖子不存在或已删除");
        }

        // 计数解耦：不进缓存，每次从 #11 实时 Redis 源读；Redis 计数键 miss 时用 DB 冗余列回填基线
        PostInteractionCounts counts = interactionCountReader.readForPost(id,
                () -> { CommunityPost p = lazyPost.get(); return p == null ? 0L : p.getLikeCount(); },
                () -> { CommunityPost p = lazyPost.get(); return p == null ? 0L : p.getFavoriteCount(); });
        return assembleDetail(content, counts.likeCount(), counts.favoriteCount());
    }

    /**
     * 帖子列表 keyset 游标翻页（切片 #13 / rp-05）：支持 latest / hot 两种排序键。
     *
     * 为什么与 offset 版 {@link #findPublished} 并存而不直接替换？
     * offset 端点（页码 UI、作者页/收藏/评论）仍依赖 totalItems/totalPages；游标版不查总数、只回答「下一页从哪起、
     * 还有没有」，专治深翻页退化。两者共存，由控制器按是否传 cursor/limit 区分模式（spec D9），控制爆炸半径。
     *
     * hasMore 探测：多取一条（limit+1），实取到 &gt; limit 则有下一页；nextCursor 由本页最后一条的排序键 + id 编码。
     */
    public CursorPageResult<PostSummaryResponse> findPublishedByCursor(
            String sort, String category, String tag, String keyword, String cursor, int limit) {
        int normalizedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        String categoryFilter = blankToNull(category);
        String tagFilter = blankToNull(tag);
        String keywordFilter = blankToNull(keyword);
        // 多取一条用于探测 hasMore；ORDER BY 已写在 @Query 里，故 Pageable 用 unsorted 只施加 limit
        Pageable pageable = PageRequest.of(0, normalizedLimit + 1);

        List<CommunityPost> rows;
        if ("hot".equalsIgnoreCase(sort)) {
            HotCursor c = decodeHotCursor(cursor);
            rows = postRepository.findHotByCursor(
                    PostStatus.PUBLISHED, categoryFilter, tagFilter, keywordFilter,
                    c != null,
                    c == null ? Long.MAX_VALUE : c.likeCount(),
                    c == null ? Long.MAX_VALUE : c.commentCount(),
                    c == null ? LocalDateTime.MAX : c.publishedAt(),
                    c == null ? "" : c.id(),
                    pageable);
        } else {
            LatestCursor c = decodeLatestCursor(cursor);
            rows = postRepository.findLatestByCursor(
                    PostStatus.PUBLISHED, categoryFilter, tagFilter, keywordFilter,
                    c != null,
                    c == null ? LocalDateTime.MAX : c.publishedAt(),
                    c == null ? "" : c.id(),
                    pageable);
        }

        if (rows.isEmpty()) {
            return CursorPageResult.empty();
        }
        boolean hasMore = rows.size() > normalizedLimit;
        List<CommunityPost> page = hasMore ? rows.subList(0, normalizedLimit) : rows;

        // 整页作者一次批量回填（Feign），沿用 offset 版的 N+1 规避
        Map<String, UserBriefDto> authors = userDirectory.findAuthors(
                page.stream().map(CommunityPost::getAuthorId).toList());
        // M5/C1：整页媒体一次批量装载，与作者回填同理
        Map<String, List<PostMediaResponse>> mediaByPost = mediaFor(
                page.stream().map(CommunityPost::getId).toList());
        List<PostSummaryResponse> items = page.stream()
                .map(post -> toSummary(post, authors, mediaByPost))
                .toList();
        String nextCursor = hasMore ? encodeCursor(sort, page.get(page.size() - 1)) : null;
        return new CursorPageResult<>(items, nextCursor, hasMore);
    }

    /** latest 游标：(publishedAt, id)。 */
    private record LatestCursor(LocalDateTime publishedAt, String id) {
    }

    /** hot 游标：(likeCount, commentCount, publishedAt, id)。 */
    private record HotCursor(long likeCount, long commentCount, LocalDateTime publishedAt, String id) {
    }

    private String encodeCursor(String sort, CommunityPost last) {
        if ("hot".equalsIgnoreCase(sort)) {
            return CursorCodec.encode(List.of(
                    Long.toString(last.getLikeCount()),
                    Long.toString(last.getCommentCount()),
                    last.getPublishedAt().toString(),
                    last.getId()));
        }
        return CursorCodec.encode(List.of(last.getPublishedAt().toString(), last.getId()));
    }

    private LatestCursor decodeLatestCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        List<String> tokens = CursorCodec.decode(cursor);
        if (tokens.size() != 2) {
            throw new AuthRequestException("INVALID_CURSOR", "帖子列表游标格式无效");
        }
        try {
            return new LatestCursor(LocalDateTime.parse(tokens.get(0)), tokens.get(1));
        } catch (RuntimeException e) {
            throw new AuthRequestException("INVALID_CURSOR", "帖子列表游标格式无效");
        }
    }

    private HotCursor decodeHotCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        List<String> tokens = CursorCodec.decode(cursor);
        if (tokens.size() != 4) {
            throw new AuthRequestException("INVALID_CURSOR", "帖子列表游标格式无效");
        }
        try {
            return new HotCursor(
                    Long.parseLong(tokens.get(0)),
                    Long.parseLong(tokens.get(1)),
                    LocalDateTime.parse(tokens.get(2)),
                    tokens.get(3));
        } catch (RuntimeException e) {
            throw new AuthRequestException("INVALID_CURSOR", "帖子列表游标格式无效");
        }
    }

    public PageResult<PostSummaryResponse> findPublishedByAuthor(String authorId, int page, int pageSize) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize, Sort.by(Sort.Order.desc("publishedAt")));
        Page<CommunityPost> posts = postRepository.findByAuthorIdAndStatus(authorId, PostStatus.PUBLISHED, pageable);
        Map<String, UserBriefDto> authors = userDirectory.findAuthors(List.of(authorId));
        // M5/C1：整页媒体一次批量装载
        Map<String, List<PostMediaResponse>> mediaByPost = mediaFor(
                posts.getContent().stream().map(CommunityPost::getId).toList());
        List<PostSummaryResponse> items = posts.getContent().stream()
                .map(post -> toSummary(post, authors, mediaByPost))
                .toList();
        return new PageResult<>(items, new PageMeta(normalizedPage, normalizedPageSize, posts.getTotalElements(), posts.getTotalPages()));
    }

    /**
     * 按 id 批量构建摘要，只返回 PUBLISHED 状态的帖子（审计 M9 / D2）。
     *
     * 为什么必须按状态过滤：方法名承诺的是 Published。timeline ZSet 里的
     * postId 不会随软删除而消失（删帖只改 status，不动 ZSet），
     * findAllById 也能查到 DELETED 的行——只 filter null 的话软删帖会
     * 漏进关注流，用户在自己的首页看到已删除内容。这正是原零断言测试
     * 注释里记成"已知局限"的那个缺陷（FeedTimelineIntegrationTest
     * .findFollowingFeed_filtersOutDeletedPosts 现在真正断言它）。
     *
     * 调用方：FeedService.findFollowingFeed / findFollowingFeedByCursor、
     * ProfileService 收藏列表。三处都要求"只给用户看已发布内容"。
     */
    public PageResult<PostSummaryResponse> findPublishedByIds(Page<String> ids) {
        List<CommunityPost> all = postRepository.findAllById(ids.getContent());
        // 先筛一次状态，后续的作者回填/媒体装载/组装都只处理已发布帖，
        // 避免同一个 filter 条件在三个 stream 里重复
        List<CommunityPost> posts = all.stream()
                .filter(post -> post.getStatus() == PostStatus.PUBLISHED)
                .toList();
        Map<String, CommunityPost> postsById = posts.stream()
                .collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        Map<String, UserBriefDto> authors = userDirectory.findAuthors(
                posts.stream().map(CommunityPost::getAuthorId).toList());
        // M5/C1：整页媒体一次批量装载
        Map<String, List<PostMediaResponse>> mediaByPost = mediaFor(
                posts.stream().map(CommunityPost::getId).toList());
        List<PostSummaryResponse> items = ids.getContent().stream()
                .map(postsById::get)
                .filter(java.util.Objects::nonNull)
                .map(post -> toSummary(post, authors, mediaByPost))
                .toList();
        return new PageResult<>(items, new PageMeta(ids.getNumber() + 1, ids.getSize(), ids.getTotalElements(), ids.getTotalPages()));
    }

    public long publishedPostCount(String authorId) {
        return postRepository.countByAuthorIdAndStatus(authorId, PostStatus.PUBLISHED);
    }

    public long publishedPostLikeCount(String authorId) {
        return postRepository.sumLikeCountByAuthorIdAndStatus(authorId, PostStatus.PUBLISHED);
    }

    private Sort sortOf(String sort) {
        if ("hot".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("commentCount"), Sort.Order.desc("publishedAt"));
        }
        return Sort.by(Sort.Order.desc("publishedAt"));
    }

    /**
     * 组装摘要。媒体由调用方批量预取（见 {@link #mediaFor}），不在本方法内单帖查询——
     * 那是 DB 层 N+1 的来源（审计 M5 / C1）。
     */
    private PostSummaryResponse toSummary(CommunityPost post, Map<String, UserBriefDto> authors,
                                          Map<String, List<PostMediaResponse>> mediaByPost) {
        List<PostMediaResponse> media = mediaByPost.getOrDefault(post.getId(), List.of());
        return new PostSummaryResponse(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(),
                userDirectory.toAuthor(post.getAuthorId(), authors), post.getPublishedAt(), post.getViewCount(), post.getLikeCount(),
                post.getFavoriteCount(), post.getCommentCount(), tagNames(post), coverUrl(media), media
        );
    }

    /** 单帖版（详情页等路径）：内部委托批量版，行为一致。 */
    private PostSummaryResponse toSummary(CommunityPost post, Map<String, UserBriefDto> authors) {
        return toSummary(post, authors, mediaFor(List.of(post.getId())));
    }

    /** 实体 → 内容缓存载体（不含 like/favorite 计数，计数解耦见 PostDetailContent 注释）。 */
    private PostDetailContent toContent(CommunityPost post, Map<String, UserBriefDto> authors) {
        List<PostMediaResponse> media = media(post.getId());
        return new PostDetailContent(
                post.getId(), post.getType(), post.getCategory(), post.getTitle(), post.getExcerpt(), post.getContent(),
                userDirectory.toAuthor(post.getAuthorId(), authors), post.getPublishedAt(), post.getViewCount(),
                post.getCommentCount(), tagNames(post), coverUrl(media), media
        );
    }

    /** 内容载体 + 实时计数 → 对外详情响应（计数在组装那一刻叠加，故永远是实时值、不受缓存 TTL 影响）。 */
    private PostDetailResponse assembleDetail(PostDetailContent content, long likeCount, long favoriteCount) {
        return new PostDetailResponse(
                content.id(), content.type(), content.category(), content.title(), content.excerpt(), content.content(),
                content.author(), content.publishedAt(), content.viewCount(), likeCount, favoriteCount,
                content.commentCount(), content.tags(), content.coverImageUrl(), content.media()
        );
    }

    private List<String> tagNames(CommunityPost post) {
        return post.getTags().stream().map(tag -> tag.getName()).sorted().toList();
    }

    private String coverUrl(List<PostMediaResponse> media) {
        return media.stream().findFirst().map(PostMediaResponse::url).orElse(null);
    }

    /**
     * 批量装载多帖的媒体（审计 M5 / C1：消除列表读路径的 DB 层 N+1）。
     *
     * 原实现对每帖调用 {@link #media(String)}，而它内部是
     * 「findByPostIdOrderBySortOrderAsc + findAllById」两次查询——一页 50 帖
     * = 1 列表 + 50×2 = 102 条 SQL（实测数字，见 PostListSqlCountIntegrationTest）。
     * 项目在 split-08 专门消除了跨服务 Feign 的 N+1，DB 层却原样存在，
     * 与 #13「读路径加固」的定位相悖。
     *
     * 批量版用两次查询覆盖整页：一次取全部关联（findByPostIdIn）、一次取全部资产
     * （findAllById），再在内存里按 postId 分组。SQL 数从 O(页大小) 降为常数。
     *
     * 顺带修掉原实现的 O(n²)：它对每个 asset 都全量扫一遍 associations 来找
     * sortOrder（filter().findFirst().orElseThrow()），且关联缺失时抛
     * NoSuchElementException（会落到 M1 的"无通用异常处理"缺口上变成 500）。
     * 这里先用一次遍历建 assetId → sortOrder 的 Map，缺失关联直接跳过。
     */
    private Map<String, List<PostMediaResponse>> mediaFor(Collection<String> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<PostMedia> associations = postMediaRepository.findByPostIdIn(postIds);
        if (associations.isEmpty()) {
            return Map.of();
        }
        Map<String, MediaAsset> assets = mediaAssetRepository.findAllById(
                        associations.stream().map(PostMedia::getAssetId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(MediaAsset::getId, Function.identity()));

        // 一次遍历建立 (postId, assetId) → sortOrder，避免嵌套 filter 的 O(n²)
        Map<String, Integer> sortOrderByKey = new HashMap<>();
        for (PostMedia association : associations) {
            sortOrderByKey.put(association.getPostId() + ":" + association.getAssetId(),
                    association.getSortOrder());
        }

        Map<String, List<PostMediaResponse>> result = new HashMap<>();
        for (PostMedia association : associations) {
            MediaAsset asset = assets.get(association.getAssetId());
            if (asset == null) {
                // 关联指向的资产不存在（数据不一致）：跳过而非抛异常。
                // 一个坏关联不该让整页帖子 500。
                continue;
            }
            Integer sortOrder = sortOrderByKey.get(association.getPostId() + ":" + association.getAssetId());
            if (sortOrder == null) {
                continue;
            }
            result.computeIfAbsent(association.getPostId(), key -> new ArrayList<>())
                    .add(new PostMediaResponse(
                            asset.getId(), publicBaseUrl + "/media/" + asset.getStorageKey(),
                            sortOrder, sortOrder == 0));
        }
        // 保持每帖内部按 sortOrder 升序（与原实现的 OrderBy 语义一致）
        result.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparingInt(PostMediaResponse::sortOrder))
                .toList());
        return result;
    }

    /** 单帖版：委托批量版，保证两条路径的行为完全一致。 */
    private List<PostMediaResponse> media(String postId) {
        return mediaFor(List.of(postId)).getOrDefault(postId, List.of());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

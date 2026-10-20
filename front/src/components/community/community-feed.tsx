"use client";
import Image from "next/image";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { BadgeCheck, Eye, Heart, MessageSquare, MoreHorizontal } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { ApiFallbackNote } from "@/components/community/api-fallback-note";
import { CommunityFollowButton } from "@/components/community/community-follow-button";
import { CommunityHeroCarousel, CommunityImageCarousel } from "@/components/community/community-carousel";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { CommunityReportDialog } from "@/components/community/community-report-dialog";
import { ApiError, fetchFollowingFeedByCursor, fetchPostInteractionsBatch, fetchPosts } from "@/lib/api";
import { useFavoriteMutation, useLikeMutation } from "@/lib/community-queries";
import { guides } from "@/lib/mock";
import { DEMO_AUTHOR_ID_BY_NAME, toGuide } from "@/lib/post-view";
import type { Guide } from "@/types/community";
const bannerSlides = [
  { alt: "鸣潮 2.6 版本前瞻", description: "版本情报 · 攻略与漂泊者记录", src: "/art/修-2.6版本PV预告图 5K.webp", title: "鸣潮" },
  { alt: "心灵海男漂地图", description: "探索地图 · 标记你的下一处回响", src: "/art/修-心灵海 男漂地图.webp", title: "心灵海" },
  { alt: "嘉贝莉娜角色唤取动画", description: "角色图鉴 · 记录每一次共鸣相遇", src: "/art/修-嘉贝莉娜  唤取动画.webp", title: "嘉贝莉娜" },
];
function Banner() {
  return <CommunityHeroCarousel label="社区头图轮播" slides={bannerSlides} />;
}
const postIdBySlug: Record<string, string> = {
  "changli-team": "10000000-0000-0000-0000-000000000001",
  "tower-24": "10000000-0000-0000-0000-000000000002",
  "camellya-echo": "10000000-0000-0000-0000-000000000003",
  "new-player-route": "10000000-0000-0000-0000-000000000004",
};
function feedCount(value: number) {
  return value >= 10000
    ? (value / 10000).toFixed(1).replace(/\.0$/, "") + "w"
    : value >= 1000
      ? (value / 1000).toFixed(1).replace(/\.0$/, "") + "k"
      : String(value);
}
/** guide.likes 是列表响应里的字符串计数（如 "5"），批量互动状态只给 liked/favorited，
 *  卡片显示的计数要拼回 PostInteraction，故在此解析为 number。 */
function parseLikeCount(value: string) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}
function PostMedia({ guide, href }: { guide: Guide; href: string }) {
  const sources = guide.mediaUrls ?? [];
  if (!sources.length) return null;
  // 多图帖子：宫格缩略图（库街区同款——单图才用大图，多图为方形小图网格，2 张两列、3 张起三列）
  if (sources.length >= 2) {
    return (
      <div className="post-media-grid" style={{ gridTemplateColumns: `repeat(${Math.min(sources.length, 3)}, 1fr)` }}>
        {sources.slice(0, 6).map((src, index) => (
          <Link className="post-media-grid-item" href={href} key={src + index} rel="noopener noreferrer" target="_blank">
            <Image alt={`${guide.title}配图 ${index + 1}`} fill sizes="160px" src={src} />
          </Link>
        ))}
      </div>
    );
  }
  return (
    <CommunityImageCarousel
      className="post-media post-media-carousel"
      href={href}
      images={sources.slice(0, 6).map((src) => ({ alt: `${guide.title}帖子配图`, src }))}
      label={`${guide.title}帖子配图轮播`}
    />
  );
}
function PostCard({ guide, interaction: initialInteraction }: { guide: Guide; interaction?: { liked: boolean; favorited: boolean } | null }) {
  const { liked, bookmarked, toggleLike, toggleBookmark, notify, requestLogin } = useCommunityDemo();
  const [menuOpen, setMenuOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const postId = postIdBySlug[guide.id] ?? (/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(guide.id) ? guide.id : undefined);
  // H-2：互动状态由 Feed 一次批量拉取后传下来，不再每卡自己发请求。
  // 原来的实现是每个 PostCard 各发一次 /posts/{id}/interactions，
  // 一屏 20 帖 = 21 个请求（浏览器 6 条并发，分 4 批完成）。
  //
  // 计数与状态分离：批量接口只返回 liked/favorited（列表响应已带 likeCount，
  // 两边重复返回会诱发"哪个才是真相"），所以卡片显示的计数始终取自
  // guide.likes（列表数据），initialInteraction 只提供"当前用户赞过没有"。
  //
  // 为什么不能把 initialInteraction 塞进 useState：批量状态是异步到达的，
  // prop 从 null 变成 {liked:true} 时 useState 的初始值不会重新求值——
  // 卡片会永远停在挂载那一刻的状态（这正是"首页显示未赞"的直接原因）。
  //
  // 也不再用"本地 override 覆盖 prop"：那只是把同一个 bug 从「挂载时冻结」
  // 降级为「每次操作后冻结」——override 写进去后没有清除路径，invalidate
  // 重取拿到的新 initialInteraction 会被旧 override 压住，用户在别处点赞
  // 后本页卡片仍显示旧值。mutation 的 onSuccess 已经 invalidate 了
  // feed-interaction-states，重取足以驱动 UI，本地不需要第二份真相。
  const interaction = initialInteraction ? { postId: postId ?? "", likeCount: parseLikeCount(guide.likes), favoriteCount: 0, ...initialInteraction } : null;
  const isBookmarked = interaction?.favorited ?? bookmarked.includes(guide.id);
  const likeMutation = useLikeMutation(postId ?? "");
  const favoriteMutation = useFavoriteMutation(postId ?? "");
  function change(kind: "like" | "favorite") {
    requestLogin(async () => {
      if (!postId || !interaction) {
        if (kind === "like") toggleLike(guide.id);
        else toggleBookmark(guide.id);
        return;
      }
      // 收藏和点赞一样走 mutation：onSuccess 会 invalidate 首页批量状态，
      // 去详情页时读到的是新值，不再"首页收藏了、详情页显示未收藏"
      // 两个 mutation 的变量类型不同，分开写而不是塞进同一个联合变量——
      // TS 会把联合推断成 never，then 也就取不到
      // 只发请求，不写本地状态：onSuccess 的 invalidate 会让批量状态重取，
      // 新值经 initialInteraction 流回本卡片
      if (kind === "like") await likeMutation.mutateAsync(interaction.liked ? "unlike" : "like");
      else await favoriteMutation.mutateAsync(interaction.favorited ? "unfavorite" : "favorite");
    });
  }
  const isLiked = interaction?.liked ?? liked.includes(guide.id);

  const likeLabel = interaction ? feedCount(interaction.likeCount) : guide.likes;
  const detailHref = `/guides/${guide.id}`;
  const authorRole = guide.category.includes("攻略")
    ? "攻略作者"
    : guide.category.includes("同人")
      ? "同人画师"
      : guide.category.includes("心得")
        ? "社区达人"
        : null;
  return (
    <article className="post-card">
      <div className="post-header">
        <div className={`author-avatar author-avatar--${guide.avatarTone}`}>{guide.authorMark}</div>
        <div className="author-info">
          <strong>
            {guide.author}
            <BadgeCheck size={13} />
            {authorRole && <span className="author-role-badge">{authorRole}</span>}
          </strong>
          <time>{guide.publishedAt}</time>
        </div>
        <CommunityFollowButton className="follow-button" fallbackKey={guide.author} targetUserId={DEMO_AUTHOR_ID_BY_NAME[guide.author]} />
        <div className="more-wrap">
          <button aria-expanded={menuOpen} aria-label="更多操作" className="more-button" onClick={() => setMenuOpen(!menuOpen)} type="button">
            <MoreHorizontal size={20} />
          </button>
          {menuOpen && (
            <div className="post-menu">
              <button
                onClick={() => {
                  change("favorite");
                  setMenuOpen(false);
                }}
                type="button"
              >
                {isBookmarked ? "取消收藏" : "收藏帖子"}
              </button>
              <button
                onClick={() => {
                  if (postId) setReportOpen(true);
                  else notify("当前内容暂不支持举报。");
                  setMenuOpen(false);
                }}
                type="button"
              >
                举报反馈
              </button>
            </div>
          )}
        </div>
      </div>
      <Link href={detailHref} rel="noopener noreferrer" target="_blank">
        <h2>{guide.title}</h2>
      </Link>
      <p className="post-excerpt">{guide.excerpt}</p>
      <PostMedia guide={guide} href={detailHref} />
      <div className="post-tags">
        {guide.tags.map((tag) => (
          <Link href={`/search?q=${encodeURIComponent(tag)}`} key={tag}>
            # {tag}
          </Link>
        ))}
      </div>
      <div className="post-actions">
        <span>
          <Eye size={17} />
          {guide.views}
        </span>
        <Link href={`${detailHref}#comments`} rel="noopener noreferrer" target="_blank">
          <MessageSquare size={17} />
          {guide.replies}
        </Link>
        <button className={isLiked ? "is-active" : ""} onClick={() => change("like")} type="button">
          <Heart fill={isLiked ? "currentColor" : "none"} size={18} />
          {likeLabel}
        </button>
      </div>
      {reportOpen && postId && (
        <CommunityReportDialog targetId={postId} targetType="POST" onClose={() => setReportOpen(false)} onSuccess={() => setReportOpen(false)} />
      )}
    </article>
  );
}
function guideFromListItem(post: Parameters<typeof toGuide>[0]) {
  const guide = toGuide(post);
  const demoMedia = guides.find((item) => item.id === guide.id || item.apiId === guide.id)?.mediaUrls;
  return guide.mediaUrls?.length ? guide : { ...guide, mediaUrls: demoMedia };
}
export function CommunityFeed({ query }: { query: string }) {
  const params = useSearchParams();
  const router = useRouter();
  const { requestLogin } = useCommunityDemo();
  const tab = params.get("tab") === "following" ? "following" : params.get("tab") === "latest" ? "latest" : "recommend";
  const [items, setItems] = useState<Guide[]>(guides);
  const [loadedKey, setLoadedKey] = useState("");
  const [apiUnavailable, setApiUnavailable] = useState(false);
  /* 关注流的失败要如实呈现：401 是未登录，其余是后端故障——都不能伪装成"没关注人"的空态。 */
  const [feedIssue, setFeedIssue] = useState<null | { kind: "unauthorized" | "error" }>(null);
  const [reloadKey, setReloadKey] = useState(0);
  /* slice-13/rp-06: following tab → cursor pagination ("加载更多" appends, no total count) */
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const requestKey = tab + "::" + query + "::" + reloadKey;
  const sort = tab === "recommend" ? "hot" : "latest";
  /* H-2/H-1：整页互动状态走 react-query（key 含 requestKey），
     这样点赞 mutation 的 invalidateQueries 能让它重新拉取——
     原先是 useState + useEffect，invalidate 叫不到它，首页点赞后
     卡片计数停留在旧值（写后读不一致的一条路径）。 */
  const interactionStatesQuery = useQuery({
    queryKey: ["feed-interaction-states", requestKey, items.map((guide) => guide.id).join(",")],
    queryFn: async () => {
      const ids = items.map((guide) => guide.id).filter((id) => /^[0-9a-f-]{36}$/i.test(id));
      if (!ids.length) return new Map<string, { liked: boolean; favorited: boolean }>();
      return fetchPostInteractionsBatch(ids);
    },
    enabled: items.length > 0 && items.some((guide) => /^[0-9a-f-]{36}$/i.test(guide.id)),
    retry: false,
    staleTime: 30_000,
  });
  const interactionStates = interactionStatesQuery.data ?? new Map<string, { liked: boolean; favorited: boolean }>();
  useEffect(() => {
    let active = true;
    /* slice-12: following tab → /api/v1/feed/following (Redis ZSet timeline); recommend/latest → GET /api/v1/posts */
    /* slice-13: following tab switches to cursor mode (limit triggers cursor), first page cursor=null */
    const request =
      tab === "following"
        ? fetchFollowingFeedByCursor({ limit: 20 }).then((result) => {
            if (active) {
              setNextCursor(result.nextCursor);
              setHasMore(result.hasMore);
            }
            return result.items;
          })
        : fetchPosts({ keyword: query, sort, pageSize: 20 });
    request
      .then((posts) => {
        if (!active) return;
        setItems(posts.map(guideFromListItem));
        setApiUnavailable(false);
        setFeedIssue(null);
        setLoadedKey(requestKey);
      })
      .catch((error) => {
        if (!active) return;
        if (tab === "following") {
          setFeedIssue({ kind: error instanceof ApiError && error.status === 401 ? "unauthorized" : "error" });
          setItems([]);
          setHasMore(false);
          setNextCursor(null);
          setApiUnavailable(false);
        } else {
          const keyword = query.trim().toLowerCase();
          const fallback = guides.filter((guide) => (guide.title + guide.excerpt + guide.category + guide.tags.join("")).toLowerCase().includes(keyword));
          setItems(tab === "latest" ? [...fallback].reverse() : fallback);
          setApiUnavailable(true);
          setFeedIssue(null);
        }
        setLoadedKey(requestKey);
      });
    return () => {
      active = false;
    };
  }, [query, reloadKey, requestKey, sort, tab]);
  /* "加载更多"：透传上一页 nextCursor 拉下一页，追加到现有列表（按 id 去重），hasMore=false 后隐藏按钮 */
  function loadMore() {
    if (tab !== "following" || !hasMore || !nextCursor || loadingMore) return;
    setLoadingMore(true);
    fetchFollowingFeedByCursor({ cursor: nextCursor, limit: 20 })
      .then((result) => {
        setItems((current) => {
          const seen = new Set(current.map((guide) => guide.id));
          const appended = result.items.map(guideFromListItem).filter((guide) => !seen.has(guide.id));
          return [...current, ...appended];
        });
        setNextCursor(result.nextCursor);
        setHasMore(result.hasMore);
      })
      .catch(() => {
        setHasMore(false);
      })
      .finally(() => setLoadingMore(false));
  }
  const loading = loadedKey !== requestKey;
  const visible = items;
  const tabs = [
    { key: "recommend", label: "默认", href: "/" },
    { key: "latest", label: "最新发布", href: "/?tab=latest" },
    { key: "following", label: "关注", href: "/?tab=following" },
  ];
  const emptyState = feedIssue ? (
    <div className="empty-state">
      {feedIssue.kind === "unauthorized" ? (
        <>
          <p>登录后查看关注的创作者动态</p>
          <button className="secondary-button" onClick={() => requestLogin()} type="button">
            去登录
          </button>
        </>
      ) : (
        <>
          <p>关注流加载失败，请稍后重试。</p>
          <button className="secondary-button" onClick={() => setReloadKey((key) => key + 1)} type="button">
            重新加载
          </button>
        </>
      )}
    </div>
  ) : (
    <div className="empty-state">
      <p>{tab === "following" ? "还没有关注的创作者。去推荐页看看吧。" : "没有找到相关帖子，换个关键词试试。"}</p>
      {tab === "following" && (
        <Link className="secondary-button" href="/">
          返回推荐页
        </Link>
      )}
    </div>
  );
  return (
    <section className="feed" id="feed" aria-label="社区内容流">
      <Banner />
      <div className="feed-tabs">
        {tabs.map((item) => (
          <button className={tab === item.key ? "is-active" : ""} key={item.key} onClick={() => router.push(item.href)} type="button">
            {item.label}
          </button>
        ))}
      </div>
      {loading ? (
        <div className="feed-status">正在整理漂泊者的最新内容…</div>
      ) : visible.length ? (
        <>
          {visible.map((guide) => (
            <PostCard guide={guide} key={guide.id} interaction={interactionStates.get(guide.id) ?? null} />
          ))}
          {tab === "following" &&
            (hasMore ? (
              <button className="feed-load-more" disabled={loadingMore} onClick={loadMore} type="button">
                {loadingMore ? "正在加载更多…" : "加载更多"}
              </button>
            ) : (
              <p className="feed-end-note">已经到底啦，关注的动态都看完了。</p>
            ))}
        </>
      ) : (
        emptyState
      )}
      {apiUnavailable && tab !== "following" && <ApiFallbackNote onRetry={() => setReloadKey((key) => key + 1)} />}
    </section>
  );
}

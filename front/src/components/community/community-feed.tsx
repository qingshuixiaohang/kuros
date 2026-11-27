"use client";
import Image from "next/image";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowRight, BadgeCheck, Eye, Heart, MessageSquare, MoreHorizontal, UsersRound } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { FloatingFocusManager, useClick, useDismiss, useFloating, useInteractions, useRole } from "@floating-ui/react";
import { useEffect, useRef, useState } from "react";
import { CommunityFollowButton } from "@/components/community/community-follow-button";
import { CommunityAvatar } from "@/components/community/community-avatar";
import { CommunityHeroCarousel, CommunityImageCarousel } from "@/components/community/community-carousel";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { CommunityReportDialog } from "@/components/community/community-report-dialog";
import { ApiError, fetchPostInteractionsBatch } from "@/lib/api";
import { useFavoriteMutation } from "@/lib/community-queries";
import { useSharedLike } from "@/components/community/like-operations-provider";
import { useCommunityFeedQuery } from "@/lib/feed-queries";
import { topicArtwork } from "@/lib/official-art";
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
function PostMediaGridTile({ alt, href, src }: { alt: string; href: string; src: string }) {
  const [failed, setFailed] = useState(false);
  return (
    <Link className="post-media-grid-item" href={href} rel="noopener noreferrer" target="_blank">
      <Image alt={alt} fill onError={() => setFailed(true)} sizes="160px" src={failed ? topicArtwork(alt) : src} unoptimized />
    </Link>
  );
}
function PostMedia({ guide, href }: { guide: Guide; href: string }) {
  const sources = guide.mediaUrls ?? [];
  if (!sources.length) return null;
  // 多图帖子：宫格缩略图（库街区同款——单图才用大图，多图为方形小图网格，2 张两列、3 张起三列）
  if (sources.length >= 2) {
    return (
      <div className="post-media-grid" style={{ gridTemplateColumns: `repeat(${Math.min(sources.length, 3)}, 1fr)` }}>
        {sources.slice(0, 6).map((src, index) => (
          <PostMediaGridTile alt={`${guide.title}配图 ${index + 1}`} href={href} key={src + index} src={src} />
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
function PostCard({
  guide,
  interaction: initialInteraction,
  interactionUnavailable = false,
}: {
  guide: Guide;
  interaction?: { liked: boolean; favorited: boolean } | null;
  interactionUnavailable?: boolean;
}) {
  const { liked, bookmarked, toggleLike, toggleBookmark, notify, requestLogin, user } = useCommunityDemo();
  const [menuOpen, setMenuOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const { context: menuContext, refs: menuRefs } = useFloating({ open: menuOpen, onOpenChange: setMenuOpen });
  const { setFloating: setMenuFloating, setReference: setMenuReference } = menuRefs;
  const menuClick = useClick(menuContext);
  const menuDismiss = useDismiss(menuContext, { outsidePressEvent: "pointerdown" });
  const menuRole = useRole(menuContext, { role: "menu" });
  const { getReferenceProps: getMenuTriggerProps, getFloatingProps: getMenuProps } = useInteractions([menuClick, menuDismiss, menuRole]);
  const postId = postIdBySlug[guide.id] ?? (/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(guide.id) ? guide.id : undefined);
  const interaction = initialInteraction ? { postId: postId ?? "", likeCount: parseLikeCount(guide.likes), favoriteCount: 0, ...initialInteraction } : null;
  const interactionPending = Boolean(postId && !interaction && !interactionUnavailable);
  const interactionDisabled = interactionUnavailable || Boolean(postId && !interaction);
  const isBookmarked = interactionUnavailable ? false : interaction ? interaction.favorited : postId ? false : bookmarked.includes(guide.id);
  const like = useSharedLike(postId ?? "", { liked: interaction?.liked ?? false, likeCount: interaction?.likeCount ?? parseLikeCount(guide.likes) });
  const favoriteMutation = useFavoriteMutation(postId ?? "");
  const menuWasOpen = useRef(false);
  useEffect(() => {
    if (menuWasOpen.current && !menuOpen) {
      const frame = requestAnimationFrame(() => {
        const reference = menuRefs.reference.current;
        if (reference instanceof HTMLElement) reference.focus();
      });
      menuWasOpen.current = menuOpen;
      return () => cancelAnimationFrame(frame);
    }
    menuWasOpen.current = menuOpen;
    return undefined;
  }, [menuOpen, menuRefs.reference]);
  function change(kind: "like" | "favorite") {
    requestLogin(async () => {
      if (!postId) {
        if (kind === "like") toggleLike(guide.id);
        else toggleBookmark(guide.id);
        return;
      }
      if (!interaction) return;
      try {
        if (kind === "like") {
          if (!user) {
            notify("登录后请再次点赞");
            return;
          }
          if (!like.start()) notify("点赞暂不可用，请稍后重试");
        } else await favoriteMutation.mutateAsync(interaction.favorited ? "unfavorite" : "favorite");
      } catch (error) {
        notify(error instanceof Error ? error.message : "操作失败，请稍后重试");
      }
    });
  }
  const isLiked = postId ? like.liked : liked.includes(guide.id);

  const likeLabel = interaction || like.pending ? feedCount(like.likeCount) : guide.likes;
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
        <CommunityAvatar avatarUrl={guide.avatarUrl} className={`author-avatar author-avatar--${guide.avatarTone}`} identity={guide.author} />
        <div className="author-info">
          <strong>
            {guide.author}
            <BadgeCheck size={13} />
            {authorRole && <span className="author-role-badge">{authorRole}</span>}
          </strong>
          <time>{guide.publishedAt}</time>
        </div>
        <CommunityFollowButton className="follow-button" fallbackKey={guide.author} targetUserId={guide.authorId ?? DEMO_AUTHOR_ID_BY_NAME[guide.author]} />
        <div className="more-wrap">
          <button aria-label="更多操作" className="more-button" ref={setMenuReference} type="button" {...getMenuTriggerProps()}>
            <MoreHorizontal size={20} />
          </button>
          {menuOpen && (
            <FloatingFocusManager context={menuContext} modal={false} returnFocus>
              <div
                aria-label="帖子操作"
                className="post-menu"
                ref={setMenuFloating}
                {...getMenuProps({
                  "aria-labelledby": undefined,
                  onBlur: (event) => {
                    if (!event.currentTarget.contains(event.relatedTarget)) setMenuOpen(false);
                  },
                })}
              >
                <button
                  disabled={interactionDisabled}
                  onClick={() => {
                    change("favorite");
                    setMenuOpen(false);
                  }}
                  role="menuitem"
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
                  role="menuitem"
                  type="button"
                >
                  举报反馈
                </button>
              </div>
            </FloatingFocusManager>
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
        <button
          aria-busy={interactionPending || undefined}
          aria-pressed={interactionDisabled ? undefined : isLiked}
          className={isLiked ? "is-active" : ""}
          disabled={interactionDisabled || like.pending}
          onClick={() => change("like")}
          type="button"
        >
          <Heart fill={isLiked ? "currentColor" : "none"} size={18} />
          {likeLabel}
        </button>
        {like.pending && <span role="status">点赞待确认</span>}
      </div>
      {reportOpen && postId && (
        <CommunityReportDialog targetId={postId} targetType="POST" onClose={() => setReportOpen(false)} onSuccess={() => setReportOpen(false)} />
      )}
    </article>
  );
}
function FollowingLoginGate({ onLogin }: { onLogin: () => void }) {
  return (
    <div className="following-login-gate">
      <div className="following-login-gate__copy">
        <span className="following-login-gate__eyebrow">
          <UsersRound size={15} /> 漂泊者的关注流
        </span>
        <h2>跟上你关注的创作者</h2>
        <p>登录后，这里会汇集他们新发布的攻略、探索记录和版本讨论。</p>
        <button className="following-login-gate__button" onClick={onLogin} type="button">
          登录后继续 <ArrowRight size={16} />
        </button>
      </div>
      <div aria-hidden="true" className="following-login-gate__art">
        <Image alt="" fill sizes="300px" src="/art/official/characters/changli-profile.jpg" />
        <span>FOLLOW THE WAVES</span>
      </div>
    </div>
  );
}
export function CommunityFeed({ query }: { query: string }) {
  const params = useSearchParams();
  const router = useRouter();
  const { loggedIn, requestLogin, user, sessionRestoring } = useCommunityDemo();
  const tab = params.get("tab") === "following" ? "following" : params.get("tab") === "latest" ? "latest" : "recommend";
  const feedQuery = useCommunityFeedQuery({
    tab,
    keyword: query,
    viewerId: user?.id ?? "guest",
    enabled: !sessionRestoring && (tab !== "following" || loggedIn),
  });
  const items = feedQuery.data?.items.map(toGuide) ?? [];
  const requestKey = tab + "::" + query;
  /* H-2/H-1：整页互动状态走 react-query（key 含 requestKey），
     这样点赞 mutation 的 invalidateQueries 能让它重新拉取——
     原先是 useState + useEffect，invalidate 叫不到它，首页点赞后
     卡片计数停留在旧值（写后读不一致的一条路径）。 */
  const interactionStatesQuery = useQuery({
    queryKey: ["feed-interaction-states", user?.id ?? "guest", requestKey, items.map((guide) => guide.id).join(",")],
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
  const interactionUnavailable = interactionStatesQuery.isError;
  const loading = feedQuery.isPending;
  const visible = items;
  const tabs = [
    { key: "recommend", label: "默认", href: "/" },
    { key: "latest", label: "最新发布", href: "/?tab=latest" },
    { key: "following", label: "关注", href: "/?tab=following" },
  ];
  const emptyState = feedQuery.isError ? (
    <div className="empty-state">
      <p>{tab === "following" ? "关注流加载失败，请稍后重试。" : "帖子加载失败，请稍后重试。"}</p>
      <button className="secondary-button" onClick={() => feedQuery.refetch()} type="button">
        重新加载
      </button>
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
          <button
            aria-current={tab === item.key ? "page" : undefined}
            className={tab === item.key ? "is-active" : ""}
            key={item.key}
            onClick={() => {
              if (item.key === "following" && !loggedIn) {
                requestLogin(() => router.push(item.href));
                return;
              }
              router.push(item.href);
            }}
            type="button"
          >
            {item.label}
          </button>
        ))}
      </div>
      {tab === "following" && !sessionRestoring && !loggedIn ? (
        <FollowingLoginGate onLogin={() => requestLogin()} />
      ) : tab === "following" && feedQuery.error instanceof ApiError && feedQuery.error.status === 401 ? (
        <FollowingLoginGate onLogin={() => requestLogin()} />
      ) : loading ? (
        <div className="feed-status" role="status">
          正在整理漂泊者的最新内容…
        </div>
      ) : visible.length ? (
        <>
          {interactionUnavailable ? (
            <div className="feed-status" role="status">
              <span>互动状态暂不可用，请重试。</span>
              <button className="secondary-button" onClick={() => interactionStatesQuery.refetch()} type="button">
                重新加载互动状态
              </button>
            </div>
          ) : null}
          {visible.map((guide) => (
            <PostCard guide={guide} interaction={interactionStates.get(guide.id) ?? null} interactionUnavailable={interactionUnavailable} key={guide.id} />
          ))}
          {feedQuery.isFetchNextPageError ? <p role="status">更多动态加载失败，请重试。</p> : null}
          {tab === "following" &&
            (feedQuery.hasNextPage ? (
              <button className="feed-load-more" disabled={feedQuery.isFetching} onClick={() => feedQuery.fetchNextPage()} type="button">
                {feedQuery.isFetchingNextPage ? "正在加载更多…" : feedQuery.isFetchNextPageError ? "重试加载更多" : "加载更多"}
              </button>
            ) : (
              <p className="feed-end-note">已经到底啦，关注的动态都看完了。</p>
            ))}
        </>
      ) : (
        emptyState
      )}
    </section>
  );
}

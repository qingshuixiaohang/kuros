"use client";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { BadgeCheck, Bell, BookOpen, Bookmark, Calculator, ChevronDown, ChevronRight, Compass, Eye, FileText, Heart, Home, LayoutGrid, LibraryBig, Menu, MessageSquare, MoreHorizontal, Newspaper, PenSquare, Search, Share2, Sparkles, Telescope, UsersRound, X } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { CommunityFollowButton } from "@/components/community/community-follow-button";
import { CommunityDemoProvider, useCommunityDemo } from "@/components/community/community-interactions";
import { CommunityReportDialog } from "@/components/community/community-report-dialog";
import { favoritePost, fetchPostInteractions, fetchPosts, likePost, unfavoritePost, unlikePost, type PostInteraction } from "@/lib/api";
import { guides, newsItems } from "@/lib/mock";
import { toGuide } from "@/lib/post-view";
import type { Guide } from "@/types/community";

const channels = [{ label: "推荐", icon: Home, href: "/" }, { label: "关注", icon: Heart, href: "/?tab=following" }, { label: "攻略", icon: FileText, href: "/guides" }, { label: "新手", icon: Sparkles, href: "/guides?category=新手攻略" }, { label: "官方", icon: Bell, href: "/news?category=官方公告" }, { label: "同人", icon: UsersRound, href: "/creations" }, { label: "资讯", icon: Newspaper, href: "/news" }];
const quickTools = [
  { title: "养成计算器", href: "/tools/calculator", icon: Calculator },
  { title: "声骸图鉴", href: "/echoes", icon: Compass },
  { title: "配队模拟", href: "/tools/team-builder", icon: UsersRound },
  { title: "角色图鉴", href: "/characters", icon: Telescope },
  { title: "版本资讯", href: "/news", icon: Newspaper },
  { title: "新手指南", href: "/guides?category=新手攻略", icon: BookOpen },
];
function isActive(label: string, path: string, params: URLSearchParams) { if (label === "推荐") return path === "/" && params.get("tab") !== "following"; if (label === "关注") return path === "/" && params.get("tab") === "following"; if (label === "新手") return path === "/guides" && params.get("category") === "新手攻略"; if (label === "攻略") return path.startsWith("/guides") && params.get("category") !== "新手攻略"; if (label === "官方") return path === "/news" && params.get("category") === "官方公告"; if (label === "资讯") return path.startsWith("/news") && params.get("category") !== "官方公告"; return path.startsWith("/creations"); }
function GameLogo() { return <div className="game-logo"><div className="game-logo-title">鸣潮</div><div className="game-logo-subtitle">WUTHERING WAVES</div><p>海潮回响 · 共鸣此间</p></div>; }
export function Sidebar({ drawer = false }: { drawer?: boolean }) { const path = usePathname(); const params = useSearchParams(); return <aside className={"sidebar " + (drawer ? "drawer-sidebar" : "")} aria-label="社区频道"><GameLogo /><nav className="channel-list">{channels.map(({ label, icon: Icon, href }) => <Link className={"channel-item " + (isActive(label, path, params) ? "is-active" : "")} href={href} key={label}><Icon size={20} strokeWidth={1.8} /><span>{label}</span></Link>)}</nav><div className="sidebar-footer"><div className="footer-rule" /><p>与漂泊者们<br />在此相遇</p></div></aside>; }
function MoreNavigation({ activeNav }: { activeNav: string }) { const [open, setOpen] = useState(false); const triggerRef = useRef<HTMLButtonElement>(null); const items = [{ href: "/characters", icon: UsersRound, title: "角色图鉴", detail: "定位与培养方向" }, { href: "/echoes", icon: LibraryBig, title: "声骸图鉴", detail: "套装与掉落查询" }, { href: "/tools", icon: LayoutGrid, title: "漂泊者工具箱", detail: "计算器与配队模拟" }, { href: "/publish", icon: Telescope, title: "发布内容", detail: "攻略、心得与创作" }]; useEffect(() => { if (!open) return; function closeWithFocus() { setOpen(false); window.requestAnimationFrame(() => triggerRef.current?.focus()); } function handleKeyDown(event: KeyboardEvent) { if (event.key === "Escape") { event.preventDefault(); closeWithFocus(); } } document.addEventListener("keydown", handleKeyDown); return () => document.removeEventListener("keydown", handleKeyDown); }, [open]); return <div className="top-more-wrap"><button className={activeNav === "tools" ? "is-active" : ""} onClick={() => setOpen((current) => !current)} ref={triggerRef} type="button" aria-expanded={open} aria-haspopup="menu">更多 <ChevronDown size={13} /></button>{open && <div className="top-more-menu" role="menu">{items.map(({ href, icon: Icon, title, detail }) => <Link href={href} key={href} onClick={() => setOpen(false)} role="menuitem"><span><Icon size={18} /></span><div><strong>{title}</strong><small>{detail}</small></div><ChevronRight size={15} /></Link>)}</div>}</div>; }
export function TopNavigation({ query, onQueryChange, onMenu, activeNav = "community" }: { query: string; onQueryChange: (query: string) => void; onMenu: () => void; activeNav?: string }) { const router = useRouter(); const [gameOpen, setGameOpen] = useState(false); const { loggedIn, requestLogin, logout } = useCommunityDemo(); function submitSearch(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (query.trim()) router.push("/search?q=" + encodeURIComponent(query.trim())); } return <header className="top-nav"><div className="top-nav-inner"><button className="mobile-menu-button" onClick={onMenu} type="button" aria-label="打开导航"><Menu size={22} /></button><Link className="top-logo" href="/" aria-label="鸣潮社区首页"><span>鸣潮</span><small>WUTHERING WAVES</small></Link><div className="game-selector"><button className="game-switcher" onClick={() => setGameOpen(!gameOpen)} type="button" aria-expanded={gameOpen}><span>战双帕弥什</span><span className="current-game">鸣潮</span><ChevronDown size={14} /></button>{gameOpen && <div className="game-popover"><strong>库洛游戏社区</strong><Link href="/">鸣潮 <small>当前社区</small></Link><span>战双帕弥什 <small>暂未接入 Demo</small></span></div>}</div><nav className="main-nav" aria-label="主导航"><Link className={activeNav === "community" ? "is-active" : ""} href="/">社区</Link><Link className={activeNav === "guides" ? "is-active" : ""} href="/guides">攻略</Link><Link className={activeNav === "news" ? "is-active" : ""} href="/news">资讯</Link><Link className={activeNav === "creations" ? "is-active" : ""} href="/creations">同人</Link><MoreNavigation activeNav={activeNav} /></nav><form className="top-search" onSubmit={submitSearch}><Search size={18} /><input value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder="搜索帖子、攻略、玩家..." aria-label="搜索帖子、攻略、玩家" /><button type="submit" aria-label="提交搜索" /></form><button className="mobile-search-button" onClick={() => router.push("/search")} type="button" aria-label="打开搜索"><Search size={19} /></button><Link className="top-action" href="/publish" aria-label="发布内容"><PenSquare size={20} /></Link><>{loggedIn ? <div aria-label="账户操作" className="top-login-group"><Link aria-label="打开个人中心" className="top-account-avatar" href="/profile" title="打开个人中心"><span className="login-avatar">漂</span></Link><Link className="top-login" href="/profile" title="打开个人中心"><span>个人中心</span></Link><button aria-label="退出登录" className="top-logout-button" onClick={logout} type="button">退出</button></div> : <button className="top-login" onClick={() => requestLogin()} type="button"><span className="login-avatar">漂</span><span>登录</span></button>}</></div></header>; }
function Banner() { return <section className="community-banner" id="top"><Image alt="鸣潮月夜遗迹" className="community-banner-image" fill priority sizes="(max-width: 720px) 100vw, 880px" src="/art/hero-tide.png" /><div className="banner-copy"><div className="banner-logo">鸣潮<small>WUTHERING WAVES</small></div><p>潮声不息 · 世界依旧辽阔</p></div></section>; }
const postIdBySlug: Record<string, string> = { "changli-team": "10000000-0000-0000-0000-000000000001", "tower-24": "10000000-0000-0000-0000-000000000002", "camellya-echo": "10000000-0000-0000-0000-000000000003", "new-player-route": "10000000-0000-0000-0000-000000000004" };
const authorIdByName: Record<string, string> = { "潮声档案员": "10000000-0000-0000-0000-000000000001", "无音区夜行者": "10000000-0000-0000-0000-000000000002", "今汐的留声机": "10000000-0000-0000-0000-000000000003", "漂泊者手册": "10000000-0000-0000-0000-000000000004" };
function feedCount(value: number) { return value >= 10000 ? (value / 10000).toFixed(1).replace(/\.0$/, "") + "w" : value >= 1000 ? (value / 1000).toFixed(1).replace(/\.0$/, "") + "k" : String(value); }
async function copyPostLink(postId: string) {
  const url = new URL(`/guides/${postId}`, window.location.origin).href;
  await navigator.clipboard?.writeText(url);
}
function PostMediaImage({ alt, src }: { alt: string; src: string }) {
  if (src.startsWith("/art/")) return <Image alt={alt} fill sizes="(max-width: 720px) 100vw, 720px" src={src} />;
  // Uploaded media is a user-controlled backend URL, so it cannot safely be preconfigured in Next's image allowlist.
  // eslint-disable-next-line @next/next/no-img-element
  return <img alt={alt} loading="lazy" src={src} />;
}

function PostMedia({ guide, href }: { guide: Guide; href: string }) {
  const sources = guide.mediaUrls ?? [];
  if (!sources.length) return null;
  if (sources.length === 1) return <Link aria-label={`${guide.title}帖子配图`} className="post-media post-media--single" href={href} rel="noopener noreferrer" target="_blank"><PostMediaImage alt={`${guide.title}帖子配图`} src={sources[0]} /></Link>;
  return <div aria-label={`${guide.title}帖子配图`} className="post-media post-media--grid">{sources.slice(0, 3).map((src, index) => <Link aria-label={`${guide.title}帖子配图 ${index + 1}`} href={href} key={src} rel="noopener noreferrer" target="_blank"><PostMediaImage alt={`${guide.title}帖子配图 ${index + 1}`} src={src} /></Link>)}</div>;
}
function PostCard({ guide }: { guide: Guide }) {
  const { liked, bookmarked, toggleLike, toggleBookmark, notify, requestLogin } = useCommunityDemo();
  const [menuOpen, setMenuOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const postId = postIdBySlug[guide.id] ?? (/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(guide.id) ? guide.id : undefined);
  const [interaction, setInteraction] = useState<PostInteraction | null>(null);
  useEffect(() => {
    if (!postId) return;
    let active = true;
    fetchPostInteractions(postId).then((result) => { if (active) setInteraction(result); }).catch(() => { /* The home feed remains usable with its seeded data. */ });
    return () => { active = false; };
  }, [postId]);
  function change(kind: "like" | "favorite") {
    requestLogin(() => {
      if (!postId || !interaction) {
        if (kind === "like") toggleLike(guide.id); else toggleBookmark(guide.id);
        return;
      }
      const action = kind === "like" ? (interaction.liked ? unlikePost : likePost) : (interaction.favorited ? unfavoritePost : favoritePost);
      void action(postId).then((result) => {
        setInteraction(result);
        if (kind === "like") toggleLike(guide.id); else toggleBookmark(guide.id);
      }).catch((error) => notify(error instanceof Error ? error.message : "操作失败，请稍后重试"));
    });
  }
  const isLiked = interaction?.liked ?? liked.includes(guide.id);
  const isBookmarked = interaction?.favorited ?? bookmarked.includes(guide.id);
  const likeLabel = interaction ? feedCount(interaction.likeCount) : guide.likes;
  const detailHref = `/guides/${guide.id}`;
  async function share() {
    try {
      await copyPostLink(guide.id);
      notify("链接已复制，可以分享给你的队友。");
    } catch {
      notify("复制失败，请手动复制帖子地址。");
    }
  }
  return <article className="post-card">
    <div className="post-header">
      <div className={`author-avatar author-avatar--${guide.avatarTone}`}>{guide.authorMark}</div>
      <div className="author-info"><strong>{guide.author}<BadgeCheck size={13} /></strong><time>{guide.publishedAt}</time></div>
      <CommunityFollowButton className="follow-button" fallbackKey={guide.author} targetUserId={authorIdByName[guide.author]} />
      <div className="more-wrap">
        <button aria-expanded={menuOpen} aria-label="更多操作" className="more-button" onClick={() => setMenuOpen(!menuOpen)} type="button"><MoreHorizontal size={20} /></button>
        {menuOpen && <div className="post-menu">
          <button onClick={() => { change("favorite"); setMenuOpen(false); }} type="button">{isBookmarked ? "取消收藏" : "收藏帖子"}</button>
          <button onClick={() => { if (postId) setReportOpen(true); else notify("当前内容暂不支持举报。"); setMenuOpen(false); }} type="button">举报反馈</button>
        </div>}
      </div>
    </div>
    <Link href={detailHref} rel="noopener noreferrer" target="_blank"><h2>{guide.title}</h2></Link>
    <p className="post-excerpt">{guide.excerpt}</p>
    <PostMedia guide={guide} href={detailHref} />
    <div className="post-tags">{guide.tags.map((tag) => <Link href={`/search?q=${encodeURIComponent(tag)}`} key={tag}># {tag}</Link>)}</div>
    <div className="post-actions">
      <span><Eye size={17} />{guide.views}</span>
      <Link href={`${detailHref}#comments`} rel="noopener noreferrer" target="_blank"><MessageSquare size={17} />{guide.replies}</Link>
      <button className={isLiked ? "is-active" : ""} onClick={() => change("like")} type="button"><Heart fill={isLiked ? "currentColor" : "none"} size={18} />{likeLabel}</button>
      <button className={`post-action-label ${isBookmarked ? "is-bookmarked" : ""}`} onClick={() => change("favorite")} type="button"><Bookmark fill={isBookmarked ? "currentColor" : "none"} size={17} />{isBookmarked ? "已收藏" : "收藏"}</button>
      <button className="post-action-label" onClick={() => void share()} type="button"><Share2 size={17} />分享</button>
    </div>
    {reportOpen && postId && <CommunityReportDialog targetId={postId} targetType="POST" onClose={() => setReportOpen(false)} onSuccess={() => setReportOpen(false)} />}
  </article>;
}
function guideFromListItem(post: Parameters<typeof toGuide>[0]) {
  const guide = toGuide(post);
  const demoMedia = guides.find((item) => item.id === guide.id || item.apiId === guide.id)?.mediaUrls;
  return guide.mediaUrls?.length ? guide : { ...guide, mediaUrls: demoMedia };
}
function Feed({ query }: { query: string }) { const params = useSearchParams(); const router = useRouter(); const { followed } = useCommunityDemo(); const tab = params.get("tab") === "following" ? "following" : params.get("tab") === "latest" ? "latest" : "recommend"; const [items, setItems] = useState<Guide[]>(guides); const [loadedKey, setLoadedKey] = useState(""); const [apiUnavailable, setApiUnavailable] = useState(false); const requestKey = tab + "::" + query; const sort = tab === "recommend" ? "hot" : "latest"; useEffect(() => { let active = true; fetchPosts({ keyword: query, sort, pageSize: 20 }).then((posts) => { if (!active) return; setItems(posts.map(guideFromListItem)); setApiUnavailable(false); setLoadedKey(requestKey); }).catch(() => { if (!active) return; const keyword = query.trim().toLowerCase(); const fallback = guides.filter((guide) => (guide.title + guide.excerpt + guide.category + guide.tags.join("")).toLowerCase().includes(keyword)); setItems(tab === "latest" ? [...fallback].reverse() : fallback); setApiUnavailable(true); setLoadedKey(requestKey); }); return () => { active = false; }; }, [query, requestKey, sort, tab]); const loading = loadedKey !== requestKey; const visible = tab === "following" ? items.filter((guide) => followed.includes(guide.author)) : items; const tabs = [{ key: "recommend", label: "推荐", href: "/" }, { key: "latest", label: "最新", href: "/?tab=latest" }, { key: "following", label: "关注", href: "/?tab=following" }]; return <section className="feed" id="feed" aria-label="社区内容流"><Banner /><div className="feed-tabs">{tabs.map((item) => <button className={tab === item.key ? "is-active" : ""} key={item.key} onClick={() => router.push(item.href)} type="button">{item.label}</button>)}</div>{loading ? <div className="feed-status">正在整理漂泊者的最新内容…</div> : visible.length ? visible.map((guide) => <PostCard guide={guide} key={guide.id} />) : <div className="empty-state"><p>{tab === "following" ? "还没有关注的创作者。去推荐页看看吧。" : "没有找到相关帖子，换个关键词试试。"}</p>{tab === "following" && <Link className="secondary-button" href="/">返回推荐页</Link>}</div>}{apiUnavailable && <p className="api-fallback-note">后端暂不可用，当前显示本地 Demo 数据。</p>}</section>; }
export function RightRail() {
  const [tab, setTab] = useState<"recommend" | "news">("recommend");
  const items = tab === "recommend" ? newsItems.slice(0, 5) : newsItems.filter((item) => item.category === "官方公告" || item.category === "版本前瞻").slice(0, 5);
  return <aside className="right-rail" aria-label="推荐与工具">
    <section className="right-panel" id="news">
      <div className="panel-tabs"><button className={tab === "recommend" ? "is-active" : ""} onClick={() => setTab("recommend")} type="button">推荐</button><button className={tab === "news" ? "is-active" : ""} onClick={() => setTab("news")} type="button">资讯</button></div>
      <div className="ranking-list">{items.map((item) => <Link className="ranking-item" href={"/news/" + item.id} key={item.id}><span className={"rank rank-" + item.rank}>{item.rank}</span><span className="ranking-title">{item.title}</span>{item.hot ? <b>热</b> : null}</Link>)}</div>
    </section>
    <section aria-label="快捷工具" className="right-panel tools-panel" id="tools">
      <div className="panel-title"><h2>实用工具</h2><Link href="/tools">更多 <ChevronRight size={14} /></Link></div>
      <ul aria-label="工具列表" className="tool-grid">{quickTools.map(({ title, href, icon: Icon }) => <li key={title}><Link className="tool-grid-item" href={href}><span className="tool-icon"><Icon size={22} strokeWidth={1.75} /></span><strong>{title}</strong></Link></li>)}</ul>
    </section>
    <div className="right-quote"><span>“</span><p>潮水会记得每一个漂泊者的足迹。</p><small>— 鸣潮</small></div>
  </aside>;
}
export function useCommunityDrawer(open: boolean, setOpen: (value: boolean) => void) {
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const drawerCloseRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) {
      const previous = previousFocusRef.current;
      if (previous) window.requestAnimationFrame(() => previous.focus());
      previousFocusRef.current = null;
      return;
    }
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusFrame = window.requestAnimationFrame(() => drawerCloseRef.current?.focus());
    function getFocusableElements() {
      return Array.from(drawerRef.current?.querySelectorAll<HTMLElement>("a[href], button:not([disabled]), input:not([disabled]), select, textarea") ?? []);
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setOpen(false);
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = getFocusableElements();
      const first = focusable[0];
      const last = focusable.at(-1);
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => { window.cancelAnimationFrame(focusFrame); document.removeEventListener("keydown", handleKeyDown); };
  }, [open, setOpen]);
  return { drawerCloseRef, drawerRef };
}
function HomeContent() { const [drawerOpen, setDrawerOpen] = useState(false); const [query, setQuery] = useState(""); const { drawerCloseRef, drawerRef } = useCommunityDrawer(drawerOpen, setDrawerOpen); return <main className="app-shell"><TopNavigation query={query} onQueryChange={setQuery} onMenu={() => setDrawerOpen(true)} />{drawerOpen && <div className="drawer-backdrop" onClick={() => setDrawerOpen(false)}><div aria-label="社区频道" aria-modal="true" className="mobile-drawer" onClick={(event) => event.stopPropagation()} ref={drawerRef} role="dialog"><div className="drawer-header"><span>频道</span><button onClick={() => setDrawerOpen(false)} ref={drawerCloseRef} type="button" aria-label="关闭导航"><X size={20} /></button></div><Sidebar drawer /></div></div>}<div className="page-grid"><Sidebar /><div className="main-column"><Feed query={query} /></div><RightRail /></div></main>; }
export function CommunityHome() { return <CommunityDemoProvider><HomeContent /></CommunityDemoProvider>; }

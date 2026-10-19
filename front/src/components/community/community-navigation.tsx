"use client";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import {
  AtSign,
  Bell,
  ChevronDown,
  ChevronRight,
  FileText,
  Heart,
  Home,
  LayoutGrid,
  LibraryBig,
  LogOut,
  Menu,
  MessageSquare,
  Newspaper,
  PenSquare,
  Search,
  Shield,
  Sparkles,
  UserRound,
  UsersRound,
} from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { fetchMyProfile } from "@/lib/api";
const channels = [
  { label: "推荐", icon: Home, href: "/" },
  { label: "关注", icon: Heart, href: "/?tab=following" },
  { label: "攻略", icon: FileText, href: "/guides" },
  { label: "新手", icon: Sparkles, href: "/guides?category=新手攻略" },
  { label: "角色图鉴", icon: UsersRound, href: "/characters" },
  { label: "声骸图鉴", icon: LibraryBig, href: "/echoes" },
  { label: "官方", icon: Bell, href: "/news?category=官方公告" },
  { label: "同人", icon: UsersRound, href: "/creations" },
  { label: "资讯", icon: Newspaper, href: "/news" },
  { label: "工具箱", icon: LayoutGrid, href: "/tools" },
];
function isActive(label: string, path: string, params: URLSearchParams) {
  if (label === "推荐") return path === "/" && params.get("tab") !== "following";
  if (label === "关注") return path === "/" && params.get("tab") === "following";
  if (label === "新手") return path === "/guides" && params.get("category") === "新手攻略";
  if (label === "攻略") return path.startsWith("/guides") && params.get("category") !== "新手攻略";
  if (label === "官方") return path === "/news" && params.get("category") === "官方公告";
  if (label === "资讯") return path.startsWith("/news") && params.get("category") !== "官方公告";
  return path.startsWith("/creations");
}
function GameLogo() {
  return (
    <div className="game-logo">
      <Image alt="鸣潮" className="game-logo-wordmark" height={72} priority src="/brand/wuthering-waves.png" width={114} />
      <p>海潮回响 · 共鸣此间</p>
    </div>
  );
}
export function Sidebar({ drawer = false }: { drawer?: boolean }) {
  const path = usePathname();
  const params = useSearchParams();
  return (
    <aside className={"sidebar " + (drawer ? "drawer-sidebar" : "")} aria-label="社区频道">
      <GameLogo />
      <nav className="channel-list">
        {channels.map(({ label, icon: Icon, href }) => (
          <Link className={"channel-item " + (isActive(label, path, params) ? "is-active" : "")} href={href} key={label}>
            <Icon size={22} strokeWidth={1.6} />
            <span>{label}</span>
          </Link>
        ))}
      </nav>
    </aside>
  );
}
export function TopNavigation({ query, onQueryChange, onMenu }: { query: string; onQueryChange: (query: string) => void; onMenu: () => void }) {
  const router = useRouter();
  const [gameOpen, setGameOpen] = useState(false);
  const { loggedIn, user, requestLogin, logout } = useCommunityDemo();
  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (query.trim()) router.push("/search?q=" + encodeURIComponent(query.trim()));
  }
  // 库街区同款：鼠标悬停头像即弹出账户面板（昵称/UID + 关注/粉丝/获赞 + 个人中心/退出）；
  // 移出头像与面板 180ms 后收起（缓冲防止斜穿面板时闪关）；Esc/点击外部也可关闭
  const [accountOpen, setAccountOpen] = useState(false);
  const [bellOpen, setBellOpen] = useState(false);
  const bellWrapRef = useRef<HTMLDivElement>(null);
  const accountWrapRef = useRef<HTMLDivElement>(null);
  const accountCloseTimerRef = useRef<number | undefined>(undefined);
  const openAccount = useCallback(() => {
    window.clearTimeout(accountCloseTimerRef.current);
    setAccountOpen(true);
  }, []);
  const scheduleCloseAccount = useCallback(() => {
    window.clearTimeout(accountCloseTimerRef.current);
    accountCloseTimerRef.current = window.setTimeout(() => setAccountOpen(false), 180);
  }, []);
  useEffect(() => () => window.clearTimeout(accountCloseTimerRef.current), []);
  useEffect(() => {
    if (!bellOpen) return;
    function onPointerDown(event: MouseEvent) {
      if (bellWrapRef.current && !bellWrapRef.current.contains(event.target as Node)) setBellOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setBellOpen(false);
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [bellOpen]);
  const bellItems = [
    { href: "/profile?tab=comments", icon: MessageSquare, label: "评论" },
    { href: "/profile?tab=comments", icon: AtSign, label: "@我" },
    { href: "/profile?tab=posts", icon: Heart, label: "点赞" },
    { href: "/news", icon: Sparkles, label: "系统通知" },
  ];
  const accountOverview = useQuery({
    queryKey: ["account-menu-overview"],
    queryFn: () => fetchMyProfile(),
    enabled: loggedIn && accountOpen,
    staleTime: 30_000,
    retry: false,
  });
  useEffect(() => {
    if (!accountOpen) return;
    function onPointerDown(event: MouseEvent) {
      if (accountWrapRef.current && !accountWrapRef.current.contains(event.target as Node)) setAccountOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setAccountOpen(false);
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [accountOpen]);
  return (
    <header className="top-nav">
      <div className="top-nav-inner">
        <button className="mobile-menu-button" onClick={onMenu} type="button" aria-label="打开导航">
          <Menu size={22} />
        </button>
        <Link className="top-logo" href="/" aria-label="鸣潮社区首页">
          <Image alt="" className="top-logo-icon" height={26} priority src="/brand/kurobbs-icon.png" width={26} />
          <Image alt="鸣潮" className="top-logo-wordmark" height={26} priority src="/brand/wuthering-waves-white.png" width={42} />
        </Link>
        <div className="game-selector">
          <button className="game-switcher" onClick={() => setGameOpen(!gameOpen)} type="button" aria-expanded={gameOpen}>
            <span>战双帕弥什</span>
            <span className="current-game">鸣潮</span>
            <ChevronDown size={14} />
          </button>
          {gameOpen && (
            <div className="game-popover">
              <strong>库洛游戏社区</strong>
              <Link href="/">
                鸣潮 <small>当前社区</small>
              </Link>
              <span>
                战双帕弥什 <small>暂未接入 Demo</small>
              </span>
            </div>
          )}
        </div>
        <form className="top-search" onSubmit={submitSearch}>
          <Search size={18} />
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="搜索帖子、攻略、玩家..."
            aria-label="搜索帖子、攻略、玩家"
          />
          <button type="submit" aria-label="提交搜索" />
        </form>
        <button className="mobile-search-button" onClick={() => router.push("/search")} type="button" aria-label="打开搜索">
          <Search size={19} />
        </button>
        <Link className="top-action" href="/publish" aria-label="发布内容">
          <PenSquare size={20} />
        </Link>
        {user?.role === "ADMIN" && (
          <Link aria-label="管理入口" className="top-admin-link" href="/admin/reports" title="举报审核台">
            <Shield size={16} />
            管理
          </Link>
        )}
        {loggedIn && (
          <div className="top-bell-wrap" ref={bellWrapRef}>
            <button
              className="top-bell"
              onClick={() => setBellOpen((current) => !current)}
              aria-expanded={bellOpen}
              aria-haspopup="menu"
              aria-label="消息通知"
              type="button"
            >
              <Bell size={20} />
            </button>
            {bellOpen && (
              <div className="bell-menu" role="menu" aria-label="消息通知">
                {bellItems.map(({ href, icon: Icon, label }) => (
                  <Link className="bell-item" href={href} key={label} onClick={() => setBellOpen(false)} role="menuitem">
                    <Icon size={16} />
                    <span>{label}</span>
                    <ChevronRight className="bell-item-chevron" size={14} />
                  </Link>
                ))}
                <p className="bell-note">新消息会在这里提醒</p>
              </div>
            )}
          </div>
        )}
        <>
          {loggedIn ? (
            <div aria-label="账户操作" className="top-login-group" ref={accountWrapRef} onMouseEnter={openAccount} onMouseLeave={scheduleCloseAccount}>
              <button
                className="top-account-avatar"
                onClick={openAccount}
                aria-expanded={accountOpen}
                aria-haspopup="menu"
                aria-label="打开账户菜单"
                title="账户菜单"
              >
                <span className="login-avatar">漂</span>
              </button>
              {accountOpen && (
                <div className="account-menu" role="menu" aria-label="账户面板">
                  <div className="account-menu-head">
                    <span className="account-menu-avatar">漂</span>
                    <div>
                      <strong>{user?.nickname ?? "漂泊者"}</strong>
                      <small title={user?.id}>UID: {user?.id}</small>
                    </div>
                  </div>
                  <div className="account-menu-stats">
                    <div>
                      <b>{accountOverview.data?.following.meta?.totalItems ?? "—"}</b>
                      <span>关注</span>
                    </div>
                    <div>
                      <b>{accountOverview.data?.fans.meta?.totalItems ?? "—"}</b>
                      <span>粉丝</span>
                    </div>
                    <div>
                      <b>{accountOverview.data?.stats.likeCount ?? "—"}</b>
                      <span>获赞</span>
                    </div>
                  </div>
                  <Link className="account-menu-item" href="/profile" onClick={() => setAccountOpen(false)} role="menuitem">
                    <UserRound size={16} />
                    <span>个人中心</span>
                    <ChevronRight size={14} className="account-menu-chevron" />
                  </Link>
                  <button className="account-menu-item" onClick={logout} role="menuitem">
                    <LogOut size={16} />
                    <span>退出登录</span>
                  </button>
                </div>
              )}
            </div>
          ) : (
            <button className="top-login" onClick={() => requestLogin()} type="button">
              <span className="login-avatar">漂</span>
              <span>登录</span>
            </button>
          )}
        </>
      </div>
    </header>
  );
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
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open, setOpen]);
  return { drawerCloseRef, drawerRef };
}

"use client";

import Image from "next/image";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Award, Bookmark, ChevronRight, FileText, LoaderCircle, MapPin, MessageCircle, UserRound, UsersRound, type LucideIcon } from "lucide-react";
import { CommunityPageFrame } from "@/components/community/community-pages";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { fetchMyProfile, type ApiPost, type ProfileComment, type ProfileOverview } from "@/lib/api";

const fallbackAvatar = "/art/character-lavender.png";
const coverByGuide: Record<string, string> = {
  "changli-team": "/art/guide-sword.png",
  "tower-24": "/art/guide-tower.png",
  "camellya-echo": "/art/guide-flower.png",
  "new-player-route": "/art/guide-coast.png",
};
const slugByPostId: Record<string, string> = {
  "10000000-0000-0000-0000-000000000001": "changli-team",
  "10000000-0000-0000-0000-000000000002": "tower-24",
  "10000000-0000-0000-0000-000000000003": "camellya-echo",
  "10000000-0000-0000-0000-000000000004": "new-player-route",
};

const profileTabs = [
  { key: "posts", label: "帖子", icon: FileText },
  { key: "comments", label: "评论", icon: MessageCircle },
  { key: "favorites", label: "收藏", icon: Bookmark },
  { key: "following", label: "关注", icon: UserRound },
  { key: "fans", label: "粉丝", icon: UsersRound },
] as const;

type ProfileTab = (typeof profileTabs)[number]["key"];

function postSlug(postId: string) { return slugByPostId[postId] ?? postId; }
function formatDate(value: string) { return value.length >= 10 ? value.slice(5, 10).replace("-", "/") : value; }
function safeAvatar(avatarUrl: string | null) { return avatarUrl?.startsWith("/") ? avatarUrl : fallbackAvatar; }

function ProfileNavigation({ activeTab }: { activeTab: ProfileTab }) {
  return <aside className="profile-navigation"><div className="profile-navigation-heading"><UserRound size={19} /><h2>个人中心</h2></div><nav aria-label="个人中心导航">{profileTabs.map(({ key, label, icon: Icon }) => <Link className={activeTab === key ? "is-active" : ""} href={`/profile?tab=${key}`} key={key}><Icon size={18} strokeWidth={1.8} /><span>{label}</span>{activeTab === key && <ChevronRight size={15} />}</Link>)}</nav></aside>;
}

function ProfileHero({ overview }: { overview: ProfileOverview }) {
  const { followed, toggleFollow } = useCommunityDemo();
  const { profile, stats } = overview;
  const isFollowed = followed.includes(profile.id);
  return <section className="profile-hero"><div className="profile-identity"><div className="profile-avatar"><Image alt={`${profile.nickname}头像`} fill sizes="116px" src={safeAvatar(profile.avatarUrl)} /></div><div className="profile-copy"><div className="profile-name-line"><h1>{profile.nickname}</h1>{profile.postCount > 0 && <span className="profile-author-mark"><Award size={13} />攻略作者</span>}</div><p className="profile-location"><MapPin size={14} />鸣潮 · 漂泊者档案</p><div className="profile-stat-line"><span><b>{stats.postCount}</b>帖子</span><span><b>{stats.commentCount}</b>评论</span><span><b>{stats.likeCount}</b>获赞</span></div><p className="profile-bio">{profile.bio || "还没有留下个人介绍。"}</p><button className={`profile-follow-button ${isFollowed ? "is-followed" : ""}`} onClick={() => toggleFollow(profile.id)} type="button">{isFollowed ? "已关注" : "关注"}</button></div></div><div className="profile-game-card"><div className="profile-game-card-top"><div className="profile-mini-avatar">潮</div><div><strong>{profile.nickname}</strong><small>鸣潮社区档案</small></div><span className="profile-game-wave">鸣潮</span></div><div className="profile-game-stats"><span><b>{stats.postCount}</b>公开帖子</span><span><b>{stats.commentCount}</b>评论记录</span><span><b>{stats.likeCount}</b>累计获赞</span><span><b>{profile.postCount > 0 ? "作者" : "玩家"}</b>社区身份</span></div></div></section>;
}

function ProfilePostRow({ post }: { post: ApiPost }) {
  const slug = postSlug(post.id);
  return <Link className="profile-post-row" href={`/guides/${slug}`} rel="noopener noreferrer" target="_blank"><div className="author-avatar author-avatar--dark">{post.author.nickname.slice(0, 1)}</div><div className="profile-post-row-copy"><div className="profile-post-row-meta"><strong>{post.author.nickname}</strong><time>{formatDate(post.publishedAt)} · 鸣潮</time></div><h3>{post.title}</h3><p>{post.excerpt}</p><div className="profile-post-row-stats"><span>{post.category}</span><span>{post.viewCount} 阅读</span><span>{post.commentCount} 评论</span></div></div><div className="profile-post-thumbnail"><Image alt="" fill sizes="140px" src={coverByGuide[slug] ?? "/art/guide-coast.png"} /></div><ChevronRight className="profile-row-arrow" size={17} /></Link>;
}

function ProfileEmpty({ icon: Icon, title, description }: { icon: LucideIcon; title: string; description: string }) {
  return <div className="profile-empty-panel"><Icon size={30} /><strong>{title}</strong><span>{description}</span></div>;
}

function CommentRow({ comment }: { comment: ProfileComment }) {
  return <Link href={`/guides/${postSlug(comment.postId)}#comments`} rel="noopener noreferrer" target="_blank"><MessageCircle size={17} /><div><p className={comment.deleted ? "is-deleted" : ""}>{comment.content}</p><small>回复于《{comment.postTitle}》 · {formatDate(comment.createdAt)}</small></div><ChevronRight size={16} /></Link>;
}

function ProfilePanel({ activeTab, overview }: { activeTab: ProfileTab; overview: ProfileOverview }) {
  const title = profileTabs.find((item) => item.key === activeTab)?.label ?? "个人中心";
  if (activeTab === "comments") return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>评论</h2><p>你在社区留下的讨论足迹。</p></div><span>{overview.comments.meta?.totalItems ?? overview.comments.items.length} 条记录</span></header>{overview.comments.items.length > 0 ? <div className="profile-comment-list">{overview.comments.items.map((comment) => <CommentRow comment={comment} key={comment.id} />)}</div> : <ProfileEmpty icon={MessageCircle} title="还没有评论记录" description="参与一次讨论，你的想法会留在这里。" />}</section>;
  if (activeTab === "favorites") return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>收藏</h2><p>收藏的攻略与帖子会集中保存在这里。</p></div></header><ProfileEmpty icon={Bookmark} title="还没有收藏内容" description="看到值得反复查看的配队和声骸思路，可以收藏起来。" /></section>;
  if (activeTab === "following") return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>关注</h2><p>你关注的创作者和他们最近的内容。</p></div></header><ProfileEmpty icon={UserRound} title="还没有关注创作者" description="关注你喜欢的攻略作者，方便回来查看更新。" /></section>;
  if (activeTab === "fans") return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>粉丝</h2><p>关注你内容的漂泊者会出现在这里。</p></div></header><ProfileEmpty icon={UsersRound} title="还没有新的粉丝记录" description="持续分享你的配队和探索心得吧。" /></section>;
  return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>{title}</h2><p>你发布过的内容与最近的更新。</p></div><span>{overview.posts.meta?.totalItems ?? overview.posts.items.length} 条记录</span></header>{overview.posts.items.length > 0 ? <div className="profile-post-list">{overview.posts.items.map((post) => <ProfilePostRow key={post.id} post={post} />)}</div> : <ProfileEmpty icon={FileText} title="还没有公开帖子" description="把你的鸣潮配队、声骸和探索心得分享出来吧。" />}</section>;
}

function ProfileState({ kind, onRetry, onLogin }: { kind: "loading" | "error" | "login"; onRetry?: () => void; onLogin?: () => void }) {
  if (kind === "loading") return <div className="profile-state"><LoaderCircle className="profile-state-spinner" size={27} /><span>正在读取个人中心…</span></div>;
  if (kind === "login") return <div className="profile-state"><UserRound size={30} /><strong>登录后查看你的社区资料</strong><span>登录后可以查看帖子、评论和个人互动记录。</span><button className="profile-state-button" onClick={onLogin} type="button">立即登录</button></div>;
  return <div className="profile-state"><span>暂时无法读取个人中心，请确认后端服务已启动。</span><button className="profile-state-button" onClick={onRetry} type="button">重新加载</button></div>;
}

export function ProfilePage() {
  const params = useSearchParams();
  const { loggedIn, requestLogin } = useCommunityDemo();
  const [overview, setOverview] = useState<ProfileOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const requestedTab = params.get("tab") as ProfileTab | null;
  const activeTab = profileTabs.some((item) => item.key === requestedTab) ? requestedTab as ProfileTab : "posts";

  useEffect(() => {
    if (!loggedIn) return;
    let active = true;
    void fetchMyProfile().then((result) => { if (active) setOverview(result); }).catch(() => { if (active) setError(true); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [loggedIn]);

  function reload() {
    setLoading(true); setError(false);
    void fetchMyProfile().then(setOverview).catch(() => setError(true)).finally(() => setLoading(false));
  }

  return <CommunityPageFrame hideRail hideSidebar><div className="profile-page">{loggedIn && loading ? <ProfileState kind="loading" /> : !loggedIn ? <ProfileState kind="login" onLogin={() => requestLogin()} /> : error || !overview ? <ProfileState kind="error" onRetry={reload} /> : <><ProfileHero overview={overview} /><div className="profile-content-grid"><ProfileNavigation activeTab={activeTab} /><ProfilePanel activeTab={activeTab} overview={overview} /></div></>}</div></CommunityPageFrame>;
}

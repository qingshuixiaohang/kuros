"use client";

import Image from "next/image";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Award, Bookmark, ChevronRight, FileText, MapPin, MessageCircle, UserRound, UsersRound } from "lucide-react";
import { CommunityPageFrame } from "@/components/community/community-pages";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { guides } from "@/lib/mock";

const coverByGuide: Record<string, string> = {
  "changli-team": "/art/guide-sword.png",
  "tower-24": "/art/guide-tower.png",
  "camellya-echo": "/art/guide-flower.png",
  "new-player-route": "/art/guide-coast.png",
};

const profileTabs = [
  { key: "posts", label: "帖子", icon: FileText },
  { key: "comments", label: "评论", icon: MessageCircle },
  { key: "favorites", label: "收藏", icon: Bookmark },
  { key: "following", label: "关注", icon: UserRound },
  { key: "fans", label: "粉丝", icon: UsersRound },
] as const;

type ProfileTab = (typeof profileTabs)[number]["key"];

function ProfileNavigation({ activeTab }: { activeTab: ProfileTab }) {
  return (
    <aside className="profile-navigation">
      <div className="profile-navigation-heading">
        <UserRound size={19} />
        <h2>个人中心</h2>
      </div>
      <nav aria-label="个人中心导航">
        {profileTabs.map(({ key, label, icon: Icon }) => (
          <Link className={activeTab === key ? "is-active" : ""} href={"/profile?tab=" + key} key={key}>
            <Icon size={18} strokeWidth={1.8} />
            <span>{label}</span>
            {activeTab === key && <ChevronRight size={15} />}
          </Link>
        ))}
      </nav>
    </aside>
  );
}

function ProfileHero() {
  const { followed, toggleFollow } = useCommunityDemo();
  const profileId = "潮汐拾荒者";
  const isFollowed = followed.includes(profileId);
  return (
    <section className="profile-hero">
      <div className="profile-identity">
        <div className="profile-avatar">
          <Image alt="潮汐拾荒者头像" fill sizes="112px" src="/art/character-lavender.png" />
        </div>
        <div className="profile-copy">
          <div className="profile-name-line">
            <h1>潮汐拾荒者</h1>
            <span className="profile-author-mark"><Award size={13} />攻略作者</span>
          </div>
          <p className="profile-location"><MapPin size={14} />西州 · 漂泊者</p>
          <div className="profile-stat-line">
            <span><b>12</b>关注</span>
            <span><b>1.4w</b>粉丝</span>
            <span><b>53.2w</b>获赞</span>
          </div>
          <p className="profile-bio">在潮声里记录配队、声骸和每一次值得回看的探索。</p>
          <button className={"profile-follow-button " + (isFollowed ? "is-followed" : "")} onClick={() => toggleFollow(profileId)} type="button">
            {isFollowed ? "已关注" : "关注"}
          </button>
        </div>
      </div>
      <div className="profile-game-card">
        <div className="profile-game-card-top">
          <div className="profile-mini-avatar">漂</div>
          <div><strong>潮汐拾荒者</strong><small>鸣潮 80级</small></div>
          <span className="profile-game-wave">鸣潮</span>
        </div>
        <div className="profile-game-stats">
          <span><b>238</b>游戏天数</span>
          <span><b>617</b>成就数量</span>
          <span><b>41</b>角色数量</span>
          <span><b>76%</b>声骸收集进度</span>
        </div>
      </div>
    </section>
  );
}

function ProfilePostRow({ guide }: { guide: (typeof guides)[number] }) {
  return (
    <Link className="profile-post-row" href={"/guides/" + guide.id}>
      <div className={"author-avatar author-avatar--" + guide.avatarTone}>{guide.authorMark}</div>
      <div className="profile-post-row-copy">
        <div className="profile-post-row-meta"><strong>{guide.author}</strong><time>{guide.publishedAt} · 鸣潮</time></div>
        <h3>{guide.title}</h3>
        <p>{guide.excerpt}</p>
        <div className="profile-post-row-stats"><span>{guide.category}</span><span>{guide.views} 阅读</span><span>{guide.replies} 评论</span></div>
      </div>
      <div className="profile-post-thumbnail">
        <Image alt="" fill sizes="140px" src={coverByGuide[guide.id]} />
      </div>
      <ChevronRight className="profile-row-arrow" size={17} />
    </Link>
  );
}

function ProfilePanel({ activeTab }: { activeTab: ProfileTab }) {
  const title = profileTabs.find((item) => item.key === activeTab)?.label ?? "个人中心";
  if (activeTab === "fans") {
    return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>粉丝</h2><p>关注你内容的漂泊者会出现在这里。</p></div></header><div className="profile-empty-panel"><UsersRound size={30} /><strong>还没有新的粉丝记录</strong><span>持续分享你的配队和探索心得吧。</span></div></section>;
  }
  if (activeTab === "following") {
    return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>关注</h2><p>你关注的创作者和他们最近的内容。</p></div></header><div className="profile-following-list">{guides.slice(0, 3).map((guide) => <Link href={"/guides/" + guide.id} key={guide.author}><div className={"author-avatar author-avatar--" + guide.avatarTone}>{guide.authorMark}</div><div><strong>{guide.author}</strong><small>{guide.category} · 最近有更新</small></div><ChevronRight size={16} /></Link>)}</div></section>;
  }
  if (activeTab === "comments") {
    return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>评论</h2><p>你在社区留下的讨论足迹。</p></div></header><div className="profile-comment-list">{guides.slice(1, 4).map((guide) => <Link href={"/guides/" + guide.id + "#comments"} key={guide.id}><MessageCircle size={17} /><div><p>这篇内容里的轮切思路很清楚，按这个顺序练习后舒服很多。</p><small>回复于《{guide.title}》 · 昨天</small></div><ChevronRight size={16} /></Link>)}</div></section>;
  }
  const visibleGuides = activeTab === "favorites" ? guides.slice(0, 3) : guides;
  return <section className="profile-panel"><header className="profile-panel-heading"><div><h2>{title}</h2><p>{activeTab === "favorites" ? "收藏的攻略与帖子会集中保存在这里。" : "你发布过的内容与最近的更新。"}</p></div><span>{visibleGuides.length} 条记录</span></header><div className="profile-post-list">{visibleGuides.map((guide) => <ProfilePostRow guide={guide} key={guide.id} />)}</div></section>;
}

export function ProfilePage() {
  const params = useSearchParams();
  const requestedTab = params.get("tab") as ProfileTab | null;
  const activeTab = profileTabs.some((item) => item.key === requestedTab) ? requestedTab as ProfileTab : "favorites";
  return <CommunityPageFrame hideRail hideSidebar><div className="profile-page"><ProfileHero /><div className="profile-content-grid"><ProfileNavigation activeTab={activeTab} /><ProfilePanel activeTab={activeTab} /></div></div></CommunityPageFrame>;
}

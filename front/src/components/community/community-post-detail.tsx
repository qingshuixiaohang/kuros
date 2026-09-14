"use client";

import Image from "next/image";
import Link from "next/link";
import { ArrowLeft, Bookmark, Clock3, Eye, Flag, Heart, MessageCircle, Reply, Share2, ThumbsUp } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { CommunityPageFrame } from "@/components/community/community-pages";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { guides } from "@/lib/mock";
import type { Guide } from "@/types/community";

const coverByGuide: Record<string, string> = {
  "changli-team": "/art/guide-sword.png",
  "tower-24": "/art/guide-tower.png",
  "camellya-echo": "/art/guide-flower.png",
  "new-player-route": "/art/guide-coast.png",
};

type CommentItem = {
  id: number;
  author: string;
  mark: string;
  tone: Guide["avatarTone"];
  date: string;
  floor: string;
  content: string;
  likes: number;
  authorComment?: boolean;
};

const seedComments: CommentItem[] = [
  { id: 1, author: "漂泊者玄夜", mark: "玄", tone: "dark", date: "09-13 14:20", floor: "1楼", content: "轮切顺序写得很清楚，尤其是先把声骸触发安排进循环这一点，实战里确实舒服很多。", likes: 61 },
  { id: 2, author: "潮声档案员", mark: "潮", tone: "blue", date: "09-13 15:06", floor: "楼主", content: "谢谢反馈！低配队伍可以先保证循环完整，再慢慢补面板，不用一开始就追求毕业词条。", likes: 55, authorComment: true },
  { id: 3, author: "无音区观测者", mark: "观", tone: "lavender", date: "09-14 09:12", floor: "3楼", content: "已收藏，等下一次深塔刷新后按这个思路试一遍。", likes: 18 },
];

function PostReactionRail({ guide }: { guide: Guide }) {
  const { liked, bookmarked, toggleLike, toggleBookmark } = useCommunityDemo();
  const isLiked = liked.includes(guide.id);
  const isBookmarked = bookmarked.includes(guide.id);
  return <aside className="post-reaction-rail" aria-label="帖子互动">
    <button type="button" onClick={() => document.getElementById("comments")?.scrollIntoView({ behavior: "smooth" })} aria-label={"查看 " + guide.replies + " 条评论"}><MessageCircle size={26} /><span>{guide.replies}</span></button>
    <button className={isLiked ? "is-active" : ""} type="button" onClick={() => toggleLike(guide.id)} aria-label={isLiked ? "取消点赞" : "点赞"}><Heart fill={isLiked ? "currentColor" : "none"} size={27} /><span>{isLiked ? "已赞" : guide.likes}</span></button>
    <button className={isBookmarked ? "is-active is-bookmarked" : ""} type="button" onClick={() => toggleBookmark(guide.id)} aria-label={isBookmarked ? "取消收藏" : "收藏"}><Bookmark fill={isBookmarked ? "currentColor" : "none"} size={27} /><span>{isBookmarked ? "已藏" : "收藏"}</span></button>
  </aside>;
}

function PostAuthorCard({ guide }: { guide: Guide }) {
  const { followed, toggleFollow } = useCommunityDemo();
  const isFollowed = followed.includes(guide.author);
  return <aside className="post-context-rail">
    <section className="post-author-card">
      <div className="post-author-card-top"><div className={"author-avatar author-avatar--" + guide.avatarTone}>{guide.authorMark}</div><div><strong>{guide.author}</strong><span><Eye size={13} /> {guide.views} 阅读</span></div></div>
      <p>一起来记录鸣潮里的配队、探索和实战心得。</p>
      <button className={isFollowed ? "is-followed" : ""} onClick={() => toggleFollow(guide.author)} type="button">{isFollowed ? "已关注" : "＋关注"}</button>
    </section>
    <section className="post-topic-card">
      <h2>分区</h2>
      <Link href={"/search?q=" + encodeURIComponent(guide.category)}>{guide.category}</Link>
      <h2>话题</h2>
      <div>{guide.tags.map((tag) => <Link href={"/search?q=" + encodeURIComponent(tag)} key={tag}>{tag}</Link>)}</div>
    </section>
  </aside>;
}

function CommentComposer({ onComment }: { onComment: (content: string) => void }) {
  const { loggedIn, requestLogin } = useCommunityDemo();
  const [draft, setDraft] = useState("");
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = draft.trim();
    if (!content) return;
    requestLogin(() => {
      onComment(content);
      setDraft("");
    });
  }
  return <form className="comment-composer" onSubmit={submit}>
    <textarea aria-label="评论内容" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder={loggedIn ? "留下你的看法，和漂泊者聊聊这篇攻略..." : "登录后参与讨论，分享你的实战心得..."} maxLength={1000} />
    <div className="comment-composer-tools"><span><Reply size={16} />支持回复与表情</span><span>{draft.length} / 1000</span><button type="submit">评论</button></div>
  </form>;
}

function PostComments() {
  const [comments, setComments] = useState(seedComments);
  const [onlyAuthor, setOnlyAuthor] = useState(false);
  const [sortNewest, setSortNewest] = useState(false);
  const visibleComments = useMemo(() => {
    const list = onlyAuthor ? comments.filter((comment) => comment.authorComment) : [...comments];
    return sortNewest ? list.reverse() : list;
  }, [comments, onlyAuthor, sortNewest]);
  return <section className="post-comments" id="comments">
    <div className="comments-heading"><div><button className={!onlyAuthor ? "is-active" : ""} onClick={() => setOnlyAuthor(false)} type="button">全部评论<span>{comments.length}</span></button><button className={onlyAuthor ? "is-active" : ""} onClick={() => setOnlyAuthor(true)} type="button">只看楼主</button></div><div><button className={!sortNewest ? "is-active" : ""} onClick={() => setSortNewest(false)} type="button">默认</button><i /> <button className={sortNewest ? "is-active" : ""} onClick={() => setSortNewest(true)} type="button">最新</button></div></div>
    <CommentComposer onComment={(content) => setComments((current) => [...current, { id: current.length + 1, author: "潮汐拾荒者", mark: "漂", tone: "gold", date: "刚刚", floor: current.length + 1 + "楼", content, likes: 0 }])} />
    <div className="comment-list">{visibleComments.map((comment) => <article className="comment-item" key={comment.id}><div className={"author-avatar author-avatar--" + comment.tone}>{comment.mark}</div><div className="comment-item-main"><div className="comment-item-meta"><strong>{comment.author}{comment.authorComment && <em>楼主</em>}</strong><span>{comment.floor} · {comment.date}</span></div><p>{comment.content}</p><div className="comment-item-actions"><button type="button"><ThumbsUp size={14} />{comment.likes}</button><button type="button">回复</button><button type="button">举报</button></div></div></article>)}</div>
  </section>;
}

function GuideArticle() {
  return <div className="post-detail-body">
    <p>这篇攻略记录了当前版本下的实战测试结果，适合已经完成主线并准备进一步提升队伍强度的漂泊者。</p>
    <h2>一、先确定队伍节奏</h2>
    <p>先用主输出完成关键技能循环，再让协同角色补充增益和伤害。不要为了追求面板而牺牲实际的轮切顺序，稳定完成一轮循环通常比单次数字更重要。</p>
    <div className="article-note"><Clock3 size={17} /><span>核心思路：把共鸣效率、技能冷却和声骸触发安排在同一条循环线上。</span></div>
    <h2>二、角色与声骸选择</h2>
    <p>优先选择能覆盖队伍空窗期的协同角色。声骸方面，先满足套装效果，再根据主词条和副词条逐步替换。</p>
    <div className="article-table"><div><span>位置</span><strong>推荐方向</strong></div><div><span>主输出</span><strong>优先保证共鸣技能循环</strong></div><div><span>协同位</span><strong>补充增益并缩短空窗期</strong></div><div><span>声骸</span><strong>套装效果优先于单件面板</strong></div></div>
    <h2>三、实战检查清单</h2>
    <ul><li>进入战斗前确认声骸和武器资源是否已经分配。</li><li>先练习一轮完整循环，再根据实战表现调整顺序。</li><li>深塔环境变化时，优先替换功能位，而不是盲目更换主输出。</li></ul>
  </div>;
}

export function GuidePostDetailPage({ slug }: { slug: string }) {
  const guide = guides.find((item) => item.id === slug) ?? guides[0];
  const { notify } = useCommunityDemo();
  return <CommunityPageFrame activeNav="guides" hideRail hideSidebar><div className="post-detail-layout">
    <PostReactionRail guide={guide} />
    <article className="post-detail-page">
      <Link className="back-link" href="/guides"><ArrowLeft size={15} />返回攻略列表</Link>
      <header className="post-detail-heading"><div className="post-detail-kicker"><span className="guide-type">{guide.category}</span><span>原创</span><time>2026-09-14 10:24 · 重庆</time></div><h1>{guide.title}</h1><p>{guide.excerpt}</p><div className="detail-author"><div className={"author-avatar author-avatar--" + guide.avatarTone}>{guide.authorMark}</div><div><strong>{guide.author}</strong><small>攻略作者 · {guide.views} 阅读</small></div><button className="follow-button" type="button">＋关注</button></div></header>
      <div className="post-cover"><Image alt={guide.title + "配图"} fill priority sizes="(max-width: 900px) 100vw, 820px" src={coverByGuide[guide.id]} /></div>
      <GuideArticle />
      <div className="post-detail-footer"><span>阅读 {guide.views}</span><button type="button" onClick={() => notify("已收到反馈，感谢帮助维护社区。")}><Flag size={14} />举报</button><button type="button" onClick={() => notify("链接已复制，可以分享给你的队友。")}><Share2 size={14} />分享</button></div>
      <PostComments />
    </article>
    <PostAuthorCard guide={guide} />
  </div></CommunityPageFrame>;
}

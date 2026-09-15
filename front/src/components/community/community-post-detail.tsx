"use client";

import Image from "next/image";
import Link from "next/link";
import { ArrowLeft, Bookmark, Clock3, Eye, Flag, Heart, MessageCircle, Reply, Share2, ThumbsUp } from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { CommunityPageFrame } from "@/components/community/community-pages";
import { CommunityFollowButton } from "@/components/community/community-follow-button";
import { CommunityReportDialog } from "@/components/community/community-report-dialog";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { createComment, deleteComment, favoritePost, fetchComments, fetchPost, fetchPostInteractions, likePost, unfavoritePost, unlikePost, type ApiComment, type PostInteraction, type ReportTargetType } from "@/lib/api";
import { guides } from "@/lib/mock";
import type { Guide } from "@/types/community";

const coverByGuide: Record<string, string> = {
  "changli-team": "/art/guide-sword.png",
  "tower-24": "/art/guide-tower.png",
  "camellya-echo": "/art/guide-flower.png",
  "new-player-route": "/art/guide-coast.png",
};

const apiPostIdBySlug: Record<string, string> = {
  "changli-team": "10000000-0000-0000-0000-000000000001",
  "tower-24": "10000000-0000-0000-0000-000000000002",
  "camellya-echo": "10000000-0000-0000-0000-000000000003",
  "new-player-route": "10000000-0000-0000-0000-000000000004",
};

type CommentItem = {
  id: string;
  parentId: string | null;
  authorId?: string;
  author: string;
  mark: string;
  tone: Guide["avatarTone"];
  date: string;
  floor: string;
  content: string;
  likes: number;
  deleted?: boolean;
  authorComment?: boolean;
};

const seedComments: CommentItem[] = [
  { id: "30000000-0000-0000-0000-000000000001", parentId: null, author: "无音区夜行者", mark: "无", tone: "dark", date: "09-13 14:20", floor: "1楼", content: "轮切顺序写得很清楚，尤其是先把声骸触发安排进循环这一点，实战里确实舒服很多。", likes: 61 },
  { id: "30000000-0000-0000-0000-000000000002", parentId: "30000000-0000-0000-0000-000000000001", author: "潮声档案员", mark: "潮", tone: "blue", date: "09-13 15:06", floor: "楼主", content: "谢谢反馈！低配队伍可以先保证循环完整，再慢慢补面板，不用一开始就追求毕业词条。", likes: 55, authorComment: true },
  { id: "30000000-0000-0000-0000-000000000003", parentId: null, author: "今汐的留声机", mark: "今", tone: "lavender", date: "09-14 09:12", floor: "3楼", content: "已收藏，等下一次深塔刷新后按这个思路试一遍。", likes: 18 },
];

function commentFromApi(comment: ApiComment, authorName: string): CommentItem {
  return {
    id: comment.id,
    parentId: comment.parentId,
    authorId: comment.author.id,
    author: comment.author.nickname,
    mark: comment.author.nickname.slice(0, 1),
    tone: comment.author.id === "10000000-0000-0000-0000-000000000001" ? "blue" : "dark",
    date: comment.createdAt.slice(5, 16).replace("T", " "),
    floor: comment.parentId ? "回复" : "评论",
    content: comment.content,
    likes: comment.likeCount,
    deleted: comment.deleted,
    authorComment: comment.author.nickname === authorName,
  };
}

function formatCount(value: number) {
  if (value >= 10000) return (value / 10000).toFixed(value >= 100000 ? 0 : 1).replace(/\.0$/, "") + "w";
  return String(value);
}

function parseCount(value: string) {
  const number = Number.parseFloat(value.replace(/[万w]/gi, ""));
  if (!Number.isFinite(number)) return 0;
  return /[万w]/i.test(value) ? Math.round(number * 10000) : /k/i.test(value) ? Math.round(number * 1000) : Math.round(number);
}

function PostReactionRail({ guide, postId }: { guide: Guide; postId: string }) {
  const { requestLogin, notify } = useCommunityDemo();
  const [interaction, setInteraction] = useState<PostInteraction>({ postId, likeCount: parseCount(guide.likes), favoriteCount: 0, liked: false, favorited: false });
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    let active = true;
    fetchPostInteractions(postId).then((result) => { if (active) setInteraction(result); }).catch(() => { /* Keep the local guide counts when the API is unavailable. */ });
    return () => { active = false; };
  }, [postId]);
  async function change(kind: "like" | "favorite") {
    if (loading) return;
    setLoading(true);
    try {
      const next = kind === "like"
        ? (interaction.liked ? await unlikePost(postId) : await likePost(postId))
        : (interaction.favorited ? await unfavoritePost(postId) : await favoritePost(postId));
      setInteraction(next);
    } catch (error) {
      notify(error instanceof Error ? error.message : "操作失败，请稍后重试");
    } finally { setLoading(false); }
  }
  function protect(action: () => void) { requestLogin(action); }
  return <aside className="post-reaction-rail" aria-label="帖子互动">
    <button type="button" onClick={() => document.getElementById("comments")?.scrollIntoView({ behavior: "smooth" })} aria-label={"查看 " + guide.replies + " 条评论"}><MessageCircle size={26} /><span>{guide.replies}</span></button>
    <button className={interaction.liked ? "is-active" : ""} disabled={loading} type="button" onClick={() => protect(() => { void change("like"); })} aria-label={interaction.liked ? "取消点赞" : "点赞"}><Heart fill={interaction.liked ? "currentColor" : "none"} size={27} /><span>{interaction.likeCount}</span></button>
    <button className={interaction.favorited ? "is-active is-bookmarked" : ""} disabled={loading} type="button" onClick={() => protect(() => { void change("favorite"); })} aria-label={interaction.favorited ? "取消收藏" : "收藏"}><Bookmark fill={interaction.favorited ? "currentColor" : "none"} size={27} /><span>{interaction.favorited ? "已藏" : "收藏"}</span></button>
  </aside>;
}

function PostAuthorCard({ guide, authorId }: { guide: Guide; authorId?: string }) {
  return <aside className="post-context-rail">
    <section className="post-author-card">
      <div className="post-author-card-top"><div className={"author-avatar author-avatar--" + guide.avatarTone}>{guide.authorMark}</div><div><strong>{guide.author}</strong><span><Eye size={13} /> {guide.views} 阅读</span></div></div>
      <p>一起来记录鸣潮里的配队、探索和实战心得。</p>
      <CommunityFollowButton fallbackKey={guide.author} targetUserId={authorId} />
    </section>
    <section className="post-topic-card">
      <h2>分区</h2>
      <Link href={"/search?q=" + encodeURIComponent(guide.category)}>{guide.category}</Link>
      <h2>话题</h2>
      <div>{guide.tags.map((tag) => <Link href={"/search?q=" + encodeURIComponent(tag)} key={tag}>{tag}</Link>)}</div>
    </section>
  </aside>;
}

function CommentComposer({ onComment, replyTo, replyLabel, onCancel }: { onComment: (content: string, parentId: string | null) => Promise<void>; replyTo: string | null; replyLabel?: string; onCancel: () => void }) {
  const { loggedIn, requestLogin } = useCommunityDemo();
  const [draft, setDraft] = useState("");
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = draft.trim();
    if (!content) return;
    requestLogin(() => {
      void onComment(content, replyTo).then(() => setDraft("")).catch(() => { /* The composer keeps the draft after a failed request. */ });
    });
  }
  return <form className="comment-composer" onSubmit={submit}>
    {replyTo && <div className="comment-replying"><span>正在回复 {replyLabel ?? "这条评论"}</span><button type="button" onClick={onCancel}>取消回复</button></div>}
    <textarea aria-label="评论内容" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder={loggedIn ? "留下你的看法，和漂泊者聊聊这篇攻略..." : "登录后参与讨论，分享你的实战心得..."} maxLength={1000} />
    <div className="comment-composer-tools"><span><Reply size={16} />支持回复与表情</span><span>{draft.length} / 1000</span><button type="submit">评论</button></div>
  </form>;
}

function PostComments({ postId, authorName, onReport }: { postId: string; authorName: string; onReport: (commentId: string) => void }) {
  const [comments, setComments] = useState(seedComments);
  const [totalItems, setTotalItems] = useState(seedComments.length);
  const [onlyAuthor, setOnlyAuthor] = useState(false);
  const [sortNewest, setSortNewest] = useState(false);
  const [loading, setLoading] = useState(true);
  const [apiError, setApiError] = useState("");
  const [replyTo, setReplyTo] = useState<string | null>(null);
  const { loggedIn, user, requestLogin, notify } = useCommunityDemo();

  useEffect(() => {
    let active = true;
    fetchComments(postId, { page: 1, pageSize: 20, sort: sortNewest ? "latest" : "hot" }).then((result) => {
      if (!active) return;
      setComments(result.items.map((comment) => commentFromApi(comment, authorName)));
      setTotalItems(result.meta?.totalItems ?? result.items.length);
      setApiError("");
    }).catch(() => {
      if (active) setApiError("后端暂不可用，当前显示本地评论示例。");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [authorName, postId, sortNewest]);

  const visibleComments = useMemo(() => onlyAuthor ? comments.filter((comment) => comment.authorComment) : comments, [comments, onlyAuthor]);

  async function submitComment(content: string, parentId: string | null) {
    try {
      const comment = await createComment(postId, content, parentId);
      setComments((current) => [commentFromApi(comment, authorName), ...current]);
      setTotalItems((current) => current + 1);
      setReplyTo(null);
      setApiError("");
      notify(parentId ? "回复已发布" : "评论已发布");
    } catch (error) {
      setApiError(error instanceof Error ? error.message : "评论发布失败，请稍后重试");
      notify("评论发布失败，请稍后重试");
    }
  }

  async function removeComment(commentId: string) {
    try {
      await deleteComment(postId, commentId);
      setComments((current) => current.map((comment) => comment.id === commentId ? { ...comment, content: "该评论已删除", deleted: true } : comment));
      notify("评论已删除");
    } catch (error) {
      notify(error instanceof Error ? error.message : "删除失败，请稍后重试");
    }
  }

  return <section className="post-comments" id="comments">
    <div className="comments-heading"><div><button className={!onlyAuthor ? "is-active" : ""} onClick={() => setOnlyAuthor(false)} type="button">全部评论<span>{totalItems}</span></button><button className={onlyAuthor ? "is-active" : ""} onClick={() => setOnlyAuthor(true)} type="button">只看楼主</button></div><div><button className={!sortNewest ? "is-active" : ""} onClick={() => setSortNewest(false)} type="button">默认</button><i /> <button className={sortNewest ? "is-active" : ""} onClick={() => setSortNewest(true)} type="button">最新</button></div></div>
    <CommentComposer onComment={submitComment} onCancel={() => setReplyTo(null)} replyLabel={comments.find((comment) => comment.id === replyTo)?.author} replyTo={replyTo} />
    {apiError && <p className="comment-inline-status" role="status">{apiError}</p>}
    {loading && <p className="comment-inline-status">正在整理漂泊者的留言…</p>}
    <div className="comment-list">{visibleComments.map((comment) => <article className={"comment-item" + (comment.parentId ? " comment-item--reply" : "")} key={comment.id}><div className={"author-avatar author-avatar--" + comment.tone}>{comment.mark}</div><div className="comment-item-main"><div className="comment-item-meta"><strong>{comment.author}{comment.authorComment && <em>楼主</em>}</strong><span>{comment.floor} · {comment.date}</span></div><p className={comment.deleted ? "comment-deleted" : ""}>{comment.content}</p><div className="comment-item-actions"><button type="button"><ThumbsUp size={14} />{comment.likes}</button>{!comment.deleted && !comment.parentId && <button type="button" onClick={() => { if (loggedIn) setReplyTo(comment.id); else requestLogin(() => setReplyTo(comment.id)); }}>回复</button>}<button type="button" onClick={() => onReport(comment.id)}>举报</button>{user?.id === comment.authorId && !comment.deleted && <button type="button" onClick={() => void removeComment(comment.id)}>删除</button>}</div></div></article>)}</div>
  </section>;
}

function renderMarkdown(content: string): ReactNode[] {
  return content.split(/\n\s*\n/).map((block, index) => {
    const lines = block.split("\n");
    if (lines.every((line) => line.startsWith("- "))) return <ul key={index}>{lines.map((line) => <li key={line}>{line.slice(2)}</li>)}</ul>;
    if (block.startsWith("## ")) return <h2 key={index}>{block.slice(3)}</h2>;
    return <p key={index}>{block.replace(/^# /, "")}</p>;
  });
}

function GuideArticle({ content }: { content?: string }) {
  if (content) return <div className="post-detail-body">{renderMarkdown(content)}</div>;
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
  const fallbackGuide = guides.find((item) => item.id === slug) ?? guides[0];
  const apiPostId = apiPostIdBySlug[slug] ?? slug;
  const [guide, setGuide] = useState(fallbackGuide);
  const [authorId, setAuthorId] = useState<string>();
  const [apiUnavailable, setApiUnavailable] = useState(false);
  const [reportTarget, setReportTarget] = useState<{ type: ReportTargetType; id: string } | null>(null);
  const { notify, requestLogin } = useCommunityDemo();
  useEffect(() => {
    let cancelled = false;
    fetchPost(apiPostId).then((post) => {
      if (!cancelled) {
        setGuide({ ...fallbackGuide, id: post.id, category: post.category, title: post.title, excerpt: post.excerpt, content: post.content, author: post.author.nickname, authorMark: post.author.nickname.slice(0, 1), publishedAt: post.publishedAt, views: formatCount(post.viewCount), replies: post.commentCount, likes: formatCount(post.likeCount), tags: post.tags });
        setAuthorId(post.author.id);
        setApiUnavailable(false);
      }
    }).catch(() => { if (!cancelled) setApiUnavailable(true); });
    return () => { cancelled = true; };
  }, [apiPostId, fallbackGuide, slug]);
  return <CommunityPageFrame activeNav="guides" hideRail hideSidebar><div className="post-detail-layout">
    <PostReactionRail guide={guide} postId={apiPostId} />
    <article className="post-detail-page">
      <Link className="back-link" href="/guides"><ArrowLeft size={15} />返回攻略列表</Link>
      <header className="post-detail-heading"><div className="post-detail-kicker"><span className="guide-type">{guide.category}</span><span>原创</span><time>{guide.publishedAt}</time></div><h1>{guide.title}</h1><p>{guide.excerpt}</p><div className="detail-author"><div className={"author-avatar author-avatar--" + guide.avatarTone}>{guide.authorMark}</div><div><strong>{guide.author}</strong><small>攻略作者 · {guide.views} 阅读</small></div><CommunityFollowButton className="follow-button" fallbackKey={guide.author} targetUserId={authorId} /></div></header>
      <div className="post-cover"><Image alt={guide.title + "配图"} fill priority sizes="(max-width: 900px) 100vw, 820px" src={coverByGuide[guide.id] ?? coverByGuide[slug] ?? "/art/guide-sword.png"} /></div>
      <GuideArticle content={guide.content} />
      <div className="post-detail-footer"><span>阅读 {guide.views}</span><button type="button" onClick={() => requestLogin(() => setReportTarget({ type: "POST", id: apiPostId }))}><Flag size={14} />举报</button><button type="button" onClick={() => notify("链接已复制，可以分享给你的队友。")}><Share2 size={14} />分享</button></div>
      <PostComments authorName={guide.author} onReport={(commentId) => requestLogin(() => setReportTarget({ type: "COMMENT", id: commentId }))} postId={apiPostId} />
      {apiUnavailable && <p className="api-fallback-note">后端暂不可用，当前显示本地 Demo 数据。</p>}
    </article>
    <PostAuthorCard authorId={authorId} guide={guide} />
    {reportTarget && <CommunityReportDialog onClose={() => setReportTarget(null)} onSuccess={() => setReportTarget(null)} targetId={reportTarget.id} targetType={reportTarget.type} />}
  </div></CommunityPageFrame>;
}

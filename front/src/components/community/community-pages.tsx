"use client";
import Image from "next/image";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, Bell, Bold, ChevronDown, ChevronRight, Eye, Heart, Heading1, Heading2, ImagePlus, Italic, Link2, List, ListOrdered, MessageSquare, MoveRight, PenLine, Quote, Redo2, Search, Send, Share2, Sparkles, Undo2, Wrench, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type DragEvent as ReactDragEvent, type ReactNode } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useWatch } from "react-hook-form";
import { z } from "zod";
import { CommunityDemoProvider, useCommunityDemo } from "@/components/community/community-interactions";
import { RightRail, Sidebar, TopNavigation } from "@/components/community/community-home";
import { characters, echoSets, guides, newsItems, toolItems } from "@/lib/mock";
import { createPost, fetchPost, updatePost, uploadImage } from "@/lib/api";
import { useCommunityPostQuery } from "@/lib/community-queries";
import { toGuide } from "@/lib/post-view";

type FrameProps = { children: ReactNode; activeNav?: string; query?: string; onQueryChange?: (query: string) => void; hideSidebar?: boolean; hideRail?: boolean; };
export function CommunityPageFrame(props: FrameProps) { return <CommunityDemoProvider><FrameContent {...props} /></CommunityDemoProvider>; }
function FrameContent({ children, activeNav = "community", query, onQueryChange, hideSidebar = false, hideRail = false }: FrameProps) { const [drawerOpen, setDrawerOpen] = useState(false); const [localQuery, setLocalQuery] = useState(""); const value = query ?? localQuery; return <main className="app-shell"><TopNavigation query={value} onQueryChange={onQueryChange ?? setLocalQuery} onMenu={() => setDrawerOpen(true)} activeNav={activeNav} />{drawerOpen && <div className="drawer-backdrop" onClick={() => setDrawerOpen(false)}><div className="mobile-drawer" onClick={(event) => event.stopPropagation()}><div className="drawer-header"><span>频道</span><button onClick={() => setDrawerOpen(false)} type="button" aria-label="关闭导航"><X size={20} /></button></div><Sidebar drawer /></div></div>}<div className={"page-grid " + (hideSidebar && hideRail ? "page-grid--focused" : "")}>{!hideSidebar && <Sidebar />}<div className="main-column page-content">{children}</div>{!hideRail && <RightRail />}</div></main>; }
export function PageHeader({ section, title, description, action }: { section: string; title: string; description: string; action?: ReactNode }) { return <header className="subpage-header"><div><p className="page-breadcrumb">鸣潮社区 <ChevronRight size={13} /> {section}</p><h1>{title}</h1><p>{description}</p></div>{action}</header>; }
function GuideListItem({ guide }: { guide: (typeof guides)[number] }) { return <article className="guide-list-item"><div className="guide-list-top"><span className="guide-type">{guide.category}</span><time>{guide.publishedAt}</time></div><Link href={"/guides/" + guide.id}><h2>{guide.title}</h2></Link><p>{guide.excerpt}</p><div className="guide-list-meta"><span>{guide.author}</span><span><Eye size={14} />{guide.views}</span><span><MessageSquare size={14} />{guide.replies}</span><span><Heart size={14} />{guide.likes}</span></div><Link className="read-link" href={"/guides/" + guide.id}>查看攻略 <MoveRight size={15} /></Link></article>; }
export function GuidesPage() { const params = useSearchParams(); const [query, setQuery] = useState(""); const defaultCategory = params.get("category") ?? "全部"; const [category, setCategory] = useState(defaultCategory); const categories = ["全部", "配队攻略", "深塔攻略", "角色培养", "新手攻略"]; const filtered = useMemo(() => guides.filter((guide) => (category === "全部" || guide.category === category) && (guide.title + guide.excerpt + guide.tags.join("")).toLowerCase().includes(query.trim().toLowerCase())), [category, query]); return <CommunityPageFrame activeNav="guides" query={query} onQueryChange={setQuery}><PageHeader section="攻略" title="攻略" description="从角色培养到深塔挑战，找到适合当前版本的解法。" action={<Link className="primary-button" href="/publish?type=guide"><PenLine size={16} />发布攻略</Link>} /><div className="filter-bar"><div className="filter-tabs">{categories.map((item) => <button className={category === item ? "is-active" : ""} key={item} onClick={() => setCategory(item)} type="button">{item}</button>)}</div><span className="result-count">共 {filtered.length} 篇</span></div><section className="guide-list-page">{filtered.length ? filtered.map((guide) => <GuideListItem guide={guide} key={guide.id} />) : <p className="empty-state">没有找到匹配的攻略。</p>}</section></CommunityPageFrame>; }
export function GuideDetailPage({ slug }: { slug: string }) { const guide = guides.find((item) => item.id === slug) ?? guides[0]; const { followed, toggleFollow, requestLogin, notify } = useCommunityDemo(); const authorFollowed = followed.includes(guide.author); return <CommunityPageFrame activeNav="guides"><article className="detail-page"><Link className="back-link" href="/guides"><ArrowLeft size={15} />返回攻略列表</Link><div className="detail-heading"><span className="guide-type">{guide.category}</span><h1>{guide.title}</h1><p>{guide.excerpt}</p><div className="detail-author"><div className={"author-avatar author-avatar--" + guide.avatarTone}>{guide.authorMark}</div><div><strong>{guide.author}</strong><small>发布于 {guide.publishedAt} · {guide.views} 阅读</small></div><button className={"follow-button " + (authorFollowed ? "is-followed" : "")} onClick={() => toggleFollow(guide.author)} type="button">{authorFollowed ? "已关注" : "＋关注"}</button></div></div><div className="detail-layout"><main className="article-body"><p>这篇攻略记录了当前版本下的实战测试结果，适合已经完成主线并准备进一步提升队伍强度的漂泊者。</p><h2 id="section-1">一、先确定队伍节奏</h2><p>先用主输出完成关键技能循环，再让协同角色补充增益和伤害。不要为了追求面板而牺牲实际的轮切顺序，稳定完成一轮循环通常比单次数字更重要。</p><div className="article-note"><Sparkles size={17} /><span>核心思路：把共鸣效率、技能冷却和声骸触发安排在同一条循环线上。</span></div><h2 id="section-2">二、角色与声骸选择</h2><p>优先选择能覆盖队伍空窗期的协同角色。声骸方面，先满足套装效果，再根据主词条和副词条逐步替换。</p><div className="article-table"><div><span>位置</span><strong>推荐方向</strong></div><div><span>主输出</span><strong>优先保证共鸣技能循环</strong></div><div><span>协同位</span><strong>补充增益并缩短空窗期</strong></div><div><span>声骸</span><strong>套装效果优先于单件面板</strong></div></div><h2 id="section-3">三、实战检查清单</h2><ul><li>进入战斗前确认声骸和武器资源是否已经分配。</li><li>先练习一轮完整循环，再根据实战表现调整顺序。</li><li>深塔环境变化时，优先替换功能位，而不是盲目更换主输出。</li></ul></main><aside className="article-aside"><div><p>本文目录</p><a href="#section-1">一、先确定队伍节奏</a><a href="#section-2">二、角色与声骸选择</a><a href="#section-3">三、实战检查清单</a></div><button className="share-button" onClick={() => notify("攻略链接已复制。")} type="button"><Share2 size={16} />分享攻略</button></aside></div><div className="comment-placeholder" id="comments"><MessageSquare size={18} /><div><strong>评论区</strong><p>登录后参与讨论，分享你的实战心得。</p></div><button onClick={() => requestLogin(() => notify("评论输入将在接入后端后开放。"))} type="button">登录评论</button></div></article></CommunityPageFrame>; }
export function CharactersPage() { const [query, setQuery] = useState(""); const [role, setRole] = useState("全部角色"); const roles = ["全部角色", "输出", "协同", "辅助"]; const visible = characters.filter((item) => (role === "全部角色" || item.role === role) && (item.name + item.title + item.description).includes(query.trim())); return <CommunityPageFrame query={query} onQueryChange={setQuery}><PageHeader section="图鉴 / 角色" title="角色图鉴" description="记录每位共鸣者的定位、属性与培养方向。" action={<Link className="secondary-button" href="/tools/team-builder"><Wrench size={16} />配队模拟</Link>} /><div className="catalog-tabs">{roles.map((item) => <button className={role === item ? "is-active" : ""} key={item} onClick={() => setRole(item)} type="button">{item}</button>)}</div><section className="character-grid">{visible.map((item) => <article className="character-card" key={item.id}><Link className="character-image" href={"/characters/" + item.id}><Image alt={item.name + "角色立绘"} fill sizes="(max-width: 620px) 50vw, 260px" src={item.image} /></Link><div className="character-copy"><div><h2>{item.name}</h2><span>{item.title}</span></div><p>{item.description}</p><Link href={"/characters/" + item.id}>查看培养攻略 <ChevronRight size={14} /></Link></div></article>)}</section></CommunityPageFrame>; }
export function CharacterDetailPage({ id }: { id: string }) { const character = characters.find((item) => item.id === id) ?? characters[0]; return <CommunityPageFrame><article className="detail-page entity-detail"><Link className="back-link" href="/characters"><ArrowLeft size={15} />返回角色图鉴</Link><div className="entity-hero"><div><span className="guide-type">{character.title}</span><h1>{character.name}</h1><p>{character.description}</p><Link className="primary-button" href={"/guides/" + character.guideId}>查看培养攻略</Link></div><div className="entity-image"><Image alt={character.name + "角色立绘"} fill sizes="300px" src={character.image} /></div></div><section className="entity-note"><h2>培养方向</h2><p>以稳定循环为优先，先补足角色核心技能等级，再根据队伍需求逐步完善声骸与武器。该页面为 Demo 图鉴内容，后端接入后可展示实时材料与面板数据。</p></section></article></CommunityPageFrame>; }
export function EchoesPage() { const [query, setQuery] = useState(""); const [mode, setMode] = useState("套装"); const visible = echoSets.filter((item) => (item.name + item.effect + item.location).includes(query.trim())); return <CommunityPageFrame query={query} onQueryChange={setQuery}><PageHeader section="图鉴 / 声骸" title="声骸图鉴" description="查询套装效果、适配角色与常见掉落位置。" action={<Link className="primary-button" href="/search?q=声骸"><Search size={16} />搜索声骸</Link>} /><div className="echo-toolbar"><div>{["套装", "单体声骸", "掉落位置"].map((item) => <button className={mode === item ? "is-active" : ""} key={item} onClick={() => setMode(item)} type="button">{item}</button>)}</div><span>已收录 {visible.length} 套</span></div><section className="echo-table"><div className="echo-table-head"><span>套装名称</span><span>{mode === "掉落位置" ? "推荐掉落区域" : "两件套效果"}</span><span>推荐掉落区域</span><span>操作</span></div>{visible.map((item) => <div className="echo-row" key={item.id}><div><span className="echo-symbol"><Sparkles size={16} /></span><strong>{item.name}</strong></div><p>{mode === "掉落位置" ? item.location : item.effect}</p><p>{item.location}</p><Link href={"/echoes/" + item.id}>查看搭配 <ChevronRight size={14} /></Link></div>)}</section></CommunityPageFrame>; }
export function EchoDetailPage({ id }: { id: string }) { const echo = echoSets.find((item) => item.id === id) ?? echoSets[0]; return <CommunityPageFrame><article className="detail-page"><Link className="back-link" href="/echoes"><ArrowLeft size={15} />返回声骸图鉴</Link><div className="detail-heading"><span className="guide-type">{echo.role}向套装</span><h1>{echo.name}</h1><p>{echo.description}</p></div><section className="entity-note"><h2>套装信息</h2><p><strong>两件套效果：</strong>{echo.effect}</p><p><strong>推荐掉落：</strong>{echo.location}</p><Link className="primary-button" href={"/guides/" + echo.guideId}>查看适配攻略</Link></section></article></CommunityPageFrame>; }
export function NewsPage() { const params = useSearchParams(); const [category, setCategory] = useState(params.get("category") ?? "全部"); const { requestLogin, notify } = useCommunityDemo(); const tabs = ["全部", "版本前瞻", "官方公告", "活动资讯"]; const visible = newsItems.filter((item) => category === "全部" || item.category === category); return <CommunityPageFrame activeNav="news"><PageHeader section="资讯" title="版本资讯" description="版本公告、活动前瞻和官方信息集中整理。" action={<button className="secondary-button" onClick={() => requestLogin(() => notify("已订阅资讯更新。"))} type="button"><Bell size={16} />订阅更新</button>} /><div className="news-page-tabs">{tabs.map((item) => <button className={category === item ? "is-active" : ""} key={item} onClick={() => setCategory(item)} type="button">{item}</button>)}</div><section className="news-page-list">{visible.map((item) => <Link href={"/news/" + item.id} key={item.id}><article><time>{item.date}</time><div><span>{item.category}</span><h2>{item.title}</h2><p>{item.summary}</p></div><ChevronRight size={18} /></article></Link>)}</section></CommunityPageFrame>; }
export function NewsDetailPage({ id }: { id: string }) { const item = newsItems.find((entry) => entry.id === id) ?? newsItems[0]; const { notify } = useCommunityDemo(); return <CommunityPageFrame activeNav="news"><article className="detail-page"><Link className="back-link" href="/news"><ArrowLeft size={15} />返回资讯列表</Link><div className="detail-heading"><span className="guide-type">{item.category}</span><h1>{item.title}</h1><p>{item.date} · 社区资讯整理</p></div><main className="article-body news-detail-body">{item.content.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}</main><button className="share-button detail-share" onClick={() => notify("资讯链接已复制。")} type="button"><Share2 size={16} />分享资讯</button></article></CommunityPageFrame>; }
export function CreationsPage() { return <CommunityPageFrame activeNav="creations"><PageHeader section="同人创作" title="同人创作" description="收录绘画、摄影、剪辑和世界观相关的玩家创作。" action={<Link className="primary-button" href="/publish?type=creation"><PenLine size={16} />发布创作</Link>} /><section className="creation-list">{characters.map((item, index) => <Link className="creation-card" href={"/characters/" + item.id} key={item.id}><div className="creation-image"><Image alt={item.name + "创作预览"} fill sizes="(max-width: 620px) 50vw, 420px" src={item.image} /></div><div><span>玩家创作 · {index === 1 ? "摄影" : "绘画"}</span><h2>{item.name} · 潮声片段</h2><p>以原创角色意象为灵感的社区作品展示。</p><small>查看作品 <ChevronRight size={13} /></small></div></Link>)}</section></CommunityPageFrame>; }
export function ToolsPage() { return <CommunityPageFrame activeNav="tools"><PageHeader section="实用工具" title="漂泊者工具箱" description="把常用的养成计算、声骸查询与配队思路收在同一处。" /><section className="tools-page-grid">{toolItems.map((tool) => { const href = tool.slug === "echo" ? "/echoes" : "/tools/" + tool.slug; return <Link href={href} key={tool.slug}><article className="tool-page-card"><span>{tool.icon === "calculator" ? "算" : tool.icon === "echo" ? "骸" : "队"}</span><h2>{tool.title}</h2><p>{tool.description}</p><small>打开工具 <ChevronRight size={13} /></small></article></Link>; })}</section></CommunityPageFrame>; }
export function ToolDetailPage({ slug }: { slug: string }) { const tool = toolItems.find((item) => item.slug === slug) ?? toolItems[0]; const [level, setLevel] = useState(1); const [weapon, setWeapon] = useState(1); const [members, setMembers] = useState<string[]>([]); const isTeam = tool.slug === "team-builder"; const total = (level * 1200 + weapon * 780).toLocaleString(); function toggleMember(id: string) { setMembers((current) => current.includes(id) ? current.filter((entry) => entry !== id) : current.length === 3 ? [...current.slice(1), id] : [...current, id]); } return <CommunityPageFrame activeNav="tools"><PageHeader section={"实用工具 / " + tool.title} title={tool.title} description={tool.description} /><section className="tool-workspace">{isTeam ? <><div className="team-slots">{[0, 1, 2].map((index) => <div className="team-slot" key={index}>{members[index] ? characters.find((item) => item.id === members[index])?.name : "选择角色"}</div>)}</div><div className="tool-choice-list">{characters.map((item) => <button className={members.includes(item.id) ? "is-active" : ""} key={item.id} onClick={() => toggleMember(item.id)} type="button">{item.name}<small>{item.role}</small></button>)}</div><p className="tool-result">{members.length ? "当前队伍已记录 " + members.length + " 位角色。Demo 版用于梳理轮切思路。" : "从下方选择至多三位角色，开始构建队伍。"}</p></> : <><div className="calculator-fields"><label>角色等级<input max="90" min="1" onChange={(event) => setLevel(Number(event.target.value))} type="number" value={level} /></label><label>武器等级<input max="90" min="1" onChange={(event) => setWeapon(Number(event.target.value))} type="number" value={weapon} /></label></div><div className="tool-result"><span>预计养成素材</span><strong>{total}</strong><small>按当前等级差估算；接入数据服务后可展示真实材料明细。</small></div></>}</section></CommunityPageFrame>; }
export function SearchResultsPage() { const params = useSearchParams(); const initialTerm = params.get("q")?.trim() ?? ""; const [term, setTerm] = useState(initialTerm); const [page, setPage] = useState(1); const result = useCommunityPostQuery({ keyword: term, page, pageSize: 10, sort: "latest" }); const fallback = guides.filter((guide) => (guide.title + guide.excerpt + guide.tags.join("")).toLowerCase().includes(term.toLowerCase())); const items = result.error ? fallback : result.data?.items.map(toGuide) ?? []; const totalPages = result.error ? 1 : result.data?.meta?.totalPages ?? 1; return <CommunityPageFrame query={term} onQueryChange={(value) => { setTerm(value); setPage(1); window.history.replaceState(null, "", value ? "/search?q=" + encodeURIComponent(value) : "/search"); }}><PageHeader section="搜索" title={term ? "“" + term + "” 的结果" : "搜索"} description={term ? "优先展示匹配的攻略与社区内容。" : "输入关键词寻找攻略、声骸和玩家讨论。"} /><section className="guide-list-page">{result.isLoading ? <div className="feed-status">正在搜索鸣潮社区…</div> : items.length ? items.map((guide) => <GuideListItem guide={guide} key={guide.id} />) : <div className="empty-state"><Search size={20} /><p>没有找到匹配内容，试试角色名、声骸或攻略标签。</p></div>}{result.error && <p className="api-fallback-note">后端暂不可用，当前显示本地 Demo 数据。</p>}{totalPages > 1 && <nav className="pagination" aria-label="搜索结果分页">{Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNumber) => <button className={page === pageNumber ? "is-active" : ""} key={pageNumber} onClick={() => setPage(pageNumber)} type="button">{pageNumber}</button>)}</nav>}</section></CommunityPageFrame>; }
const publishFormSchema = z.object({
  title: z.string().trim().min(1, "请输入帖子标题").max(200, "标题长度不能超过 200 个字符"),
  content: z.string().trim().min(1, "请输入帖子正文").max(50000, "正文长度不能超过 50000 个字符"),
  type: z.enum(["攻略", "心得", "同人", "提问"]),
  tags: z.string().superRefine((value, context) => {
    const tags = value.split(/[，,\s]+/).map((tag) => tag.trim()).filter(Boolean);
    if (tags.length > 10) context.addIssue({ code: "custom", message: "最多添加 10 个标签" });
    if (tags.some((tag) => tag.length > 64)) context.addIssue({ code: "custom", message: "单个标签长度不能超过 64 个字符" });
  }),
});
type PublishFormValues = z.infer<typeof publishFormSchema>;
const publishDraftKey = "wuthering-community-publish-draft-v2";
const emptyPublishValues: PublishFormValues = { title: "", content: "", type: "心得", tags: "" };
const publishDraftValuesSchema = z.object({ title: z.string().max(200), content: z.string().max(50000), type: z.enum(["攻略", "心得", "同人", "提问"]), tags: z.string() });

type PublishDraft = { version: 2; savedAt: string; values: PublishFormValues };

function readPublishDraft() {
  try {
    const saved = window.localStorage.getItem(publishDraftKey);
    if (!saved) return null;
    const parsed = JSON.parse(saved) as Partial<PublishDraft>;
    if (parsed.version !== 2 || !parsed.values) {
      window.localStorage.removeItem(publishDraftKey);
      return null;
    }
    const result = publishDraftValuesSchema.safeParse(parsed.values);
    if (!result.success) window.localStorage.removeItem(publishDraftKey);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

type ToolbarButtonProps = { label: string; icon: ReactNode; disabled?: boolean; onClick: () => void };

function PublishToolbarButton({ label, icon, disabled = false, onClick }: ToolbarButtonProps) {
  return <button aria-label={label} className="publish-toolbar-button" disabled={disabled} onClick={onClick} title={label} type="button">{icon}</button>;
}

function PublishPageContent() {
  const params = useSearchParams();
  const router = useRouter();
  const { loggedIn, requestLogin, notify } = useCommunityDemo();
  const editPostId = params.get("edit");
  const defaultType = params.get("type") === "guide" ? "攻略" : params.get("type") === "creation" ? "同人" : "心得";
  const { control, register, handleSubmit, reset, getValues, setValue, formState: { errors } } = useForm<PublishFormValues>({
    resolver: zodResolver(publishFormSchema),
    defaultValues: { ...emptyPublishValues, type: defaultType },
  });
  const [error, setError] = useState("");
  const [publishing, setPublishing] = useState(false);
  const [imageUploading, setImageUploading] = useState(false);
  const [draftMessage, setDraftMessage] = useState("");
  const [historyAvailability, setHistoryAvailability] = useState({ canUndo: false, canRedo: false });
  const [loadingEdit, setLoadingEdit] = useState(Boolean(editPostId));
  const fileRef = useRef<HTMLInputElement>(null);
  const contentRef = useRef<HTMLTextAreaElement>(null);
  const historyRef = useRef<{ past: string[]; future: string[]; current: string }>({ past: [], future: [], current: "" });
  const title = useWatch({ control, name: "title", defaultValue: "" });
  const content = useWatch({ control, name: "content", defaultValue: "" });
  const tags = useWatch({ control, name: "tags", defaultValue: "" });

  useEffect(() => {
    if (!editPostId) {
      const draft = readPublishDraft();
      if (draft) {
        reset(draft);
        historyRef.current.current = draft.content;
        window.setTimeout(() => setDraftMessage("已恢复本地草稿"), 0);
      }
      return;
    }
    if (!loggedIn) return;
    let active = true;
    void fetchPost(editPostId).then((post) => {
      if (!active) return;
      const values = { title: post.title, content: post.content ?? "", type: post.type === "GUIDE" ? "攻略" : ["心得", "同人", "提问"].includes(post.category) ? post.category as PublishFormValues["type"] : "心得", tags: post.tags.join("、") };
      reset(values);
      historyRef.current.current = values.content;
    }).catch((requestError) => {
      if (active) setError(requestError instanceof Error ? requestError.message : "无法读取待编辑的帖子");
    }).finally(() => { if (active) setLoadingEdit(false); });
    return () => { active = false; };
  }, [editPostId, loggedIn, reset]);

  function recordContent(next: string) {
    const history = historyRef.current;
    if (next === history.current) return;
    historyRef.current = { past: [...history.past, history.current], future: [], current: next };
    setHistoryAvailability({ canUndo: true, canRedo: false });
  }

  function updateContent(next: string) {
    recordContent(next);
    setValue("content", next, { shouldDirty: true, shouldValidate: true });
  }

  function handleContentChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const next = event.target.value;
    recordContent(next);
    setValue("content", next, { shouldDirty: true, shouldValidate: false });
  }

  function selectionBounds() {
    const element = contentRef.current;
    const current = getValues("content");
    const start = element?.selectionStart ?? current.length;
    const end = element?.selectionEnd ?? start;
    return { current, element, start, end };
  }

  function replaceSelection(before: string, after = "", placeholder = "文本") {
    const { current, element, start, end } = selectionBounds();
    const selected = current.slice(start, end) || placeholder;
    const next = current.slice(0, start) + before + selected + after + current.slice(end);
    updateContent(next);
    window.requestAnimationFrame(() => {
      element?.focus();
      const selectionStart = start + before.length;
      element?.setSelectionRange(selectionStart, selectionStart + selected.length);
    });
  }

  function prefixSelectedLines(prefix: string) {
    const { current, element, start, end } = selectionBounds();
    const lineStart = current.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
    const selectedEnd = end === start ? current.indexOf("\n", start) : end;
    const lineEnd = selectedEnd < 0 ? current.length : selectedEnd;
    const selected = current.slice(lineStart, lineEnd);
    const nextLines = selected.split("\n").map((line) => line.startsWith(prefix) ? line : prefix + line).join("\n");
    const next = current.slice(0, lineStart) + nextLines + current.slice(lineEnd);
    updateContent(next);
    window.requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(lineStart, lineStart + nextLines.length);
    });
  }

  function undo() {
    const history = historyRef.current;
    const previous = history.past.at(-1);
    if (previous === undefined) return;
    historyRef.current = { past: history.past.slice(0, -1), future: [history.current, ...history.future], current: previous };
    setHistoryAvailability({ canUndo: history.past.length > 1, canRedo: true });
    setValue("content", previous, { shouldDirty: true, shouldValidate: true });
  }

  function redo() {
    const history = historyRef.current;
    const next = history.future[0];
    if (next === undefined) return;
    historyRef.current = { past: [...history.past, history.current], future: history.future.slice(1), current: next };
    setHistoryAvailability({ canUndo: true, canRedo: history.future.length > 1 });
    setValue("content", next, { shouldDirty: true, shouldValidate: true });
  }

  function insertLink() {
    const { current, element, start, end } = selectionBounds();
    const selected = current.slice(start, end) || "链接文字";
    const inserted = `[${selected}](https://)`;
    updateContent(current.slice(0, start) + inserted + current.slice(end));
    window.requestAnimationFrame(() => {
      element?.focus();
      const urlStart = start + selected.length + 3;
      element?.setSelectionRange(urlStart, urlStart + 8);
    });
  }

  async function uploadSelectedImage(file: File) {
    if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) { setError("仅支持 PNG、JPEG 或 WebP 图片"); return; }
    if (file.size > 5 * 1024 * 1024) { setError("图片大小不能超过 5 MB"); return; }
    setError("");
    setImageUploading(true);
    try {
      const uploaded = await uploadImage(file);
      const { current, element, start, end } = selectionBounds();
      const inserted = `![${uploaded.originalName ?? file.name}](${uploaded.url})`;
      updateContent(current.slice(0, start) + inserted + current.slice(end));
      setDraftMessage("图片已插入正文");
      window.requestAnimationFrame(() => {
        element?.focus();
        const cursor = start + inserted.length;
        element?.setSelectionRange(cursor, cursor);
      });
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "图片上传失败，请稍后重试");
    } finally {
      setImageUploading(false);
    }
  }

  function changeImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    void uploadSelectedImage(file);
    event.target.value = "";
  }

  function handleImageDrop(event: ReactDragEvent<HTMLDivElement>) {
    event.preventDefault();
    const file = event.dataTransfer.files[0];
    if (file) void uploadSelectedImage(file);
  }

  function saveDraft() {
    try {
      const values = getValues();
      const savedAt = new Date().toISOString();
      const draft: PublishDraft = { version: 2, savedAt, values };
      window.localStorage.setItem(publishDraftKey, JSON.stringify(draft));
      setDraftMessage("草稿已保存");
      notify("草稿已保存到本机");
    } catch {
      setError("本地草稿保存失败，请检查浏览器存储权限");
      notify("草稿保存失败");
    }
  }

  async function publish(values: PublishFormValues) {
    setPublishing(true);
    setError("");
    try {
      const input = { type: values.type === "攻略" ? "GUIDE" : "GENERAL", category: values.type === "攻略" ? "配队攻略" : values.type, title: values.title, content: values.content, tags: values.tags.split(/[，,\s]+/).map((tag) => tag.trim()).filter(Boolean) } as const;
      const post = editPostId ? await updatePost(editPostId, input) : await createPost(input);
      try { window.localStorage.removeItem(publishDraftKey); } catch { /* 发布成功不应被本地存储异常阻断。 */ }
      notify(editPostId ? "帖子已更新" : "内容已发布");
      router.push("/guides/" + post.id);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "发布失败，请稍后重试");
      notify(editPostId ? "保存失败，请检查表单内容" : "发布失败，请检查表单内容");
    } finally {
      setPublishing(false);
    }
  }

  function submit(values: PublishFormValues) {
    if (!loggedIn) { requestLogin(() => { void publish(values); }); return; }
    void publish(values);
  }

  if (editPostId && !loggedIn) return <div className="publish-state"><strong>登录后才能编辑这篇帖子</strong><span>请先登录，再继续修改你的鸣潮内容。</span><button className="primary-button" onClick={() => requestLogin()} type="button">立即登录</button></div>;
  if (loadingEdit) return <div className="publish-state"><span>正在载入帖子内容…</span></div>;
  const registerTitle = register("title");
  const registerType = register("type");
  const registerTags = register("tags");
  const registerContent = register("content");
  return <form aria-label="发布编辑器" className="publish-editor" id="publish-editor" onSubmit={(event) => { void handleSubmit(submit)(event); }}>
    <div className="publish-toolbar" role="toolbar" aria-label="Markdown 工具栏">
      <PublishToolbarButton label="撤销" disabled={!historyAvailability.canUndo} icon={<Undo2 size={18} />} onClick={undo} />
      <PublishToolbarButton label="重做" disabled={!historyAvailability.canRedo} icon={<Redo2 size={18} />} onClick={redo} />
      <span className="publish-toolbar-separator" />
      <span className="publish-toolbar-mode" aria-label="当前编辑模式：正文">正文</span>
      <span className="publish-toolbar-separator" />
      <PublishToolbarButton label="一级标题" icon={<Heading1 size={18} />} onClick={() => prefixSelectedLines("# ")} />
      <PublishToolbarButton label="二级标题" icon={<Heading2 size={18} />} onClick={() => prefixSelectedLines("## ")} />
      <PublishToolbarButton label="加粗" icon={<Bold size={18} />} onClick={() => replaceSelection("**", "**")} />
      <PublishToolbarButton label="斜体" icon={<Italic size={18} />} onClick={() => replaceSelection("*", "*")} />
      <PublishToolbarButton label="引用" icon={<Quote size={18} />} onClick={() => prefixSelectedLines("> ")} />
      <PublishToolbarButton label="无序列表" icon={<List size={18} />} onClick={() => prefixSelectedLines("- ")} />
      <PublishToolbarButton label="有序列表" icon={<ListOrdered size={18} />} onClick={() => prefixSelectedLines("1. ")} />
      <span className="publish-toolbar-separator" />
      <PublishToolbarButton label="插入链接" icon={<Link2 size={18} />} onClick={insertLink} />
      <PublishToolbarButton label="插入图片" disabled={imageUploading} icon={<ImagePlus size={18} />} onClick={() => fileRef.current?.click()} />
    </div>
    <div className="publish-editor-body">
      <div className="publish-title-field">
        <input aria-label="帖子标题" maxLength={200} placeholder="输入标题（必填）" {...registerTitle} />
        <span>{title.length} / 200</span>
      </div>
      {errors.title && <p className="publish-field-error" role="alert">{errors.title.message}</p>}
      <div className="publish-content-field" onDragOver={(event) => event.preventDefault()} onDrop={handleImageDrop}>
        <textarea aria-label="帖子正文" placeholder="在这里写下你的内容..." rows={18} {...registerContent} onChange={handleContentChange} ref={(element) => { registerContent.ref(element); contentRef.current = element; }} value={content} />
        {!content && <button aria-label="上传正文图片" className="publish-upload-prompt" onClick={() => fileRef.current?.click()} type="button"><ImagePlus size={25} /><span>点击上传图片，或直接拖拽到此处</span><small>支持 PNG、JPG、WebP，单张不超过 5MB</small></button>}
        {imageUploading && <span className="publish-uploading" role="status">图片上传中…</span>}
        <span className="publish-content-count">{content.length} / 50000</span>
      </div>
      {errors.content && <p className="publish-field-error" role="alert">{errors.content.message}</p>}
      <input accept="image/png,image/jpeg,image/webp" className="file-input" onChange={changeImage} ref={fileRef} type="file" />
      <section aria-label="帖子设置" className="publish-meta" id="publish-meta">
        <label><strong>内容类型</strong><select aria-label="内容类型" {...registerType}><option>攻略</option><option>心得</option><option>同人</option><option>提问</option></select></label>
        <span className="publish-meta-divider" />
        <label className="publish-tags-field"><strong>添加标签</strong><div><input aria-label="内容标签" placeholder="例如：长离、声骸" {...registerTags} /><span>{tags.split(/[，,\s]+/).filter(Boolean).length} / 10</span></div><small>按空格或逗号分隔标签，最多 10 个</small></label>
      </section>
      {errors.tags && <p className="publish-field-error" role="alert">{errors.tags.message}</p>}
      {draftMessage && <p aria-live="polite" className="publish-draft-message">{draftMessage}</p>}
      {error && <p className="publish-field-error" role="alert">{error}</p>}
    </div>
    <footer className="publish-action-bar">
      <div className="publish-action-inner"><span className="publish-character-count">正文字符：<strong>{content.length}</strong></span><button className="publish-settings-button" onClick={() => document.getElementById("publish-meta")?.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "center" })} type="button">帖子设置<ChevronDown size={14} /></button><span className="publish-public-note">发布后公开展示</span><button className="publish-draft-button" onClick={saveDraft} type="button">保存草稿</button><button className="publish-submit-button" disabled={publishing || imageUploading} type="submit"><Send size={17} />{publishing ? (editPostId ? "保存中…" : "发布中…") : editPostId ? "保存修改" : "发布"}</button></div>
    </footer>
  </form>;
}

export function PublishPage() {
  return <CommunityPageFrame activeNav="community" hideRail hideSidebar><PublishPageContent /></CommunityPageFrame>;
}

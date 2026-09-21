"use client";
import Image from "next/image";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Bold, ChevronDown, ChevronUp, Heading1, Heading2, ImagePlus, Italic, Link2, List, ListOrdered, Quote, Redo2, Send, Undo2, X } from "lucide-react";
import { useEffect, useRef, useState, type ChangeEvent, type DragEvent as ReactDragEvent, type ReactNode } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useWatch } from "react-hook-form";
import { z } from "zod";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { CommunityPageFrame } from "@/components/community/community-pages";
import { createPost, deleteImage, fetchPost, updatePost, uploadImage } from "@/lib/api";

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
type PostMediaDraft = { assetId: string; url: string; originalName: string; temporary: boolean };

function readPublishDraft() {
  try {
    const saved = window.localStorage.getItem(publishDraftKey);
    if (!saved) return null;
    const parsed = JSON.parse(saved) as Partial<PublishDraft>;
    if (parsed.version !== 2 || !parsed.values) { window.localStorage.removeItem(publishDraftKey); return null; }
    const result = publishDraftValuesSchema.safeParse(parsed.values);
    if (!result.success) window.localStorage.removeItem(publishDraftKey);
    return result.success ? result.data : null;
  } catch { return null; }
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
  const [postMedia, setPostMedia] = useState<PostMediaDraft[]>([]);
  const [postMediaUploading, setPostMediaUploading] = useState(false);
  const draggedMediaIndexRef = useRef<number | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const postMediaFileRef = useRef<HTMLInputElement>(null);
  const contentRef = useRef<HTMLTextAreaElement>(null);
  const historyRef = useRef<{ past: string[]; future: string[]; current: string }>({ past: [], future: [], current: "" });
  const title = useWatch({ control, name: "title", defaultValue: "" });
  const content = useWatch({ control, name: "content", defaultValue: "" });
  const tags = useWatch({ control, name: "tags", defaultValue: "" });

  useEffect(() => {
    if (!editPostId) {
      const draft = readPublishDraft();
      if (draft) { reset(draft); historyRef.current.current = draft.content; window.setTimeout(() => setDraftMessage("已恢复本地草稿"), 0); }
      return;
    }
    if (!loggedIn) return;
    let active = true;
    void fetchPost(editPostId).then((post) => {
      if (!active) return;
      const values = { title: post.title, content: post.content ?? "", type: post.type === "GUIDE" ? "攻略" : ["心得", "同人", "提问"].includes(post.category) ? post.category as PublishFormValues["type"] : "心得", tags: post.tags.join("、") };
      reset(values);
      setPostMedia(post.media?.slice().sort((left, right) => left.sortOrder - right.sortOrder).map((media) => ({ assetId: media.id, url: media.url, originalName: `帖子配图 ${media.sortOrder + 1}`, temporary: false })) ?? []);
      historyRef.current.current = values.content;
    }).catch((requestError) => { if (active) setError(requestError instanceof Error ? requestError.message : "无法读取待编辑的帖子"); }).finally(() => { if (active) setLoadingEdit(false); });
    return () => { active = false; };
  }, [editPostId, loggedIn, reset]);

  function recordContent(next: string) { const history = historyRef.current; if (next === history.current) return; historyRef.current = { past: [...history.past, history.current], future: [], current: next }; setHistoryAvailability({ canUndo: true, canRedo: false }); }
  function updateContent(next: string) { recordContent(next); setValue("content", next, { shouldDirty: true, shouldValidate: true }); }
  function handleContentChange(event: ChangeEvent<HTMLTextAreaElement>) { const next = event.target.value; recordContent(next); setValue("content", next, { shouldDirty: true, shouldValidate: false }); }
  function selectionBounds() { const element = contentRef.current; const current = getValues("content"); const start = element?.selectionStart ?? current.length; const end = element?.selectionEnd ?? start; return { current, element, start, end }; }
  function replaceSelection(before: string, after = "", placeholder = "文本") { const { current, element, start, end } = selectionBounds(); const selected = current.slice(start, end) || placeholder; const next = current.slice(0, start) + before + selected + after + current.slice(end); updateContent(next); window.requestAnimationFrame(() => { element?.focus(); const selectionStart = start + before.length; element?.setSelectionRange(selectionStart, selectionStart + selected.length); }); }
  function prefixSelectedLines(prefix: string) { const { current, element, start, end } = selectionBounds(); const lineStart = current.lastIndexOf("\n", Math.max(0, start - 1)) + 1; const selectedEnd = end === start ? current.indexOf("\n", start) : end; const lineEnd = selectedEnd < 0 ? current.length : selectedEnd; const selected = current.slice(lineStart, lineEnd); const nextLines = selected.split("\n").map((line) => line.startsWith(prefix) ? line : prefix + line).join("\n"); const next = current.slice(0, lineStart) + nextLines + current.slice(lineEnd); updateContent(next); window.requestAnimationFrame(() => { element?.focus(); element?.setSelectionRange(lineStart, lineStart + nextLines.length); }); }
  function undo() { const history = historyRef.current; const previous = history.past.at(-1); if (previous === undefined) return; historyRef.current = { past: history.past.slice(0, -1), future: [history.current, ...history.future], current: previous }; setHistoryAvailability({ canUndo: history.past.length > 1, canRedo: true }); setValue("content", previous, { shouldDirty: true, shouldValidate: true }); }
  function redo() { const history = historyRef.current; const next = history.future[0]; if (next === undefined) return; historyRef.current = { past: [...history.past, history.current], future: history.future.slice(1), current: next }; setHistoryAvailability({ canUndo: true, canRedo: history.future.length > 1 }); setValue("content", next, { shouldDirty: true, shouldValidate: true }); }
  function insertLink() { const { current, element, start, end } = selectionBounds(); const selected = current.slice(start, end) || "链接文字"; const inserted = `[${selected}](https://)`; updateContent(current.slice(0, start) + inserted + current.slice(end)); window.requestAnimationFrame(() => { element?.focus(); const urlStart = start + selected.length + 3; element?.setSelectionRange(urlStart, urlStart + 8); }); }

  async function uploadSelectedImage(file: File) { if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) { setError("仅支持 PNG、JPEG 或 WebP 图片"); return; } if (file.size > 10 * 1024 * 1024) { setError("图片大小不能超过 10 MB"); return; } setError(""); setImageUploading(true); try { const uploaded = await uploadImage(file); const { current, element, start, end } = selectionBounds(); const inserted = `![${uploaded.originalName ?? file.name}](${uploaded.url})`; updateContent(current.slice(0, start) + inserted + current.slice(end)); setDraftMessage("图片已插入正文"); window.requestAnimationFrame(() => { element?.focus(); const cursor = start + inserted.length; element?.setSelectionRange(cursor, cursor); }); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "图片上传失败，请稍后重试"); } finally { setImageUploading(false); } }
  async function uploadPostImages(files: File[]) { const remaining = 9 - postMedia.length; if (files.length > remaining) { setError(`每个帖子最多上传 9 张图片，还可以添加 ${remaining} 张`); return; } setError(""); setPostMediaUploading(true); try { for (const file of files) { if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) throw new Error("仅支持 PNG、JPEG 或 WebP 图片"); if (file.size > 10 * 1024 * 1024) throw new Error("图片大小不能超过 10 MB"); const uploaded = await uploadImage(file); setPostMedia((current) => [...current, { assetId: uploaded.assetId, url: uploaded.url, originalName: uploaded.originalName ?? file.name, temporary: true }]); } } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "帖子配图上传失败，请稍后重试"); } finally { setPostMediaUploading(false); } }
  function changePostMedia(event: ChangeEvent<HTMLInputElement>) { const files = Array.from(event.target.files ?? []); event.target.value = ""; if (files.length) void uploadPostImages(files); }
  function movePostMedia(index: number, direction: -1 | 1) { setPostMedia((current) => { const nextIndex = index + direction; if (nextIndex < 0 || nextIndex >= current.length) return current; const next = [...current]; [next[index], next[nextIndex]] = [next[nextIndex], next[index]]; return next; }); }
  function reorderPostMedia(fromIndex: number, toIndex: number) { if (fromIndex === toIndex) return; setPostMedia((current) => { if (fromIndex < 0 || toIndex < 0 || fromIndex >= current.length || toIndex >= current.length) return current; const next = [...current]; const [moved] = next.splice(fromIndex, 1); next.splice(toIndex, 0, moved); return next; }); }
  async function removePostMedia(media: PostMediaDraft) { setPostMedia((current) => current.filter((item) => item.assetId !== media.assetId)); if (!media.temporary) return; try { await deleteImage(media.assetId); } catch { setError("图片预览已移除，但服务器临时文件清理失败，请稍后重试"); } }
  function changeImage(event: ChangeEvent<HTMLInputElement>) { const file = event.target.files?.[0]; if (!file) return; void uploadSelectedImage(file); event.target.value = ""; }
  function handleImageDrop(event: ReactDragEvent<HTMLDivElement>) { event.preventDefault(); const file = event.dataTransfer.files[0]; if (file) void uploadSelectedImage(file); }
  function saveDraft() { try { const values = getValues(); const savedAt = new Date().toISOString(); const draft: PublishDraft = { version: 2, savedAt, values }; window.localStorage.setItem(publishDraftKey, JSON.stringify(draft)); setDraftMessage("草稿已保存"); notify("草稿已保存到本机"); } catch { setError("本地草稿保存失败，请检查浏览器存储权限"); notify("草稿保存失败"); } }
  async function publish(values: PublishFormValues) { setPublishing(true); setError(""); try { const input = { type: values.type === "攻略" ? "GUIDE" : "GENERAL", category: values.type === "攻略" ? "配队攻略" : values.type, title: values.title, content: values.content, tags: values.tags.split(/[，,\s]+/).map((tag) => tag.trim()).filter(Boolean), mediaAssetIds: postMedia.map((media) => media.assetId) } as const; const post = editPostId ? await updatePost(editPostId, input) : await createPost(input); try { window.localStorage.removeItem(publishDraftKey); } catch { /* 发布成功不应被本地存储异常阻断。 */ } notify(editPostId ? "帖子已更新" : "内容已发布"); router.push("/guides/" + post.id); } catch (requestError) { setError(requestError instanceof Error ? requestError.message : "发布失败，请稍后重试"); notify(editPostId ? "保存失败，请检查表单内容" : "发布失败，请检查表单内容"); } finally { setPublishing(false); } }
  function submit(values: PublishFormValues) { if (!loggedIn) { requestLogin(() => { void publish(values); }); return; } void publish(values); }

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
      <div className="publish-title-field"><input aria-label="帖子标题" maxLength={200} placeholder="输入标题（必填）" {...registerTitle} /><span>{title.length} / 200</span></div>
      {errors.title && <p className="publish-field-error" role="alert">{errors.title.message}</p>}
      <div className="publish-content-field" onDragOver={(event) => event.preventDefault()} onDrop={handleImageDrop}>
        <textarea aria-label="帖子正文" placeholder="在这里写下你的内容..." rows={18} {...registerContent} onChange={handleContentChange} ref={(element) => { registerContent.ref(element); contentRef.current = element; }} value={content} />
        {!content && <button aria-label="上传正文图片" className="publish-upload-prompt" onClick={() => fileRef.current?.click()} type="button"><ImagePlus size={25} /><span>点击上传图片，或直接拖拽到此处</span><small>支持 PNG、JPG、WebP，单张不超过 10MB</small></button>}
        {imageUploading && <span className="publish-uploading" role="status">图片上传中…</span>}
        <span className="publish-content-count">{content.length} / 50000</span>
      </div>
      {errors.content && <p className="publish-field-error" role="alert">{errors.content.message}</p>}
      <section aria-label="帖子配图" className="post-media-editor">
        <div className="post-media-editor-heading"><div><strong>帖子配图</strong><span>首页展示第一张，详情页可浏览全部图片</span></div><em>{postMedia.length} / 9</em></div>
        <div className="post-media-editor-grid">
          {postMedia.map((media, index) => <article aria-label={`第 ${index + 1} 张帖子配图`} className="post-media-item" data-asset-id={media.assetId} draggable key={media.assetId}
            onDragStart={() => { draggedMediaIndexRef.current = index; }}
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => { event.preventDefault(); if (draggedMediaIndexRef.current !== null) reorderPostMedia(draggedMediaIndexRef.current, index); draggedMediaIndexRef.current = null; }}
            onDragEnd={() => { draggedMediaIndexRef.current = null; }}>
            <Image alt={media.originalName} fill sizes="(max-width: 620px) 50vw, 180px" src={media.url} unoptimized />
            {index === 0 && <span className="post-media-cover">封面</span>}
            <div className="post-media-item-actions">
              <button aria-label={`图片 ${index + 1} 上移`} disabled={index === 0 || postMediaUploading} onClick={() => movePostMedia(index, -1)} type="button"><ChevronUp size={14} /></button>
              <button aria-label={`图片 ${index + 1} 下移`} disabled={index === postMedia.length - 1 || postMediaUploading} onClick={() => movePostMedia(index, 1)} type="button"><ChevronDown size={14} /></button>
              <button aria-label={`删除图片 ${index + 1}`} disabled={postMediaUploading} onClick={() => { void removePostMedia(media); }} type="button"><X size={14} /></button>
            </div>
          </article>)}
          {postMedia.length < 9 && <button aria-label="添加帖子配图" className="post-media-add" disabled={postMediaUploading} onClick={() => postMediaFileRef.current?.click()} type="button"><ImagePlus size={22} /><span>{postMediaUploading ? "上传中…" : "添加配图"}</span><small>PNG / JPG / WebP · 10MB 内</small></button>}
        </div>
        <input accept="image/png,image/jpeg,image/webp" aria-label="帖子配图" className="file-input" multiple onChange={changePostMedia} ref={postMediaFileRef} type="file" />
      </section>
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
      <div className="publish-action-inner"><span className="publish-character-count">正文字符：<strong>{content.length}</strong></span><button className="publish-settings-button" onClick={() => document.getElementById("publish-meta")?.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "center" })} type="button">帖子设置<ChevronDown size={14} /></button><span className="publish-public-note">发布后公开展示</span><button className="publish-draft-button" onClick={saveDraft} type="button">保存草稿</button><button className="publish-submit-button" disabled={publishing || imageUploading || postMediaUploading} type="submit"><Send size={17} />{publishing ? (editPostId ? "保存中…" : "发布中…") : editPostId ? "保存修改" : "发布"}</button></div>
    </footer>
  </form>;
}

export function PublishPage() {
  return <CommunityPageFrame activeNav="community" hideRail hideSidebar><PublishPageContent /></CommunityPageFrame>;
}

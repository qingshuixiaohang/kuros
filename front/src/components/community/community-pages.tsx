"use client";
import Link from "next/link";
import { ChevronRight, Eye, Heart, MessageSquare, MoveRight, X } from "lucide-react";
import { useState, type ReactNode } from "react";
import { HighlightText } from "@/components/community/search-highlight";
import { RightRail, Sidebar, TopNavigation, useCommunityDrawer } from "@/components/community/community-home";
import { guides } from "@/lib/mock";

type FrameProps = { children: ReactNode; activeNav?: string; query?: string; onQueryChange?: (query: string) => void; hideSidebar?: boolean; hideRail?: boolean; };
// 登录态 Provider 只在 layout 层挂载一份（社区全局唯一实例）。
// 此处不再嵌套 CommunityDemoProvider：嵌套会让每个页面各自跑一遍会话恢复、
// 且登录状态在页面间不共享——顶栏与页面内容可能读到不同实例的状态（状态分裂）。
export function CommunityPageFrame(props: FrameProps) { return <FrameContent {...props} />; }
function FrameContent({ children, activeNav = "community", query, onQueryChange, hideSidebar = false, hideRail = false }: FrameProps) { const [drawerOpen, setDrawerOpen] = useState(false); const [localQuery, setLocalQuery] = useState(""); const value = query ?? localQuery; const { drawerCloseRef, drawerRef } = useCommunityDrawer(drawerOpen, setDrawerOpen); return <main className="app-shell"><TopNavigation query={value} onQueryChange={onQueryChange ?? setLocalQuery} onMenu={() => setDrawerOpen(true)} activeNav={activeNav} />{drawerOpen && <div className="drawer-backdrop" onClick={() => setDrawerOpen(false)}><div aria-label="社区频道" aria-modal="true" className="mobile-drawer" onClick={(event) => event.stopPropagation()} ref={drawerRef} role="dialog"><div className="drawer-header"><span>频道</span><button onClick={() => setDrawerOpen(false)} ref={drawerCloseRef} type="button" aria-label="关闭导航"><X size={20} /></button></div><Sidebar drawer /></div></div>}<div className={"page-grid " + (hideSidebar && hideRail ? "page-grid--focused" : "")}>{!hideSidebar && <Sidebar />}<div className="main-column page-content">{children}</div>{!hideRail && <RightRail />}</div></main>; }
export function PageHeader({ section, title, description, action }: { section: string; title: string; description: string; action?: ReactNode }) { return <header className="subpage-header"><div><p className="page-breadcrumb">鸣潮社区 <ChevronRight size={13} /> {section}</p><h1>{title}</h1><p>{description}</p></div>{action}</header>; }
export function GuideListItem({ guide }: { guide: (typeof guides)[number] }) { return <article className="guide-list-item"><div className="guide-list-top"><span className="guide-type">{guide.category}</span><time>{guide.publishedAt}</time></div><Link href={"/guides/" + guide.id}><h2><HighlightText fallback={guide.title} fragments={guide.highlight?.title} /></h2></Link><p><HighlightText fallback={guide.excerpt} fragments={guide.highlight?.excerpt ?? guide.highlight?.content} /></p><div className="guide-list-meta"><span>{guide.author}</span><span><Eye size={14} />{guide.views}</span><span><MessageSquare size={14} />{guide.replies}</span><span><Heart size={14} />{guide.likes}</span></div><Link className="read-link" href={"/guides/" + guide.id}>查看攻略 <MoveRight size={15} /></Link></article>; }

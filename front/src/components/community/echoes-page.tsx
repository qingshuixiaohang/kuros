"use client";
import Link from "next/link";
import { ArrowLeft, ChevronRight, Search, Sparkles } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { echoSets } from "@/lib/mock";

export function EchoesPage() {
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState("套装");
  const visible = echoSets.filter((item) =>
    (item.name + item.effect + item.location).includes(query.trim())
  );
  return (
    <CommunityPageFrame query={query} onQueryChange={setQuery}>
      <PageHeader section="图鉴 / 声骸" title="声骸图鉴" description="查询套装效果、适配角色与常见掉落位置。" action={<Link className="primary-button" href="/search?q=声骸"><Search size={16} />搜索声骸</Link>} />
      <div className="echo-toolbar"><div>{["套装", "单体声骸", "掉落位置"].map((item) => <button className={mode === item ? "is-active" : ""} key={item} onClick={() => setMode(item)} type="button">{item}</button>)}</div><span>已收录 {visible.length} 套</span></div>
      <section className="echo-table"><div className="echo-table-head"><span>套装名称</span><span>{mode === "掉落位置" ? "推荐掉落区域" : "两件套效果"}</span><span>推荐掉落区域</span><span>操作</span></div>{visible.map((item) => <div className="echo-row" key={item.id}><div><span className="echo-symbol"><Sparkles size={16} /></span><strong>{item.name}</strong></div><p>{mode === "掉落位置" ? item.location : item.effect}</p><p>{item.location}</p><Link href={"/echoes/" + item.id}>查看搭配 <ChevronRight size={14} /></Link></div>)}</section>
    </CommunityPageFrame>
  );
}

export function EchoDetailPage({ id }: { id: string }) {
  const echo = echoSets.find((item) => item.id === id) ?? echoSets[0];
  return (
    <CommunityPageFrame>
      <article className="detail-page">
        <Link className="back-link" href="/echoes"><ArrowLeft size={15} />返回声骸图鉴</Link>
        <div className="detail-heading"><span className="guide-type">{echo.role}向套装</span><h1>{echo.name}</h1><p>{echo.description}</p></div>
        <section className="entity-note"><h2>套装信息</h2><p><strong>两件套效果：</strong>{echo.effect}</p><p><strong>推荐掉落：</strong>{echo.location}</p><Link className="primary-button" href={"/guides/" + echo.guideId}>查看适配攻略</Link></section>
      </article>
    </CommunityPageFrame>
  );
}

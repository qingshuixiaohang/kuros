"use client";
import Image from "next/image";
import Link from "next/link";
import { ArrowLeft, ChevronRight, Wrench } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { characters } from "@/lib/mock";

export function CharactersPage() {
  const [query, setQuery] = useState("");
  const [role, setRole] = useState("全部角色");
  const roles = ["全部角色", "输出", "协同", "辅助"];
  const visible = characters.filter((item) =>
    (role === "全部角色" || item.role === role) &&
    (item.name + item.title + item.description).includes(query.trim())
  );
  return (
    <CommunityPageFrame query={query} onQueryChange={setQuery}>
      <PageHeader section="图鉴 / 角色" title="角色图鉴" description="记录每位共鸣者的定位、属性与培养方向。" action={<Link className="secondary-button" href="/tools/team-builder"><Wrench size={16} />配队模拟</Link>} />
      <div className="catalog-tabs">{roles.map((item) => <button className={role === item ? "is-active" : ""} key={item} onClick={() => setRole(item)} type="button">{item}</button>)}</div>
      <section className="character-grid">{visible.map((item) => <article className="character-card" key={item.id}><Link className="character-image" href={"/characters/" + item.id}><Image alt={item.name + "角色立绘"} fill sizes="(max-width: 620px) 50vw, 260px" src={item.image} /></Link><div className="character-copy"><div><h2>{item.name}</h2><span>{item.title}</span></div><p>{item.description}</p><Link href={"/characters/" + item.id}>查看培养攻略 <ChevronRight size={14} /></Link></div></article>)}</section>
    </CommunityPageFrame>
  );
}

export function CharacterDetailPage({ id }: { id: string }) {
  const character = characters.find((item) => item.id === id) ?? characters[0];
  return (
    <CommunityPageFrame>
      <article className="detail-page entity-detail">
        <Link className="back-link" href="/characters"><ArrowLeft size={15} />返回角色图鉴</Link>
        <div className="entity-hero"><div><span className="guide-type">{character.title}</span><h1>{character.name}</h1><p>{character.description}</p><Link className="primary-button" href={"/guides/" + character.guideId}>查看培养攻略</Link></div><div className="entity-image"><Image alt={character.name + "角色立绘"} fill sizes="300px" src={character.image} /></div></div>
        <section className="entity-note"><h2>培养方向</h2><p>以稳定循环为优先，先补足角色核心技能等级，再根据队伍需求逐步完善声骸与武器。该页面为 Demo 图鉴内容，后端接入后可展示实时材料与面板数据。</p></section>
      </article>
    </CommunityPageFrame>
  );
}

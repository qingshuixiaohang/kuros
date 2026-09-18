"use client";

import Image from "next/image";
import Link from "next/link";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ArrowLeft, ChevronRight, Wrench } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { fetchCharacter, fetchCharacterPage, type ApiCharacter } from "@/lib/api";

const roles = ["全部角色", "输出", "协同", "辅助"];
const pageSize = 12;

export function CharactersPage() {
  const [query, setQuery] = useState("");
  const [role, setRole] = useState("全部角色");
  const [page, setPage] = useState(1);
  const characters = useQuery({
    queryKey: ["characters", role, query, page],
    queryFn: () => fetchCharacterPage({ role, keyword: query, page, pageSize }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const items = characters.data?.items ?? [];
  const totalPages = characters.data?.meta?.totalPages ?? 1;
  const changeQuery = (value: string) => {
    setQuery(value);
    setPage(1);
  };
  const changeRole = (value: string) => {
    setRole(value);
    setPage(1);
  };

  return <CommunityPageFrame query={query} onQueryChange={changeQuery}>
    <PageHeader section="图鉴 / 角色" title="角色图鉴" description="记录每位共鸣者的定位、属性与基础资料。" action={<Link className="secondary-button" href="/tools/team-builder"><Wrench size={16} />配队模拟</Link>} />
    <div className="catalog-tabs">{roles.map((item) => <button className={role === item ? "is-active" : ""} key={item} onClick={() => changeRole(item)} type="button">{item}</button>)}</div>
    {characters.isPending ? <div className="feed-status">正在加载角色资料…</div> : characters.isError ? <div className="empty-state"><p>角色资料暂时无法加载，请检查后端服务。</p><button className="secondary-button" onClick={() => characters.refetch()} type="button">重新加载</button></div> : items.length === 0 ? <div className="empty-state"><p>没有找到符合条件的角色。</p></div> : <><section className="character-grid">{items.map((item) => <article className="character-card" key={item.id}><Link className="character-image" href={"/characters/" + item.slug}><Image alt={item.name + "角色立绘"} fill sizes="(max-width: 620px) 50vw, 260px" src={item.imageUrl} /></Link><div className="character-copy"><div><h2>{item.name}</h2><span>{item.role}</span></div><p>{item.description}</p><small>{item.attribute} · {item.weaponType} · {item.version}</small><Link href={"/characters/" + item.slug}>查看角色资料 <ChevronRight size={14} /></Link></div></article>)}</section>{totalPages > 1 && <nav className="pagination" aria-label="角色图鉴分页">{Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNumber) => <button className={page === pageNumber ? "is-active" : ""} key={pageNumber} onClick={() => setPage(pageNumber)} type="button">{pageNumber}</button>)}</nav>}</>}
  </CommunityPageFrame>;
}

export function CharacterDetailPage({ slug }: { slug: string }) {
  const character = useQuery<ApiCharacter>({
    queryKey: ["character", slug],
    queryFn: () => fetchCharacter(slug),
    retry: false,
  });

  return <CommunityPageFrame>
    <article className="detail-page entity-detail"><Link className="back-link" href="/characters"><ArrowLeft size={15} />返回角色图鉴</Link>
      {character.isPending ? <div className="feed-status">正在加载角色资料…</div> : character.isError || !character.data ? <div className="empty-state"><p>角色不存在、已下架或暂时无法加载。</p><Link className="secondary-button" href="/characters">返回角色图鉴</Link></div> : <><div className="entity-hero"><div><span className="guide-type">{character.data.role} · {character.data.rarity}星</span><h1>{character.data.name}</h1><p>{character.data.description}</p><div className="entity-meta"><span>{character.data.attribute}</span><span>{character.data.weaponType}</span><span>登场版本 {character.data.version}</span></div><Link className="primary-button" href={"/search?q=" + encodeURIComponent(character.data.name)}>查看相关攻略</Link></div><div className="entity-image"><Image alt={character.data.name + "角色立绘"} fill sizes="300px" src={character.data.imageUrl} /></div></div><section className="entity-note"><h2>基础资料</h2><p>定位：{character.data.role}　属性：{character.data.attribute}　武器：{character.data.weaponType}</p><p>当前页面展示已发布的基础角色资料，培养材料与推荐配队将在后续版本接入。</p></section></>}
    </article>
  </CommunityPageFrame>;
}

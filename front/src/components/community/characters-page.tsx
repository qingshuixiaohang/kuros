"use client";
import Image from "next/image";
import Link from "next/link";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ArrowLeft, ChevronRight, Sparkles, Wrench } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { fetchCharacter, fetchCharacterPage, type ApiCharacter } from "@/lib/api";

const roles = ["全部角色", "输出", "协同", "辅助"];
const elements = ["全部属性", "湮灭", "导电", "热熔", "冷凝", "气动", "衍射"];
const weapons = ["全部武器", "迅刀", "长刃", "佩枪", "臂铠", "音感仪"];
const rarities = ["全部稀有度", "5★", "4★"];
const pageSize = 12;

export function CharactersPage() {
  const [query, setQuery] = useState("");
  const [role, setRole] = useState("全部角色");
  const [element, setElement] = useState("全部属性");
  const [weapon, setWeapon] = useState("全部武器");
  const [rarity, setRarity] = useState("全部稀有度");
  const [page, setPage] = useState(1);

  const characters = useQuery({
    queryKey: ["characters", role, element, weapon, rarity, query, page],
    queryFn: () => fetchCharacterPage({
      role: role === "全部角色" ? undefined : role,
      attribute: element === "全部属性" ? undefined : element,
      weapon: weapon === "全部武器" ? undefined : weapon,
      rarity: rarity === "全部稀有度" ? undefined : Number(rarity.replace("★", "")),
      keyword: query,
      page,
      pageSize,
    }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const items = characters.data?.items ?? [];
  const totalPages = characters.data?.meta?.totalPages ?? 1;
  const resetPage = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setPage(1);
  };

  return (
    <CommunityPageFrame query={query} onQueryChange={resetPage(setQuery)}>
      <PageHeader section="图鉴 / 角色" title="角色图鉴" description="记录每位共鸣者的定位、属性与培养方向。" action={<Link className="secondary-button" href="/tools/team-builder"><Wrench size={16} />配队模拟</Link>} />

      {/* 角色定位 */}
      <div className="catalog-tabs">{roles.map((item) => <button className={role === item ? "is-active" : ""} key={item} onClick={() => resetPage(setRole)(item)} type="button">{item}</button>)}</div>

      {/* 属性筛选 */}
      <div className="catalog-tabs catalog-tabs--sub">
        <span className="catalog-tabs-label">属性</span>
        {elements.map((item) => <button className={element === item ? "is-active" : ""} key={item} onClick={() => resetPage(setElement)(item)} type="button">{item}</button>)}
      </div>

      {/* 武器筛选 */}
      <div className="catalog-tabs catalog-tabs--sub">
        <span className="catalog-tabs-label">武器</span>
        {weapons.map((item) => <button className={weapon === item ? "is-active" : ""} key={item} onClick={() => resetPage(setWeapon)(item)} type="button">{item}</button>)}
      </div>

      {/* 稀有度筛选 */}
      <div className="catalog-tabs catalog-tabs--sub">
        <span className="catalog-tabs-label">稀有度</span>
        {rarities.map((item) => <button className={rarity === item ? "is-active" : ""} key={item} onClick={() => resetPage(setRarity)(item)} type="button">{item}</button>)}
      </div>

      {characters.isPending ? (
        <div className="feed-status">正在加载角色资料…</div>
      ) : characters.isError ? (
        <div className="empty-state">
          <p>角色资料暂时无法加载，请检查后端服务。</p>
          <button className="secondary-button" onClick={() => characters.refetch()} type="button">重新加载</button>
        </div>
      ) : items.length === 0 ? (
        <div className="empty-state"><p>没有找到符合条件的角色。</p></div>
      ) : (
        <>
          <section className="character-grid">
            {items.map((item) => (
              <article className="character-card" key={item.id}>
                <Link className="character-image" href={"/characters/" + item.slug}>
                  <Image alt={item.name + "角色立绘"} fill sizes="(max-width: 620px) 50vw, 260px" src={item.imageUrl} />
                </Link>
                <div className="character-copy">
                  <div>
                    <h2>{item.name}</h2>
                    <span>{item.role}</span>
                  </div>
                  <div className="character-tags">
                    <span className="character-tag character-tag--element">{item.attribute}</span>
                    <span className="character-tag character-tag--weapon">{item.weaponType}</span>
                    <span className="character-tag character-tag--rarity">{item.rarity}★</span>
                  </div>
                  <p>{item.description}</p>
                  <Link href={"/characters/" + item.slug}>查看角色资料 <ChevronRight size={14} /></Link>
                </div>
              </article>
            ))}
          </section>
          {totalPages > 1 && (
            <nav className="pagination" aria-label="角色图鉴分页">
              {Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNumber) => (
                <button className={page === pageNumber ? "is-active" : ""} key={pageNumber} onClick={() => setPage(pageNumber)} type="button">{pageNumber}</button>
              ))}
            </nav>
          )}
        </>
      )}
    </CommunityPageFrame>
  );
}

export function CharacterDetailPage({ slug }: { slug: string }) {
  const character = useQuery<ApiCharacter>({
    queryKey: ["character", slug],
    queryFn: () => fetchCharacter(slug),
    retry: false,
  });

  return (
    <CommunityPageFrame>
      <article className="detail-page entity-detail">
        <Link className="back-link" href="/characters"><ArrowLeft size={15} />返回角色图鉴</Link>

        {character.isPending ? (
          <div className="feed-status">正在加载角色资料…</div>
        ) : character.isError || !character.data ? (
          <div className="empty-state">
            <p>角色不存在、已下架或暂时无法加载。</p>
            <Link className="secondary-button" href="/characters">返回角色图鉴</Link>
          </div>
        ) : (
          <>
            <div className="entity-hero">
              <div>
                <span className="guide-type">{character.data.role} · {character.data.rarity}星</span>
                <h1>{character.data.name}</h1>
                <p>{character.data.description}</p>
                <div className="character-tags" style={{ marginTop: 12 }}>
                  <span className="character-tag character-tag--element">{character.data.attribute}</span>
                  <span className="character-tag character-tag--weapon">{character.data.weaponType}</span>
                  <span className="character-tag character-tag--role">{character.data.role}</span>
                  <span className="character-tag character-tag--rarity">{character.data.rarity}★</span>
                </div>
                <Link className="primary-button" href={"/search?q=" + encodeURIComponent(character.data.name)} style={{ marginTop: 16, display: "inline-flex" }}>查看相关攻略</Link>
              </div>
              <div className="entity-image">
                <Image alt={character.data.name + "角色立绘"} fill sizes="300px" src={character.data.imageUrl} />
              </div>
            </div>

            <section className="entity-note">
              <h2><Sparkles size={17} style={{ display: "inline", verticalAlign: "text-bottom", marginRight: 6 }} />基础资料</h2>
              <p>定位：{character.data.role}　属性：{character.data.attribute}　武器：{character.data.weaponType}　登场版本：{character.data.version}</p>
              <p>当前页面展示已发布的基础角色资料，培养材料与推荐配队将在后续版本接入。</p>
            </section>
          </>
        )}
      </article>
    </CommunityPageFrame>
  );
}

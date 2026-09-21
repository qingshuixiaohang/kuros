"use client";
import Image from "next/image";
import Link from "next/link";
import { ArrowLeft, ChevronRight, Sparkles, Swords, Wrench } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { characters } from "@/lib/mock";
import type { CharacterElement, CharacterWeapon } from "@/types/community";

const roles = ["全部角色", "输出", "协同", "辅助"] as const;
const elements: readonly (CharacterElement | "全部属性")[] = ["全部属性", "湮灭", "导电", "热熔", "冷凝", "气动", "衍射"];
const weapons: readonly (CharacterWeapon | "全部武器")[] = ["全部武器", "迅刀", "长刃", "佩枪", "臂铠", "音感仪"];
const rarities = ["全部稀有度", "5★", "4★"] as const;

export function CharactersPage() {
  const [query, setQuery] = useState("");
  const [role, setRole] = useState<string>("全部角色");
  const [element, setElement] = useState<string>("全部属性");
  const [weapon, setWeapon] = useState<string>("全部武器");
  const [rarity, setRarity] = useState<string>("全部稀有度");

  const visible = characters.filter((item) =>
    (role === "全部角色" || item.role === role) &&
    (element === "全部属性" || item.element === element) &&
    (weapon === "全部武器" || item.weapon === weapon) &&
    (rarity === "全部稀有度" || item.rarity + "★" === rarity) &&
    (item.name + item.title + item.description).includes(query.trim())
  );

  return (
    <CommunityPageFrame query={query} onQueryChange={setQuery}>
      <PageHeader section="图鉴 / 角色" title="角色图鉴" description="记录每位共鸣者的定位、属性与培养方向。" action={<Link className="secondary-button" href="/tools/team-builder"><Wrench size={16} />配队模拟</Link>} />

      {/* 角色定位 */}
      <div className="catalog-tabs">{roles.map((item) => <button className={role === item ? "is-active" : ""} key={item} onClick={() => setRole(item)} type="button">{item}</button>)}</div>

      {/* 属性筛选 */}
      <div className="catalog-tabs catalog-tabs--sub">
        <span className="catalog-tabs-label">属性</span>
        {elements.map((item) => <button className={element === item ? "is-active" : ""} key={item} onClick={() => setElement(item)} type="button">{item}</button>)}
      </div>

      {/* 武器筛选 */}
      <div className="catalog-tabs catalog-tabs--sub">
        <span className="catalog-tabs-label">武器</span>
        {weapons.map((item) => <button className={weapon === item ? "is-active" : ""} key={item} onClick={() => setWeapon(item)} type="button">{item}</button>)}
      </div>

      {/* 稀有度筛选 */}
      <div className="catalog-tabs catalog-tabs--sub">
        <span className="catalog-tabs-label">稀有度</span>
        {rarities.map((item) => <button className={rarity === item ? "is-active" : ""} key={item} onClick={() => setRarity(item)} type="button">{item}</button>)}
      </div>

      <section className="character-grid">
        {visible.map((item) => (
          <article className="character-card" key={item.id}>
            <Link className="character-image" href={"/characters/" + item.id}>
              <Image alt={item.name + "角色立绘"} fill sizes="(max-width: 620px) 50vw, 260px" src={item.image} />
            </Link>
            <div className="character-copy">
              <div>
                <h2>{item.name}</h2>
                <span>{item.title}</span>
              </div>
              <div className="character-tags">
                <span className="character-tag character-tag--element">{item.element}</span>
                <span className="character-tag character-tag--weapon">{item.weapon}</span>
                <span className="character-tag character-tag--rarity">{item.rarity}★</span>
              </div>
              <p>{item.description}</p>
              <Link href={"/characters/" + item.id}>查看培养攻略 <ChevronRight size={14} /></Link>
            </div>
          </article>
        ))}
        {visible.length === 0 && <p className="empty-state">没有找到匹配的角色。</p>}
      </section>
    </CommunityPageFrame>
  );
}

export function CharacterDetailPage({ id }: { id: string }) {
  const character = characters.find((item) => item.id === id) ?? characters[0];
  return (
    <CommunityPageFrame>
      <article className="detail-page entity-detail">
        <Link className="back-link" href="/characters"><ArrowLeft size={15} />返回角色图鉴</Link>

        <div className="entity-hero">
          <div>
            <span className="guide-type">{character.title}</span>
            <h1>{character.name}</h1>
            <p>{character.description}</p>
            <div className="character-tags" style={{ marginTop: 12 }}>
              <span className="character-tag character-tag--element">{character.element}</span>
              <span className="character-tag character-tag--weapon">{character.weapon}</span>
              <span className="character-tag character-tag--role">{character.role}</span>
              <span className="character-tag character-tag--rarity">{character.rarity}★</span>
            </div>
            <Link className="primary-button" href={"/guides/" + character.guideId} style={{ marginTop: 16, display: "inline-flex" }}>查看培养攻略</Link>
          </div>
          <div className="entity-image">
            <Image alt={character.name + "角色立绘"} fill sizes="300px" src={character.image} />
          </div>
        </div>

        {/* 基础属性表 */}
        <section className="entity-note">
          <h2><Sparkles size={17} style={{ display: "inline", verticalAlign: "text-bottom", marginRight: 6 }} />基础属性（Lv.90）</h2>
          <div className="attribute-table">
            <div className="attribute-row"><span>生命值</span><strong>{character.attributes.hp.toLocaleString()}</strong></div>
            <div className="attribute-row"><span>攻击力</span><strong>{character.attributes.atk}</strong></div>
            <div className="attribute-row"><span>防御力</span><strong>{character.attributes.def}</strong></div>
            <div className="attribute-row"><span>暴击率</span><strong>{character.attributes.critRate}</strong></div>
            <div className="attribute-row"><span>暴击伤害</span><strong>{character.attributes.critDmg}</strong></div>
          </div>
        </section>

        {/* 技能列表 */}
        <section className="entity-note">
          <h2><Swords size={17} style={{ display: "inline", verticalAlign: "text-bottom", marginRight: 6 }} />技能</h2>
          <div className="skill-list">
            {character.skills.map((skill) => (
              <div className="skill-card" key={skill.type}>
                <div className="skill-card-header">
                  <strong>{skill.name}</strong>
                  <span className="skill-type-badge">{skill.type}</span>
                </div>
                <p>{skill.description}</p>
              </div>
            ))}
          </div>
        </section>

        <section className="entity-note">
          <h2>培养方向</h2>
          <p>以稳定循环为优先，先补足角色核心技能等级，再根据队伍需求逐步完善声骸与武器。该页面为 Demo 图鉴内容，后端接入后可展示实时材料与面板数据。</p>
        </section>
      </article>
    </CommunityPageFrame>
  );
}

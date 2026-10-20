"use client";
import Image from "next/image";
import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { fetchCharacterPage, type ApiCharacter } from "@/lib/api";
import { toolItems } from "@/lib/mock";

export function ToolsPage() {
  return (
    <CommunityPageFrame activeNav="tools">
      <PageHeader section="实用工具" title="漂泊者工具箱" description="把常用的养成计算、声骸查询与配队思路收在同一处。" />
      <section className="tools-page-grid">
        {toolItems.map((tool) => {
          const href = tool.slug === "echo" ? "/echoes" : "/tools/" + tool.slug;
          return (
            <Link href={href} key={tool.slug}>
              <article className="tool-page-card">
                <span>
                  <Image alt="" fill sizes="64px" src={tool.iconSrc} />
                </span>
                <h2>{tool.title}</h2>
                <p>{tool.description}</p>
                <small>
                  打开工具 <ChevronRight size={13} />
                </small>
              </article>
            </Link>
          );
        })}
      </section>
    </CommunityPageFrame>
  );
}

export function ToolDetailPage({ slug }: { slug: string }) {
  // 未知 slug 不再回退到第一个工具冒充（架构巡检 #2 同模式）：hooks 全部无条件调用后再早退
  const tool = toolItems.find((item) => item.slug === slug);
  const [level, setLevel] = useState(1);
  const [weapon, setWeapon] = useState(1);
  const [members, setMembers] = useState<string[]>([]);
  const teamCharacters = useQuery({
    queryKey: ["team-builder-characters"],
    queryFn: () => fetchCharacterPage({ pageSize: 50 }),
    enabled: slug === "team-builder",
    retry: false,
  });
  const characters: ApiCharacter[] = teamCharacters.data?.items ?? [];
  if (!tool)
    return (
      <CommunityPageFrame activeNav="tools">
        <div className="detail-page entity-detail">
          <Link className="back-link" href="/tools">
            <ChevronRight size={15} />
            返回工具箱
          </Link>
          <div className="empty-state">
            <p>工具不存在或已下架。</p>
            <Link className="secondary-button" href="/tools">
              返回工具箱
            </Link>
          </div>
        </div>
      </CommunityPageFrame>
    );
  const isTeam = tool.slug === "team-builder";
  const total = (level * 1200 + weapon * 780).toLocaleString();
  function toggleMember(id: string) {
    setMembers((current) =>
      current.includes(id) ? current.filter((entry) => entry !== id) : current.length === 3 ? [...current.slice(1), id] : [...current, id],
    );
  }
  return (
    <CommunityPageFrame activeNav="tools">
      <PageHeader section={"实用工具 / " + tool.title} title={tool.title} description={tool.description} />
      <section className="tool-workspace">
        {isTeam ? (
          <>
            <div className="team-slots">
              {[0, 1, 2].map((index) => (
                <div className="team-slot" key={index}>
                  {members[index] ? characters.find((item) => item.id === members[index])?.name : "选择角色"}
                </div>
              ))}
            </div>
            <div className="tool-choice-list">
              {teamCharacters.isPending ? (
                <p className="tool-result" role="status">
                  角色列表加载中…
                </p>
              ) : teamCharacters.isError ? (
                <div className="tool-result" role="alert">
                  <p>角色资料暂时无法加载，请检查后端服务。</p>
                  <button className="secondary-button" onClick={() => void teamCharacters.refetch()} type="button">
                    重试
                  </button>
                </div>
              ) : characters.length === 0 ? (
                <p className="tool-result" role="status">
                  暂无可用于配队的角色。
                </p>
              ) : (
                characters.map((item) => (
                  <button className={members.includes(item.id) ? "is-active" : ""} key={item.id} onClick={() => toggleMember(item.id)} type="button">
                    {item.name}
                    <small>{item.role}</small>
                  </button>
                ))
              )}
            </div>
            <p className="tool-result">
              {members.length ? "当前队伍已记录 " + members.length + " 位角色。Demo 版用于梳理轮切思路。" : "从下方选择至多三位角色，开始构建队伍。"}
            </p>
          </>
        ) : (
          <>
            <div className="calculator-fields">
              <label>
                角色等级
                <input max="90" min="1" onChange={(event) => setLevel(Number(event.target.value))} type="number" value={level} />
              </label>
              <label>
                武器等级
                <input max="90" min="1" onChange={(event) => setWeapon(Number(event.target.value))} type="number" value={weapon} />
              </label>
            </div>
            <div className="tool-result">
              <span>预计养成素材</span>
              <strong>{total}</strong>
              <small>按当前等级差估算；接入数据服务后可展示真实材料明细。</small>
            </div>
          </>
        )}
      </section>
    </CommunityPageFrame>
  );
}

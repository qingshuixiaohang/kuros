"use client";
import Link from "next/link";
import { ArrowLeft, ChevronRight, Search, Sparkles } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { useEchoDetailQuery, useEchoListQuery } from "@/lib/community-queries";

/** 视角 tab。切 tab 只换显示列，数据始终是全量列表——不是数据过滤。 */
const VIEW_TABS = ["套装", "单体声骸", "掉落位置"];

export function EchoesPage() {
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState("套装");
  // 声骸接真 API（H-9）：此前是 mock.ts 的 echoSets 硬编码数组，改内容要改代码发版。
  // 定位过滤（输出/协同/治疗）是服务端做的 role 查询参数，这里不重复实现
  const echoesQuery = useEchoListQuery();
  const visible = (echoesQuery.data?.items ?? []).filter((item) => (item.name + item.effect + item.location).includes(query.trim()));
  return (
    <CommunityPageFrame query={query} onQueryChange={setQuery}>
      <PageHeader
        section="图鉴 / 声骸"
        title="声骸图鉴"
        description="查询套装效果、适配角色与常见掉落位置。"
        action={
          <Link className="primary-button" href="/search?q=声骸">
            <Search size={16} />
            搜索声骸
          </Link>
        }
      />
      <div className="echo-toolbar">
        <div aria-label="声骸展示视角" role="group">
          {VIEW_TABS.map((item) => (
            <button aria-pressed={mode === item} className={mode === item ? "is-active" : ""} key={item} onClick={() => setMode(item)} type="button">
              {item}
            </button>
          ))}
        </div>
        <span>已收录 {visible.length} 套</span>
      </div>
      <section className="echo-table">
        <div className="echo-table-head">
          <span>套装名称</span>
          <span>{mode === "掉落位置" ? "推荐掉落区域" : "两件套效果"}</span>
          <span>推荐掉落区域</span>
          <span>操作</span>
        </div>
        {echoesQuery.isPending ? <p className="admin-empty">正在整理声骸图鉴…</p> : null}
        {echoesQuery.isError ? <p className="admin-empty">声骸图鉴加载失败，请稍后重试。</p> : null}
        {!echoesQuery.isPending && !echoesQuery.isError && visible.length === 0 ? <p className="admin-empty">没有找到匹配的声骸。</p> : null}
        {visible.map((item) => (
          <div className="echo-row" key={item.id}>
            <div>
              <span className="echo-symbol">
                <Sparkles size={16} />
              </span>
              <strong>{item.name}</strong>
            </div>
            <p>{mode === "掉落位置" ? item.location : item.effect}</p>
            <p>{item.location}</p>
            <Link href={"/echoes/" + item.slug}>
              查看搭配 <ChevronRight size={14} />
            </Link>
          </div>
        ))}
      </section>
    </CommunityPageFrame>
  );
}

export function EchoDetailPage({ id }: { id: string }) {
  // id 即 slug（对外契约用 slug，不用内部 UUID）
  // 未知/未上线 slug 不再回退到第一套声骸冒充（架构巡检 #2 同模式）——
  // 接真 API 后这条由后端 ECHO_NOT_FOUND 保证，前端只需把失败态显示出来
  const echoQuery = useEchoDetailQuery(id);
  const echo = echoQuery.data;
  if (!echo)
    return (
      <CommunityPageFrame>
        <article className="detail-page">
          <Link className="back-link" href="/echoes">
            <ArrowLeft size={15} />
            返回声骸图鉴
          </Link>
          <div className="empty-state">
            <p>{echoQuery.isError ? "声骸套装加载失败，请稍后重试。" : "声骸套装不存在或已下架。"}</p>
            <Link className="secondary-button" href="/echoes">
              返回声骸图鉴
            </Link>
          </div>
        </article>
      </CommunityPageFrame>
    );
  return (
    <CommunityPageFrame>
      <article className="detail-page">
        <Link className="back-link" href="/echoes">
          <ArrowLeft size={15} />
          返回声骸图鉴
        </Link>
        <div className="detail-heading">
          <span className="guide-type">{echo.role}向套装</span>
          <h1>{echo.name}</h1>
          <p>{echo.description}</p>
        </div>
        <section className="entity-note">
          <h2>套装信息</h2>
          <p>
            <strong>两件套效果：</strong>
            {echo.effect}
          </p>
          <p>
            <strong>推荐掉落：</strong>
            {echo.location}
          </p>
          {/* guideSlug 为空时干脆不渲染按钮——渲染一个必然打不开的攻略链接
              比没有按钮更糟（mock 时代的 guideId 全是假 slug） */}
          {echo.guideSlug ? (
            <Link className="primary-button" href={"/guides/" + echo.guideSlug}>
              查看适配攻略
            </Link>
          ) : null}
        </section>
      </article>
    </CommunityPageFrame>
  );
}

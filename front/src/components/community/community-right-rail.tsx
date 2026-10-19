"use client";
import Image from "next/image";
import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { fetchPosts } from "@/lib/api";
import { useNewsListQuery } from "@/lib/community-queries";
const quickTools = [
  { title: "养成计算器", href: "/tools/calculator", iconSrc: "/icons/upgrade-calculator.webp" },
  { title: "声骸图鉴", href: "/echoes", iconSrc: "/icons/echo-archive.webp" },
  { title: "配队模拟", href: "/tools/team-builder", iconSrc: "/icons/team-builder.webp" },
  { title: "角色图鉴", href: "/characters", iconSrc: "/icons/character-archive.webp" },
  { title: "版本资讯", href: "/news", iconSrc: "/icons/version-news.webp" },
  { title: "新手指南", href: "/guides?category=新手攻略", iconSrc: "/icons/exploration-map.webp" },
];
export function RightRail() {
  const [tab, setTab] = useState<"recommend" | "news">("recommend");
  // 资讯 tab 接真 API（H-9）：右栏是 newsItems 的第三处消费点，前两处
  // （/news 列表、资讯详情）改了之后漏了它，表现为过滤用例里旧标题仍在 DOM 里
  const newsRailQuery = useNewsListQuery("全部");
  const items = (newsRailQuery.data?.items ?? []).slice(0, 5);
  // 热门话题：聚合真实帖子标签（库街区右栏话题卡同款：缩略图 + 帖子数）
  const hotTopics = useQuery({
    queryKey: ["hot-topics"],
    queryFn: async () => {
      const posts = await fetchPosts({ sort: "hot", pageSize: 40 });
      const byTag = new Map<string, { count: number; cover?: string }>();
      for (const post of posts) {
        const cover = post.coverImageUrl ?? post.mediaUrls?.[0];
        for (const tag of post.tags ?? []) {
          const entry = byTag.get(tag) ?? { count: 0, cover: undefined };
          entry.count += 1;
          if (!entry.cover && cover) entry.cover = cover;
          byTag.set(tag, entry);
        }
      }
      return [...byTag.entries()]
        .sort((left, right) => right[1].count - left[1].count)
        .slice(0, 5)
        .map(([tag, value]) => ({ tag, ...value }));
    },
    staleTime: 120_000,
    retry: false,
  });
  return (
    <aside className="right-rail" aria-label="推荐与工具">
      <section className="right-panel" id="news">
        <div className="panel-tabs">
          <button className={tab === "recommend" ? "is-active" : ""} onClick={() => setTab("recommend")} type="button">
            推荐
          </button>
          <button className={tab === "news" ? "is-active" : ""} onClick={() => setTab("news")} type="button">
            资讯
          </button>
        </div>
        <div className="ranking-list">
          {items.map((item) => {
            const chip =
              item.category === "官方公告"
                ? { kind: "official", label: "官方" }
                : item.category === "活动资讯"
                  ? { kind: "activity", label: "活动" }
                  : { kind: "info", label: "资讯" };
            return (
              <Link className="ranking-item" href={"/news/" + item.slug} key={item.slug}>
                <span className={"news-chip news-chip--" + chip.kind}>{chip.label}</span>
                <span className="ranking-title">{item.title}</span>
              </Link>
            );
          })}
        </div>
      </section>
      <section aria-label="快捷工具" className="right-panel tools-panel" id="tools">
        <div className="panel-title">
          <h2>实用工具</h2>
          <Link href="/tools">
            更多 <ChevronRight size={14} />
          </Link>
        </div>
        <ul aria-label="工具列表" className="tool-grid">
          {quickTools.map(({ title, href, iconSrc }) => (
            <li key={title}>
              <Link className="tool-grid-item" href={href}>
                <span className="tool-icon">
                  <Image alt="" fill sizes="36px" src={iconSrc} />
                </span>
                <strong>{title}</strong>
              </Link>
            </li>
          ))}
        </ul>
      </section>
      {hotTopics.data && hotTopics.data.length > 0 && (
        <section aria-label="热门话题" className="right-panel topics-panel">
          <div className="panel-title">
            <h2>热门话题</h2>
          </div>
          <div className="topic-list">
            {hotTopics.data.map(({ tag, count, cover }) => (
              <Link className="topic-card" href={"/search?q=" + encodeURIComponent(tag)} key={tag}>
                <span className="topic-thumb">
                  <Image alt="" fill sizes="44px" src={cover ?? "/art/guide-coast.webp"} />
                </span>
                <span className="topic-copy">
                  <strong>#{tag}</strong>
                  <small>{count} 篇帖子</small>
                </span>
              </Link>
            ))}
          </div>
        </section>
      )}
    </aside>
  );
}

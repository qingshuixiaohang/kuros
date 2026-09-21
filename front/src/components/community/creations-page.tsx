"use client";
import Image from "next/image";
import Link from "next/link";
import { ChevronRight, PenLine } from "lucide-react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { characters } from "@/lib/mock";

export function CreationsPage() {
  return (
    <CommunityPageFrame activeNav="creations">
      <PageHeader
        section="同人创作"
        title="同人创作"
        description="收录绘画、摄影、剪辑和世界观相关的玩家创作。"
        action={<Link className="primary-button" href="/publish?type=creation"><PenLine size={16} />发布创作</Link>}
      />
      <section className="creation-list">
        {characters.map((item, index) => (
          <Link className="creation-card" href={"/characters/" + item.id} key={item.id}>
            <div className="creation-image">
              <Image alt={item.name + "创作预览"} fill sizes="(max-width: 620px) 50vw, 420px" src={item.image} />
            </div>
            <div>
              <span>玩家创作 · {index === 1 ? "摄影" : "绘画"}</span>
              <h2>{item.name} · 潮声片段</h2>
              <p>以原创角色意象为灵感的社区作品展示。</p>
              <small>查看作品 <ChevronRight size={13} /></small>
            </div>
          </Link>
        ))}
      </section>
    </CommunityPageFrame>
  );
}

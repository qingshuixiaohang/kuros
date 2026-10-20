"use client";
import Image from "next/image";
import Link from "next/link";
import { ChevronRight, PenLine } from "lucide-react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { useCommunityPostQuery } from "@/lib/community-queries";
import { formatPublishedAt, toGuide } from "@/lib/post-view";

export function CreationsPage() {
  const creationsQuery = useCommunityPostQuery({ category: "同人", page: 1, pageSize: 20, sort: "latest", retry: false });
  const creations = creationsQuery.data?.items.map((post) => ({ post, view: toGuide(post) })) ?? [];

  return (
    <CommunityPageFrame activeNav="creations">
      <PageHeader
        section="同人创作"
        title="同人创作"
        description="收录绘画、摄影、剪辑和世界观相关的玩家创作。"
        action={
          <Link className="primary-button" href="/publish?type=creation">
            <PenLine size={16} />
            发布创作
          </Link>
        }
      />
      <section className="creation-list">
        {creationsQuery.isPending ? (
          <p className="admin-empty" role="status">
            正在加载同人创作…
          </p>
        ) : null}
        {creationsQuery.isError ? (
          <p className="admin-empty" role="alert">
            同人创作加载失败，请检查后端服务。
          </p>
        ) : null}
        {!creationsQuery.isPending && !creationsQuery.isError && creations.length === 0 ? (
          <p className="admin-empty" role="status">
            暂时还没有同人创作。
          </p>
        ) : null}
        {creations.map(({ post, view }, index) => (
          <Link className="creation-card" href={"/guides/" + post.id} key={post.id}>
            {view.mediaUrls?.[0] ? (
              <div className="creation-image">
                <Image
                  alt={post.title + "创作预览"}
                  fill
                  loading={index === 0 ? "eager" : "lazy"}
                  sizes="(max-width: 620px) 50vw, 420px"
                  src={view.mediaUrls[0]}
                />
              </div>
            ) : null}
            <div>
              <span>玩家创作 · {post.category}</span>
              <h2>{post.title}</h2>
              <p>{post.excerpt}</p>
              <small>
                {post.author.nickname} · {formatPublishedAt(post.publishedAt)} · 查看作品 <ChevronRight size={13} />
              </small>
            </div>
          </Link>
        ))}
      </section>
    </CommunityPageFrame>
  );
}

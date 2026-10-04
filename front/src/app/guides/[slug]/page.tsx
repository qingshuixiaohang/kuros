import { GuidePostDetailPage } from "@/components/community/community-post-detail";

export function generateStaticParams() {
  return [
    { slug: "changli-team" },
    { slug: "tower-24" },
    { slug: "camellya-echo" },
    { slug: "new-player-route" },
  ];
}

export default async function Page({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  // key=slug：切换帖子时组件重挂载，加载/错误状态由惰性初始值按新 slug 重建，
  // 避免 effect 里同步 setState（react-hooks/set-state-in-effect）
  return <GuidePostDetailPage key={slug} slug={slug} />;
}
export const dynamic = "force-dynamic";

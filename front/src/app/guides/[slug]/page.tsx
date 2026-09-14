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
  return <GuidePostDetailPage slug={slug} />;
}
export const dynamic = "force-dynamic";

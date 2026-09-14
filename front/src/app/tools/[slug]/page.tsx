import { ToolDetailPage } from "@/components/community/community-pages";

export default async function Page({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  return <ToolDetailPage slug={slug} />;
}
export const dynamic = "force-dynamic";

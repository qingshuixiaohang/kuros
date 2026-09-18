import { CharacterDetailPage } from "@/components/community/characters-page";

export default async function Page({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  return <CharacterDetailPage slug={slug} />;
}
export const dynamic = "force-dynamic";

import { CharacterDetailPage } from "@/components/community/community-character-pages";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <CharacterDetailPage slug={id} />;
}
export const dynamic = "force-dynamic";

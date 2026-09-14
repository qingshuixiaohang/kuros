import { CharacterDetailPage } from "@/components/community/community-pages";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <CharacterDetailPage id={id} />;
}
export const dynamic = "force-dynamic";

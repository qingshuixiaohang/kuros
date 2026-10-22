import { useInfiniteQuery } from "@tanstack/react-query";
import { fetchFollowingFeedByCursor, fetchPosts } from "@/lib/api";

export function useCommunityFeedQuery(options: { tab: "recommend" | "latest" | "following"; keyword: string; viewerId: string; enabled: boolean }) {
  const { tab, keyword, viewerId, enabled } = options;
  return useInfiniteQuery({
    queryKey: ["community-feed", viewerId, tab, keyword],
    queryFn: async ({ pageParam }) => {
      if (tab === "following") return fetchFollowingFeedByCursor({ cursor: pageParam, limit: 20 });
      const items = await fetchPosts({ keyword, sort: tab === "recommend" ? "hot" : "latest", pageSize: 20 });
      return { items, nextCursor: null, hasMore: false };
    },
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.nextCursor : null),
    select: (data) => {
      const seen = new Set<string>();
      const items = data.pages
        .flatMap((page) => page.items)
        .filter((post) => {
          if (seen.has(post.id)) return false;
          seen.add(post.id);
          return true;
        });
      return { ...data, items };
    },
    enabled,
    retry: false,
  });
}

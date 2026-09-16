import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { fetchPostPage } from "@/lib/api";

export function useCommunityPostQuery(options: { category?: string; keyword?: string; tag?: string; page?: number; pageSize?: number; sort?: "latest" | "hot" }) {
  const { category = "", keyword = "", tag = "", page = 1, pageSize = 20, sort = "latest" } = options;
  return useQuery({
    queryKey: ["community-posts", category, keyword, tag, page, pageSize, sort],
    queryFn: () => fetchPostPage({ category: category || undefined, keyword: keyword || undefined, tag: tag || undefined, page, pageSize, sort }),
    placeholderData: keepPreviousData,
  });
}

"use client";

import type { ReactNode } from "react";
import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { CommunityDemoProvider } from "@/components/community/community-interactions";

export function CommunityProvider({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false } } }));
  return <QueryClientProvider client={queryClient}><CommunityDemoProvider>{children}</CommunityDemoProvider></QueryClientProvider>;
}

"use client";

import type { ReactNode } from "react";

import { CommunityDemoProvider } from "@/components/community/community-interactions";

export function CommunityProvider({ children }: { children: ReactNode }) {
  return <CommunityDemoProvider>{children}</CommunityDemoProvider>;
}

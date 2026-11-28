"use client";

import { createContext, useContext, useEffect, useRef, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { fetchLikeOperation, submitLikeOperation } from "@/lib/api";
import { LikeCoordinator } from "@/lib/like-coordinator";
import { likeKey, type LikeOperation, type LikeValue } from "@/lib/like-state";

type Manager = { userId: string; coordinator: LikeCoordinator };
const Context = createContext<React.RefObject<Manager | null> | null>(null);

export function LikeOperationsProvider({ children }: { children: ReactNode }) {
  const { user, notify } = useCommunityDemo();
  const client = useQueryClient();
  const manager = useRef<Manager | null>(null);
  const userId = user?.id;
  useEffect(() => {
    if (!userId) return;
    const coordinator = new LikeCoordinator(
      client,
      userId,
      { submit: submitLikeOperation, query: fetchLikeOperation },
      { getItem: (key) => window.localStorage.getItem(key), setItem: (key, value) => window.localStorage.setItem(key, value) },
      undefined,
      () => notify("点赞未受理，请稍后重试"),
    );
    manager.current = { userId, coordinator };
    coordinator.restore();
    let lastResume = 0;
    const resume = () => {
      if (Date.now() - lastResume < 5000) return;
      lastResume = Date.now();
      coordinator.resume();
    };
    window.addEventListener("focus", resume);
    window.addEventListener("pointerdown", resume);
    return () => {
      coordinator.stop();
      if (manager.current?.coordinator === coordinator) manager.current = null;
      window.removeEventListener("focus", resume);
      window.removeEventListener("pointerdown", resume);
    };
  }, [client, userId, notify]);
  return <Context.Provider value={manager}>{children}</Context.Provider>;
}

export function useSharedLike(postId: string, fallback: LikeValue) {
  const { user } = useCommunityDemo();
  const manager = useContext(Context);
  if (!manager) throw new Error("useSharedLike must be used inside LikeOperationsProvider");
  const userId = user?.id ?? "guest";
  const { data } = useQuery<LikeOperation | null>({
    queryKey: likeKey(userId, postId),
    queryFn: () => null,
    enabled: false,
    initialData: null,
    gcTime: Infinity,
  });
  return {
    ...(data?.value ?? fallback),
    pending: data?.pending ?? false,
    start: () => {
      if (manager.current?.userId !== userId || !user || !postId) return false;
      return manager.current.coordinator.start(postId, data?.value ?? fallback);
    },
  };
}

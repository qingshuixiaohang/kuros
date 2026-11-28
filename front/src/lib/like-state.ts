import type { QueryClient } from "@tanstack/react-query";

export type LikeValue = { liked: boolean; likeCount: number };
export type LikeOperation = { operationId: string; value: LikeValue; previous: LikeValue; pending: boolean };
export const likeKey = (userId: string, postId: string) => ["like-operation", userId, postId] as const;

export function beginLike(client: QueryClient, userId: string, postId: string, current: LikeValue, operationId: string) {
  const key = likeKey(userId, postId);
  if (client.getQueryData<LikeOperation | null>(key)?.pending) return false;
  const value = { liked: !current.liked, likeCount: Math.max(0, current.likeCount + (current.liked ? -1 : 1)) };
  client.setQueryData<LikeOperation>(key, { operationId, value, previous: current, pending: true });
  return true;
}

export function readLike(client: QueryClient, userId: string, postId: string, fallback: LikeValue) {
  const operation = client.getQueryData<LikeOperation | null>(likeKey(userId, postId));
  return { ...(operation?.value ?? fallback), pending: operation?.pending ?? false };
}

export function confirmLike(client: QueryClient, userId: string, postId: string, operationId: string, value: LikeValue) {
  const key = likeKey(userId, postId);
  const operation = client.getQueryData<LikeOperation | null>(key);
  if (operation?.pending && operation.operationId === operationId) {
    client.setQueryData<LikeOperation>(key, { ...operation, value, pending: false });
  }
}

import { describe, expect, it } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { beginLike, readLike, confirmLike } from "../like-state";

describe("shared like state", () => {
  it("immediately overlays the selected state and count across readers", () => {
    const client = new QueryClient();
    beginLike(client, "user-a", "post-a", { liked: false, likeCount: 5 }, "op-1");
    expect(readLike(client, "user-a", "post-a", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 6, pending: true });
  });
  it("isolates users and prevents a second operation until a terminal result", () => {
    const client = new QueryClient();
    expect(beginLike(client, "a", "p", { liked: false, likeCount: 5 }, "one")).toBe(true);
    expect(beginLike(client, "a", "p", { liked: true, likeCount: 6 }, "two")).toBe(false);
    expect(readLike(client, "b", "p", { liked: false, likeCount: 5 })).toEqual({ liked: false, likeCount: 5, pending: false });
  });
  it("keeps the authoritative result over stale ordinary reads", () => {
    const client = new QueryClient();
    beginLike(client, "a", "p", { liked: false, likeCount: 5 }, "one");
    confirmLike(client, "a", "p", "one", { liked: true, likeCount: 9 });
    expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 9, pending: false });
  });
});

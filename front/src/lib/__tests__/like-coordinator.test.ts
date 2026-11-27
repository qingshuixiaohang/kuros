import { describe, expect, it, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { LikeCoordinator } from "../like-coordinator";
import { readLike } from "../like-state";

describe("like confirmation", () => {
  it("keeps an accepted operation until MySQL confirms it", async () => {
    const client = new QueryClient();
    const api = {
      submit: vi.fn().mockResolvedValue(undefined),
      query: vi.fn().mockResolvedValue({ status: "APPLIED", state: { postId: "p", liked: true, likeCount: 7, favoriteCount: 0, favorited: false } }),
    };
    const storage = { getItem: () => null, setItem: () => {} };
    const coordinator = new LikeCoordinator(client, "a", api, storage, []);
    coordinator.start("p", { liked: false, likeCount: 5 }, "op");
    expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 6, pending: true });
    await coordinator.wait("p");
    expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 7, pending: false });
    expect(api.submit).toHaveBeenCalledWith("op", "p", true, expect.any(AbortSignal));
  });
});

it("keeps an unknown delivery pending when a later retry is rejected", async () => {
  const client = new QueryClient();
  const { ApiError } = await import("../api");
  const api = {
    submit: vi.fn().mockRejectedValueOnce(new Error("timeout")).mockRejectedValueOnce(new ApiError(503, "INTERACTION_NOT_ACCEPTED")),
    query: vi.fn().mockResolvedValue({ status: "UNKNOWN" }),
  };
  const coordinator = new LikeCoordinator(client, "a", api, { getItem: () => null, setItem: () => {} }, []);
  coordinator.start("p", { liked: false, likeCount: 5 }, "op");
  await coordinator.wait("p");
  coordinator.resume();
  await coordinator.wait("p");
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 }).pending).toBe(true);
  expect(api.submit.mock.calls.map((call) => call[0])).toEqual(["op", "op"]);
});

it("restores an accepted pending operation after refresh and does not submit again", async () => {
  const memory = new Map<string, string>();
  const storage = {
    getItem: (key: string) => memory.get(key) ?? null,
    setItem: (key: string, value: string) => {
      memory.set(key, value);
    },
  };
  const api = { submit: vi.fn().mockResolvedValue(undefined), query: vi.fn().mockResolvedValue({ status: "UNKNOWN" }) };
  const first = new LikeCoordinator(new QueryClient(), "a", api, storage, []);
  first.start("p", { liked: false, likeCount: 5 }, "op");
  await first.wait("p");
  first.stop();
  const client = new QueryClient();
  const second = new LikeCoordinator(client, "a", api, storage, []);
  second.restore();
  await second.wait("p");
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 6, pending: true });
  expect(api.submit).toHaveBeenCalledTimes(1);
  const other = new QueryClient();
  new LikeCoordinator(other, "b", api, storage, []).restore();
  expect(readLike(other, "b", "p", { liked: false, likeCount: 5 }).pending).toBe(false);
});

it("rolls back an explicitly rejected first submission", async () => {
  const { ApiError } = await import("../api");
  const client = new QueryClient();
  const api = { submit: vi.fn().mockRejectedValue(new ApiError(503, "INTERACTION_NOT_ACCEPTED")), query: vi.fn() };
  const coordinator = new LikeCoordinator(client, "a", api, { getItem: () => null, setItem: () => {} }, []);
  coordinator.start("p", { liked: false, likeCount: 5 }, "op");
  await coordinator.wait("p");
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: false, likeCount: 5, pending: false });
  expect(api.query).not.toHaveBeenCalled();
});

it("ignores an in-flight stale read after an operation begins", async () => {
  const client = new QueryClient();
  let finish!: (value: { liked: boolean; likeCount: number }) => void;
  const old = client
    .fetchQuery({
      queryKey: ["post-interactions", "p", "a"],
      queryFn: () =>
        new Promise<{ liked: boolean; likeCount: number }>((resolve) => {
          finish = resolve;
        }),
    })
    .catch(() => undefined);
  const api = { submit: vi.fn().mockResolvedValue(undefined), query: vi.fn().mockResolvedValue({ status: "UNKNOWN" }) };
  const coordinator = new LikeCoordinator(client, "a", api, { getItem: () => null, setItem: () => {} }, []);
  coordinator.start("p", { liked: false, likeCount: 5 }, "op");
  finish({ liked: false, likeCount: 5 });
  await old;
  await coordinator.wait("p");
  expect(client.getQueryData(["post-interactions", "p", "a"])).toBeUndefined();
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 6, pending: true });
});

it("rechecks an already confirmed result without accepting a stale Redis value", async () => {
  const client = new QueryClient();
  const api = {
    submit: vi.fn().mockResolvedValue(undefined),
    query: vi
      .fn()
      .mockResolvedValueOnce({ status: "APPLIED", state: { postId: "p", liked: true, likeCount: 6 } })
      .mockResolvedValueOnce({ status: "APPLIED", state: { postId: "p", liked: true, likeCount: 8 } }),
  };
  const coordinator = new LikeCoordinator(client, "a", api, { getItem: () => null, setItem: () => {} }, []);
  coordinator.start("p", { liked: false, likeCount: 5 }, "op");
  await coordinator.wait("p");
  coordinator.resume();
  await coordinator.wait("p");
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 8, pending: false });
});

it("does not send an operation whose ID cannot be saved for recovery", async () => {
  const client = new QueryClient();
  const api = { submit: vi.fn(), query: vi.fn() };
  const coordinator = new LikeCoordinator(
    client,
    "a",
    api,
    {
      getItem: () => null,
      setItem: () => {
        throw new Error("quota");
      },
    },
    [],
  );
  expect(coordinator.start("p", { liked: false, likeCount: 5 }, "op")).toBe(false);
  expect(api.submit).not.toHaveBeenCalled();
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: false, likeCount: 5, pending: false });
});

it("continues reconciling the prior confirmed operation after a later rejection", async () => {
  const { ApiError } = await import("../api");
  const client = new QueryClient();
  const api = {
    submit: vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new ApiError(503, "INTERACTION_NOT_ACCEPTED")),
    query: vi
      .fn()
      .mockResolvedValueOnce({ status: "APPLIED", state: { postId: "p", liked: true, likeCount: 6 } })
      .mockResolvedValueOnce({ status: "APPLIED", state: { postId: "p", liked: true, likeCount: 8 } }),
  };
  const coordinator = new LikeCoordinator(client, "a", api, { getItem: () => null, setItem: () => {} }, []);
  coordinator.start("p", { liked: false, likeCount: 5 }, "one");
  await coordinator.wait("p");
  coordinator.start("p", { liked: true, likeCount: 6 }, "two");
  await coordinator.wait("p");
  coordinator.resume();
  await coordinator.wait("p");
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 8, pending: false });
});

it("ignores an old account response after its coordinator stops", async () => {
  const client = new QueryClient();
  let resolve!: (value: { status: "APPLIED"; state: { postId: string; liked: boolean; likeCount: number } }) => void;
  const api = {
    submit: vi.fn().mockResolvedValue(undefined),
    query: vi.fn().mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    ),
  };
  const coordinator = new LikeCoordinator(client, "a", api, { getItem: () => null, setItem: () => {} }, []);
  coordinator.start("p", { liked: false, likeCount: 5 }, "op");
  await vi.waitFor(() => expect(api.query).toHaveBeenCalled());
  coordinator.stop();
  resolve({ status: "APPLIED", state: { postId: "p", liked: true, likeCount: 9 } });
  await coordinator.wait("p");
  expect(readLike(client, "a", "p", { liked: false, likeCount: 5 })).toEqual({ liked: true, likeCount: 6, pending: true });
  expect(readLike(client, "b", "p", { liked: false, likeCount: 5 }).pending).toBe(false);
});

import type { QueryClient } from "@tanstack/react-query";
import { ApiError, type LikeOperationResult } from "./api";
import { beginLike, confirmLike, likeKey, type LikeOperation, type LikeValue } from "./like-state";

type Transport = {
  submit: (id: string, postId: string, liked: boolean, signal: AbortSignal) => Promise<unknown>;
  query: (id: string, signal: AbortSignal) => Promise<LikeOperationResult>;
};
type Saved = { postId: string; operation: LikeOperation; accepted: boolean; ambiguous: boolean; prior?: LikeOperation };
type StorageLike = Pick<Storage, "getItem" | "setItem">;

export class LikeCoordinator {
  private readonly key: string;
  private readonly records = new Map<string, Saved>();
  private readonly jobs = new Map<string, Promise<void>>();
  private readonly abort = new AbortController();

  constructor(
    private client: QueryClient,
    private userId: string,
    private api: Transport,
    private storage: StorageLike,
    private delays = [500, 1500, 3000],
    private onRejected: () => void = () => {},
  ) {
    this.key = `kuros-like-operations-v1:${userId}`;
  }

  restore() {
    try {
      const data: unknown = JSON.parse(this.storage.getItem(this.key) ?? "[]");
      if (!Array.isArray(data)) return;
      for (const item of data) {
        if (
          !item ||
          typeof item.postId !== "string" ||
          typeof item.accepted !== "boolean" ||
          typeof item.ambiguous !== "boolean" ||
          !item.operation ||
          typeof item.operation.operationId !== "string" ||
          typeof item.operation.pending !== "boolean" ||
          typeof item.operation.value?.liked !== "boolean" ||
          typeof item.operation.value?.likeCount !== "number" ||
          typeof item.operation.previous?.liked !== "boolean" ||
          typeof item.operation.previous?.likeCount !== "number"
        )
          continue;
        const saved = item as Saved;
        this.records.set(saved.postId, saved);
        this.client.setQueryData(likeKey(this.userId, saved.postId), saved.operation);
      }
    } catch {
      /* Invalid or unavailable storage cannot restore previous operations. */
    }
    this.resume();
  }

  start(postId: string, value: LikeValue, id = crypto.randomUUID()) {
    if (this.abort.signal.aborted || !beginLike(this.client, this.userId, postId, value, id)) return false;
    const operation = this.client.getQueryData<LikeOperation>(likeKey(this.userId, postId))!;
    const previous = this.records.get(postId);
    this.records.set(postId, { postId, operation, accepted: false, ambiguous: false, prior: previous?.accepted ? previous.operation : undefined });
    if (!this.save()) {
      if (previous) this.records.set(postId, previous);
      else this.records.delete(postId);
      this.client.setQueryData(likeKey(this.userId, postId), previous?.operation ?? null);
      return false;
    }
    void this.client.cancelQueries({
      predicate: (query) =>
        ["post-interactions", "feed-interaction-states", "community-feed", "community-posts", "search-posts"].includes(String(query.queryKey[0])),
    });
    const previousJob = this.jobs.get(postId);
    if (previousJob) void previousJob.then(() => this.run(postId));
    else this.run(postId);
    return true;
  }

  resume() {
    for (const [postId, record] of this.records) if (record.operation.pending || record.accepted) this.run(postId);
  }

  wait(postId: string) {
    return this.jobs.get(postId) ?? Promise.resolve();
  }
  stop() {
    this.abort.abort();
  }

  private save() {
    try {
      this.storage.setItem(this.key, JSON.stringify([...this.records.values()]));
      return true;
    } catch {
      return false;
    }
  }

  private run(postId: string) {
    if (this.abort.signal.aborted || this.jobs.has(postId)) return;
    const job = this.execute(postId).finally(() => this.jobs.delete(postId));
    this.jobs.set(postId, job);
  }

  private reject(postId: string, saved: Saved) {
    const prior = saved.prior;
    if (
      prior &&
      typeof prior.operationId === "string" &&
      prior.pending === false &&
      typeof prior.value?.liked === "boolean" &&
      typeof prior.value?.likeCount === "number"
    ) {
      this.records.set(postId, { postId, operation: prior, accepted: true, ambiguous: true });
      this.client.setQueryData(likeKey(this.userId, postId), prior);
    } else {
      this.records.delete(postId);
      this.client.setQueryData(likeKey(this.userId, postId), null);
    }
    this.save();
    this.onRejected();
  }

  private async execute(postId: string) {
    const saved = this.records.get(postId);
    if (!saved) return;
    if (!saved.operation.pending) {
      if (!saved.accepted) return;
      try {
        const id = saved.operation.operationId;
        const result = await this.api.query(id, AbortSignal.any([this.abort.signal, AbortSignal.timeout(8000)]));
        const current = this.client.getQueryData<LikeOperation>(likeKey(this.userId, postId));
        if (
          !this.abort.signal.aborted &&
          current &&
          !current.pending &&
          current.operationId === id &&
          result.status === "APPLIED" &&
          result.state?.postId === postId &&
          typeof result.state.liked === "boolean" &&
          Number.isSafeInteger(result.state.likeCount) &&
          result.state.likeCount >= 0
        ) {
          saved.operation = { ...current, value: { liked: result.state.liked, likeCount: result.state.likeCount } };
          this.client.setQueryData(likeKey(this.userId, postId), saved.operation);
          this.save();
        }
      } catch {
        /* A later interaction will retry confirmation. */
      }
      return;
    }
    const { operation } = saved;
    const id = operation.operationId;
    const attempts = Math.max(1, this.delays.length);
    for (let attempt = 0; attempt < attempts && !this.abort.signal.aborted; attempt++) {
      if (!saved.accepted) {
        const wasAmbiguous = saved.ambiguous;
        saved.ambiguous = true;
        if (!this.save()) {
          saved.ambiguous = wasAmbiguous;
          return;
        }
        try {
          await this.api.submit(id, postId, operation.value.liked, AbortSignal.any([this.abort.signal, AbortSignal.timeout(8000)]));
          if (this.abort.signal.aborted) return;
          saved.accepted = true;
          this.save();
        } catch (error) {
          if (this.abort.signal.aborted) return;
          if (
            !wasAmbiguous &&
            error instanceof ApiError &&
            (error.code === "INTERACTION_NOT_ACCEPTED" || (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429))
          ) {
            this.reject(postId, saved);
            return;
          }
        }
      }
      try {
        const result = await this.api.query(id, AbortSignal.any([this.abort.signal, AbortSignal.timeout(8000)]));
        if (this.abort.signal.aborted) return;
        if (
          result.status === "APPLIED" &&
          result.state &&
          result.state.postId === postId &&
          typeof result.state.liked === "boolean" &&
          Number.isSafeInteger(result.state.likeCount) &&
          result.state.likeCount >= 0
        ) {
          confirmLike(this.client, this.userId, postId, id, { liked: result.state.liked, likeCount: result.state.likeCount });
          saved.operation = this.client.getQueryData<LikeOperation>(likeKey(this.userId, postId))!;
          this.save();
          void this.client.invalidateQueries({ queryKey: ["post-interactions", postId] });
          return;
        }
      } catch {
        if (this.abort.signal.aborted) return;
      }
      if (attempt < attempts - 1)
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, this.delays[attempt]);
          this.abort.signal.addEventListener(
            "abort",
            () => {
              clearTimeout(timer);
              resolve();
            },
            { once: true },
          );
        });
    }
  }
}

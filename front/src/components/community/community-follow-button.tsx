"use client";

import { useEffect, useState } from "react";
import { fetchUserFollow, followUser, unfollowUser, type UserFollow } from "@/lib/api";
import { useCommunityDemo } from "@/components/community/community-interactions";

export function CommunityFollowButton({ targetUserId, fallbackKey, className = "" }: { targetUserId?: string; fallbackKey: string; className?: string }) {
  const { loggedIn, followed, requestLogin, toggleFollow, notify } = useCommunityDemo();
  const [follow, setFollow] = useState<UserFollow | null>(null);

  useEffect(() => {
    if (!loggedIn || !targetUserId) return;
    let active = true;
    fetchUserFollow(targetUserId).then((result) => { if (active) setFollow(result); }).catch(() => { /* Keep the local fallback when the API is unavailable. */ });
    return () => { active = false; };
  }, [loggedIn, targetUserId]);

  const isFollowed = loggedIn && targetUserId ? follow?.followed === true : followed.includes(fallbackKey);
  function toggle() {
    requestLogin(() => {
      if (!targetUserId) { toggleFollow(fallbackKey); return; }
      void (isFollowed ? unfollowUser(targetUserId) : followUser(targetUserId))
        .then((result) => { setFollow(result); toggleFollow(fallbackKey); })
        .catch((error) => notify(error instanceof Error ? error.message : "关注操作失败，请稍后重试"));
    });
  }

  return <button className={`${className} ${isFollowed ? "is-followed" : ""}`.trim()} onClick={toggle} type="button">{isFollowed ? "已关注" : "＋关注"}</button>;
}

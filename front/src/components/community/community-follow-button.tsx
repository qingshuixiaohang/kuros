"use client";

import { useUserFollowQuery, useFollowUserMutation } from "@/lib/community-queries";
import { useCommunityDemo } from "@/components/community/community-interactions";

export function CommunityFollowButton({ targetUserId, fallbackKey, className = "" }: { targetUserId?: string; fallbackKey: string; className?: string }) {
  const { loggedIn, followed, requestLogin, toggleFollow, notify } = useCommunityDemo();
  // 关注状态读走 react-query：写成功后 invalidate，页面上指向同一用户的
  // 所有按钮实例一起重取（原来 setFollow(result) 只更新当前这一个实例）
  const followQuery = useUserFollowQuery(targetUserId, loggedIn);
  const followMutation = useFollowUserMutation(targetUserId);

  const isFollowed = loggedIn && targetUserId ? followQuery.data === true : followed.includes(fallbackKey);

  function toggle() {
    requestLogin(() => {
      if (!targetUserId) {
        toggleFollow(fallbackKey);
        return;
      }
      void followMutation
        .mutateAsync(isFollowed ? "unfollow" : "follow")
        .catch((error) => notify(error instanceof Error ? error.message : "关注操作失败，请稍后重试"));
    });
  }

  return (
    <button className={`${className} ${isFollowed ? "is-followed" : ""}`.trim()} onClick={toggle} type="button">
      {isFollowed ? "已关注" : "＋关注"}
    </button>
  );
}

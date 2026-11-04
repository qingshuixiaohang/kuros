import Image from "next/image";
import { communityAvatar } from "@/lib/official-art";

export function CommunityAvatar({
  avatarUrl,
  identity,
  className,
  size = 40,
}: {
  avatarUrl?: string | null;
  identity: string;
  className: string;
  size?: number;
}) {
  return (
    <span aria-label={`${identity}头像`} className={`community-avatar ${className}`} role="img">
      <Image alt="" fill sizes={`${size}px`} src={communityAvatar(avatarUrl, identity)} />
    </span>
  );
}

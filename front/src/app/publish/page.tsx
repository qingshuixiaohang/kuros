import { PublishPage } from "@/components/community/community-pages";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "发布帖子 | 鸣潮 · 漂泊者社区",
  description: "发布攻略、心得、同人和问题，记录你的鸣潮旅程。",
};

export default function Page() {
  return <PublishPage />;
}
export const dynamic = "force-dynamic";

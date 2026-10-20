"use client";
import { CommunityFeed } from "@/components/community/community-feed";
import { RightRail } from "@/components/community/community-right-rail";
import { CommunityChannelDrawer, Sidebar, TopNavigation } from "@/components/community/community-navigation";
import { useState } from "react";

function HomeContent() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [query, setQuery] = useState("");
  return (
    <main className="app-shell">
      <TopNavigation query={query} onQueryChange={setQuery} onMenu={() => setDrawerOpen(true)} />
      <CommunityChannelDrawer open={drawerOpen} setOpen={setDrawerOpen} />
      <div className="page-grid">
        <Sidebar />
        <div className="main-column">
          <CommunityFeed query={query} />
        </div>
        <RightRail />
      </div>
    </main>
  );
}
// 登录态 Provider 只在 layout 层挂载一份（与 CommunityPageFrame 同理）：
// 首页再嵌套一份会让"首页登录"不为主持栏之外的其他页面感知，且双实例共写
// 同一 localStorage 键互相覆盖——会话恢复也会重复请求 /auth/me。
export function CommunityHome() {
  return <HomeContent />;
}

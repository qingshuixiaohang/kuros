"use client";
import { CommunityFeed } from "@/components/community/community-feed";
import { RightRail } from "@/components/community/community-right-rail";
import { Sidebar, TopNavigation, useCommunityDrawer } from "@/components/community/community-navigation";
import { X } from "lucide-react";
import { useState } from "react";

function HomeContent() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [query, setQuery] = useState("");
  const { drawerCloseRef, drawerRef } = useCommunityDrawer(drawerOpen, setDrawerOpen);
  return (
    <main className="app-shell">
      <TopNavigation query={query} onQueryChange={setQuery} onMenu={() => setDrawerOpen(true)} />
      {drawerOpen && (
        <div className="drawer-backdrop" onClick={() => setDrawerOpen(false)}>
          <div aria-label="社区频道" aria-modal="true" className="mobile-drawer" onClick={(event) => event.stopPropagation()} ref={drawerRef} role="dialog">
            <div className="drawer-header">
              <span>频道</span>
              <button onClick={() => setDrawerOpen(false)} ref={drawerCloseRef} type="button" aria-label="关闭导航">
                <X size={20} />
              </button>
            </div>
            <Sidebar drawer />
          </div>
        </div>
      )}
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

import type { Page } from "@playwright/test";

/**
 * 详情页 mock（ui-regression 的三个失败用例共用）。
 *
 * 这三个用例此前**没有 mock 后端**，直接打 http://localhost:8080。
 * 后端不在运行时它们全部超时失败——表现出来的症状是"测试挂了"，
 * 但根因是环境缺失，不是代码缺陷。这类失败最有害：它让整套 e2e
 * 长期带着 3 个红灯，反而掩盖真正的回归。
 *
 * 修法不是"跳过它们"，是让它们自给自足：mock 掉帖子详情、评论列表、
 * 登录态和 CSRF，这样后端跑不跑都不影响结果。
 */

export const DETAIL_POST_ID = "10000000-0000-0000-0000-000000000001";

const detailPost = {
  id: DETAIL_POST_ID,
  type: "GUIDE",
  category: "配队攻略",
  title: "长离焚火队：从零到毕业的配队思路",
  excerpt: "围绕共鸣效率、轮切节奏与副 C 选择，整理一套能直接照着练的实战框架。",
  content:
    "# 详情接口封面测试\n\n用于验证帖子详情优先使用后端返回的媒体。\n\n## 一、先确定队伍节奏\n\n先完成一轮稳定循环。\n\n## 二、角色与声骸选择\n\n优先保证协同角色覆盖空窗期。\n\n## 三、实战检查清单\n\n进入战斗前确认资源分配。",
  coverImageUrl: "/art/guide-tower.webp",
  author: { id: "user-100", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战" },
  authorId: "user-100",
  publishedAt: "2026-09-14T10:24:00",
  viewCount: 1200,
  likeCount: 88,
  favoriteCount: 12,
  commentCount: 3,
  tags: ["配队", "长离"],
  media: [],
};

const loggedInUser = { id: "user-100", phone: "13800000001", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战", role: "USER" };

/** mock 详情页所需的全部接口。返回评论数组，调用方可改。 */
export async function mockPostDetail(page: Page) {
  await page.route("**/api/v1/auth/me", (route) => route.fulfill({ json: { data: loggedInUser } }));
  await page.route("**/api/v1/auth/csrf", (route) =>
    route.fulfill({ status: 200, headers: { "set-cookie": "XSRF-TOKEN=test-csrf-token; Path=/" }, json: { code: "SUCCESS", data: null } }),
  );
  await page.route(`**/api/v1/posts/${DETAIL_POST_ID}`, (route) => route.fulfill({ json: { data: detailPost } }));
  await page.route("**/api/v1/comments**", (route) =>
    route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 } } }),
  );
  await page.route("**/api/v1/interactions/batch-states**", (route) =>
    route.fulfill({ json: { code: "SUCCESS", data: { [DETAIL_POST_ID]: [0, 0] } } }),
  );
}

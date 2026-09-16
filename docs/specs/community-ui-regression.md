# 核心社区页面响应式、可访问性与视觉回归规格

## Problem Statement

社区核心页面已经完成视觉重构，但不同桌面宽度下的导航、频道栏、详情辅助栏和发布编辑器仍可能出现布局回退。部分交互也需要通过键盘和减少动画偏好验证，避免页面只在单一浏览器宽度下看起来正常。

## Solution

以已批准的首页、帖子详情、个人中心和发布编辑器概念为视觉基准，完成桌面端优先的浏览器回归：保持 1440px 的完整结构，保证 1280px 和 1024px 的内容密度与栏位收缩，保证 768px 的频道抽屉可用，并在 390px 保持无页面级横向滚动。同步验证核心导航、登录入口、发布编辑器、帖子互动和评论入口的键盘可达性，以及 `prefers-reduced-motion` 下不强制播放平滑滚动。

## User Stories

1. As a desktop visitor, I want the community home to preserve its channel, content and recommendation hierarchy at 1440px, so that the page remains easy to scan.
2. As a desktop visitor, I want the home recommendation rail to collapse at 1024px without creating horizontal scrolling, so that the main content remains usable.
3. As a narrow-desktop visitor, I want to open and close the channel drawer with the navigation button, so that I can still reach every channel after the sidebar is hidden.
4. As a post reader, I want the interaction rail and author/context rail to keep their desktop reading positions, so that I can react without losing the current article context.
5. As a publisher, I want the publish editor controls and fixed action bar to remain reachable at desktop widths, so that writing does not require fighting the layout.
6. As a keyboard user, I want navigation, menus, forms, dialogs and publishing controls to have names and visible focus states, so that I can complete core actions without a mouse.
7. As a visitor who prefers reduced motion, I want navigation and settings scrolling to avoid forced smooth animation, so that the interface respects my system preference.
8. As a maintainer, I want screenshot evidence at the agreed widths and a fidelity ledger, so that visual changes can be reviewed against the approved concepts rather than memory.

## Implementation Decisions

- Keep the current shared community shell and existing REST APIs; this slice changes layout, semantics, and test coverage only.
- Treat 1440px as the full desktop baseline, 1280px as the wide-tablet boundary, 1024px as the collapsed recommendation boundary, and 768px as the narrow-desktop drawer boundary.
- Keep the approved visual hierarchy: dark top navigation, white content surfaces, cyan active state, compact 6–8px radii, and restrained borders instead of decorative card shadows.
- Keep the existing fixed/sticky reading aids on the post detail page and verify their computed positioning in a real browser.
- Use semantic names for icon-only controls and preserve visible `:focus-visible` states.
- Any programmatic scrolling must choose `auto` when the user agent reports `prefers-reduced-motion: reduce`.
- Do not introduce new content, API fields, visibility controls, or a mobile-first redesign in this slice.

## Testing Decisions

- Test public user behavior through Playwright at the route and browser seam; do not assert internal React state or CSS implementation details unless the behavior is inherently layout-related.
- Cover the four core routes at 1440px and representative boundaries at 1280px, 1024px, 768px, and 390px for page-level overflow.
- Verify keyboard activation for the narrow-desktop channel drawer, the top “更多” menu, publish controls, and the login entry.
- Emulate reduced motion and verify settings navigation does not invoke smooth scrolling.
- Capture current screenshots for the four approved concepts and record at least five concrete fidelity comparisons in the PR.
- Reuse the existing community shell, post-detail, profile, and publish E2E suites; add only the missing public seams.

## Out of Scope

- New backend behavior, database changes, content moderation, or API contract changes.
- Replacing the approved community information architecture.
- Perfect mobile parity; mobile widths are regression guards while desktop remains the primary design target.

## Further Notes

- The reference product informs information density and interaction rhythm only. The project keeps its own 鸣潮社区 branding, copy, data, and assets.
- Any discrepancy between a concept and an existing validated contract must be recorded in the fidelity ledger rather than silently changing the contract.

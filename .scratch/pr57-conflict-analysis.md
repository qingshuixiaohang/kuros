# PR #57 合并冲突分析：`origin/codex/issue-56-character-catalog` → `main`

> 分析性质：**纯只读**。所有结论来自 `git log` / `git diff` / `git merge-tree --write-tree` / `git ls-tree` 等查询命令，未修改、未 `git add`、未 commit 任何仓库文件。
> 本报告是唯一新增文件（`.scratch/pr57-conflict-analysis.md`），且 `.scratch/` 已在 `.gitignore` 之外的工作区中处于未跟踪状态，不影响仓库树。

---

## 0. 结论速览

| 维度 | 结论 |
| --- | --- |
| 分支独有提交 | 2 个（`8c02e8d`、`b57f0a4`） |
| 改动文件 | 17 个（+673 / −100） |
| main 同期也改过的文件 | 2 个：`CONTEXT.md`、`front/src/lib/api.ts` |
| **真实文本冲突** | **1 个：`front/src/lib/api.ts`**（`git merge-tree --write-tree` 实测） |
| Flyway 编号 | **安全，不撞号**。分支新增 `V11__character_catalog.sql`，main 最大版本号为 `V10`，且全仓所有 ref 中只有本分支持有 V11 |
| 隐性风险 | 2 处非冲突但需人工复核：`CONTEXT.md`→`GLOSSARY.md` 重命名被自动吸收、`ResourceNotFoundException` 默认错误码回归为 `POST_NOT_FOUND` |

---

## 1. 分支独有提交（`git log --oneline main..origin/codex/issue-56-character-catalog`）

| Commit | 日期 | 说明 |
| --- | --- | --- |
| `b57f0a4` | 2026-10-04 | `fix(character): 种子与 e2e 图片引用随素材迁移切换 WebP` —— 跟随 main@`a947d18` 的 WebP 素材迁移，把 V11 种子 `image_url` 与 e2e mock 图片从 `.png` 改为 `.webp` |
| `8c02e8d` | — | `feat: connect character catalog to database` —— 角色图鉴垂直切片主体（后端实体/仓储/服务/控制器 + 前端 react-query 数据层 + e2e + 规格文档） |

分叉点（merge-base）：`9fd40f0`
相对位置：`git rev-list --left-right --count main...origin/codex/issue-56-character-catalog` → **main 领先 48，分支领先 2**。
（注：本地 `main` = `origin/main` + 1 个未推送提交 `6cfa235`「chore(dev-env): 钉死 JDK 21…」；若以 `origin/main` 为基准，冲突集合完全一致，见 §4 复核。）

---

## 2. 分支改动文件（`git diff --name-only main...origin/codex/issue-56-character-catalog`）

| # | 文件 | 类型 | main 是否也改过 | 是否冲突 |
| --- | --- | --- | --- | --- |
| 1 | `front/src/lib/api.ts` | 修改 | **是**（`a230c1e`） | **冲突（content）** |
| 2 | `CONTEXT.md` | 修改 | **是**（`2a83984` 删除） | 文本无冲突，但语义需复核 |
| 3 | `docs/api-contract.md` | 修改 | 否 | 否 |
| 4 | `docs/specs/character-catalog.md` | 新增 | 否 | 否 |
| 5 | `front/e2e/character-catalog.spec.ts` | 新增 | 否 | 否 |
| 6 | `front/src/app/characters/[slug]/page.tsx` | 新增（由 `[id]` 重命名） | 否 | 否 |
| 7 | `front/src/components/community/characters-page.tsx` | 重写 | 否 | 否 |
| 8 | `kuros-backend/.../character/api/CharacterResponse.java` | 新增 | 否 | 否 |
| 9 | `kuros-backend/.../character/domain/GameCharacter.java` | 新增 | 否 | 否 |
| 10 | `kuros-backend/.../character/repository/GameCharacterRepository.java` | 新增 | 否 | 否 |
| 11 | `kuros-backend/.../character/service/CharacterCatalogService.java` | 新增 | 否 | 否 |
| 12 | `kuros-backend/.../character/web/CharacterController.java` | 新增 | 否 | 否 |
| 13 | `kuros-backend/.../shared/config/SaTokenConfigure.java` | 修改（仅注释） | 否 | 否 |
| 14 | `kuros-backend/.../shared/exception/ResourceNotFoundException.java` | 修改 | 否 | 否（语义需复核） |
| 15 | `kuros-backend/.../shared/web/ApiExceptionHandler.java` | 修改 | 否 | 否 |
| 16 | `kuros-backend/src/main/resources/db/migration/V11__character_catalog.sql` | 新增 | 否 | 否 |
| 17 | `kuros-backend/src/test/java/.../KurosBackendApplicationTests.java` | 修改 | 否 | 否 |

合计：17 files changed, 673 insertions(+), 100 deletions(-)。

---

## 3. 逐文件 main 侧改动检查（`git log --oneline main -- <file>`，以 merge-base `9fd40f0` 为界）

| 文件 | main 侧提交数 | 具体提交 | 判定 |
| --- | --- | --- | --- |
| `CONTEXT.md` | 1 | `2a83984` chore: 补删 CONTEXT.md（删除 264 行） | **modify/delete**：main 删文件、分支追加 7 行决策记录 |
| `front/src/lib/api.ts` | 1 | `a230c1e` refactor(front): 浮层行为统一 useOverlay + 会话恢复可观察 + api 死代码清理 | **content conflict**：main 删 `fetchFollowingFeed`，分支在其后方插入角色函数 |
| 其余 15 个文件 | 0 | — | 无 main 侧改动，纯 add/one-side 修改，可自动合并 |

---

## 4. 无副作用试合并（`git merge-tree --write-tree main origin/codex/issue-56-character-catalog`）

退出码 `1`（有冲突）。输出：

```
1842a396f5aaab807fa12c573d4b06a60be8140c
100644 7e53c1b… 1  front/src/lib/api.ts
100644 53bd110… 2  front/src/lib/api.ts
100644 d2c59c1… 3  front/src/lib/api.ts

Auto-merging front/src/lib/api.ts
CONFLICT (content): Merge conflict in front/src/lib/api.ts
```

**唯一冲突文件：`front/src/lib/api.ts`。**

用 `origin/main` 复跑（`git merge-tree --write-tree origin/main origin/codex/issue-56-character-catalog`）结果完全相同：仍只有 `front/src/lib/api.ts` 一个 content 冲突。说明本地 `main` 多出的未推送提交 `6cfa235` 不影响冲突面。

### 4.1 冲突根因与具体冲突块

| 项 | 内容 |
| --- | --- |
| 共同祖先（stage 1） | `7e53c1b`：包含 `fetchFollowingFeed`（offset 分页版） |
| main 侧（stage 2 / ours） | `53bd110`：**删除**整个 `fetchFollowingFeed` 函数（`a230c1e` 判定为死代码，`git grep` 确认 main 全仓已无调用方） |
| 分支侧（stage 3 / theirs） | `d2c59c1`：**保留** `fetchFollowingFeed`，并在其正后方插入 `ApiCharacter` 类型 + `fetchCharacterPage` + `fetchCharacter` |
| 冲突形态 | 分支的插入锚点（`fetchFollowingFeed` 之后、`fetchPost` 之前）恰好落在 main 删除的同一区域，三方合并无法判定该保留哪一侧，产生一个 content 冲突块 |
| 冲突块行号（合并结果） | 第 180–214 行 |

冲突块示意（ours 侧为空，theirs 侧为 3 段新增）：

```
<<<<<<< ours (main)
=======
/** 关注流（offset 分页，保留兼容）… */
export async function fetchFollowingFeed(...) { … }
export async function fetchCharacterPage(...) { … }   // 需要保留
export function fetchCharacter(slug: string) { … }    // 需要保留
>>>>>>> theirs (branch)
```

> 注意：`ApiCharacter` 类型定义（文件第 27 行附近）**不在冲突块内**，已被自动合并进结果树，无需手工处理。

### 4.2 建议解决方式

取 **theirs 侧的新增部分、丢弃 main 已删除的 `fetchFollowingFeed`**，即：

- 删除 `fetchFollowingFeed`（连同其上方的 offset 分页注释块）——尊重 main 的死代码清理，且 main 全仓无调用方；
- 保留 `fetchCharacterPage` 与 `fetchCharacter`；
- `ApiCharacter` 类型已自动合并，检查其在 `ApiPost` 之后的位置是否符合阅读顺序即可。

一句话：**保留分支的角色函数、删掉 offset 版关注流**，冲突块取 "空 ours + theirs 去掉 `fetchFollowingFeed`"。

### 4.3 `CONTEXT.md`：文本不冲突，但要注意"静默重命名吸收"

| 侧 | 行为 |
| --- | --- |
| main | `2a83984` **删除** `CONTEXT.md`（264 行），内容已由 `GLOSSARY.md`（blob `e17f9b6`）承载 |
| 分支 | 在 `CONTEXT.md` 末尾**追加** 7 行「2026-09-18 角色图鉴真实化决策」 |
| 合并器行为 | Git 把 main 侧的 `CONTEXT.md → GLOSSARY.md` 识别为**重命名**，分支对 `CONTEXT.md` 的追加被自动"搬运"到 `GLOSSARY.md` 末尾。试合并结果树中 `CONTEXT.md` 不存在，`GLOSSARY.md` = `d6b5437`（= 分支版 CONTEXT.md 的内容） |

**这不是冲突，但比冲突更危险**：自动合并"看起来成功"，实际发生了一次跨文件的内容迁移。合并后需人工确认：

1. `GLOSSARY.md` 末尾确实多出那 7 行角色图鉴决策记录；
2. 该 7 行放在术语表 `GLOSSARY.md` 里语义是否合适（它是"决策记录"而非"术语定义"，与 `GLOSSARY.md` 其余条目的「术语 / _避免_」体例不一致）——建议改为在 `docs/specs/character-catalog.md` 或 `HANDOFF.md` 中记录，或确认团队接受；
3. 工作区不再出现 `CONTEXT.md`。

---

## 5. Flyway 迁移编号安全性

### 5.1 编号清点

| 数据源 / 位置 | main 已有版本号 | 分支新增 | 是否撞号 |
| --- | --- | --- | --- |
| `kuros-backend` `classpath:db/migration` | V1, V2, V3, V4, V5, V6, V7, **V9**, V10 | `V11__character_catalog.sql` | **否**（V11 未占用） |
| `kuros-user` `classpath:db/migration` | V1, V2 | 无 | 不适用（独立 `kuros_user` 库，独立 Flyway history） |

### 5.2 交叉验证

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 全仓所有 ref（本地 + 远端 + 所有分支）是否已有 V11 | `for ref in $(git for-each-ref …); do git ls-tree -r $ref \| grep db/migration/V11; done` | 仅 `refs/heads/codex/issue-56-character-catalog` 与 `refs/remotes/origin/codex/issue-56-character-catalog` 持有，**无第二个 V11** |
| merge-base 处的版本号上限 | `git ls-tree -r 9fd40f0 \| grep db/migration` | 同为 V1–V7/V9/V10，上限 V10 |
| main 上 `db/migration/` 自分叉后是否有新迁移 | `git log --oneline 9fd40f0..main -- kuros-backend/src/main/resources/db/migration/ kuros-user/.../db/migration/` | 空（main 未新增迁移） |
| V11 是否已被任何文档/脚本引用 | `git grep -rn "V11" main -- docs .github scripts` | 无 |

### 5.3 附带发现：**V8 编号空洞**

main 的 `kuros-backend` 迁移序列为 `V1–V7, V9, V10`，**缺 V8**，且 `git log --all -- "*V8__*"` 证明 V8 从未在任何提交中存在过（不是被删除，而是一开始就没建）。

- Flyway 本身不要求版本号连续，缺号不会导致启动失败；
- 但这是一个既有卫生问题，建议顺手确认是否有"建了 V8 又改名/漏提交"的历史，避免以后有人误以为 V8 是可复用的空号。

### 5.4 结论

**V11 编号安全，不会与 main 已有迁移撞号，也不会与其他在飞分支撞号。** 合并后 `kuros-backend` 的版本序列为 `V1–V7, V9, V10, V11`，Flyway 会按版本号顺序执行，`V11__character_catalog.sql` 是最后一个被应用的迁移。

---

## 6. 汇总：冲突清单 / 原因 / 解决顺序

### 6.1 冲突文件清单

| 冲突文件 | 冲突类型 | 冲突原因 | 严重度 |
| --- | --- | --- | --- |
| `front/src/lib/api.ts` | **content** | main 删除 `fetchFollowingFeed`（offset 版）← `a230c1e`；分支保留它并在其后插入角色图鉴函数。同一区域一删一增 | 低（机械可解） |
| `CONTEXT.md`（→`GLOSSARY.md`） | **无文本冲突，语义风险** | main 删除 `CONTEXT.md`、分支修改它。重命名检测自动把分支追加内容搬进 `GLOSSARY.md`，需人工确认落地位置 | 中（易被忽略） |

非冲突但需回归验证的语义改动（合入后行为变化，不产生冲突标记）：

| 文件 | 改动 | 影响面 |
| --- | --- | --- |
| `shared/exception/ResourceNotFoundException.java` | 新增 `code` 字段，默认构造器传 `"POST_NOT_FOUND"` | 既有帖子 404 行为不变（默认码仍是 `POST_NOT_FOUND`）；新调用方传 `CHARACTER_NOT_FOUND`。**回归点**：确认没有其他 `new ResourceNotFoundException(msg)` 的调用方期望别的错误码 |
| `shared/web/ApiExceptionHandler.java` | `resourceNotFound` 改用 `exception.getCode()` | 对外错误码从硬编码 `POST_NOT_FOUND` 变为异常自带；需跑一遍既有 404 相关测试 |
| `shared/config/SaTokenConfigure.java` | 仅注释文案更新 | 无行为变化 |
| `front/src/app/characters/[id] → [slug]/page.tsx` | 路由段重命名 | URL 形态不变（前端一直用 slug 值填充），但 `front/src/components/community/creations-page.tsx` 仍用 mock `item.id` 拼 `/characters/{id}`。mock 的 `characters` 数组里 `id` 恰好等于 slug（`shorekeeper` 等），当前不会 404，属脆弱耦合 |
| `front/src/components/community/characters-page.tsx` | 从 mock 本地过滤改为 react-query + 分页 | `characters` mock 数组仍被 `creations/tools` 页使用（分支提交信息已声明不在范围内） |

### 6.2 建议解决顺序

1. **先 rebase/merge main 到分支，定位唯一冲突文件** `front/src/lib/api.ts`（`git merge-tree` 已证实只有这一个）。
2. **解 `front/src/lib/api.ts`**：删除 `fetchFollowingFeed` 及其上方注释块，保留 `fetchCharacterPage` / `fetchCharacter`；`ApiCharacter` 类型已在冲突块外自动合并，只需目视确认位置。
3. **复核 `CONTEXT.md` → `GLOSSARY.md` 的静默重命名吸收**：确认 7 行决策记录落在 `GLOSSARY.md` 末尾；若认为体例不符，改放到 `docs/specs/character-catalog.md` 或 `HANDOFF.md`，并显式删除 `CONTEXT.md`。
4. **跑后端测试** `KurosBackendApplicationTests`（分支新增 5 个角色图鉴用例，同时覆盖 `ResourceNotFoundException` / `ApiExceptionHandler` 的 404 回归）。
5. **跑前端 e2e** `front/e2e/character-catalog.spec.ts`，并顺带验证 `creations-page` / `tools` 页的角色链接未因 `[id]→[slug]` 重命名而 404。
6. **本地起一次 Flyway**：确认 `V11` 在已有 `V1–V7/V9/V10` 之上正常 apply、`flyway_schema_history` 校验和无冲突。
7. 再进入 code-review 与提交/PR 流程（遵循 `docs/agents/issue-tracker.md`）。

### 6.3 迁移编号安全性

- **安全**。`V11` 在 main（上限 V10）与全部其他 ref 中均未占用，不存在撞号。
- 附带提醒：main 侧存在 **V8 空洞**（历史遗留，非本次引入），Flyway 容忍缺号，但建议另行清理认知。

---

## 7. 复核用命令（全部只读）

```bash
git log --oneline main..origin/codex/issue-56-character-catalog
git diff --name-only main...origin/codex/issue-56-character-catalog
git log --oneline 9fd40f0..main -- <file>          # 逐文件查 main 侧改动
git merge-tree --write-tree main origin/codex/issue-56-character-catalog
git ls-tree -r --name-only main | grep db/migration | sort
git for-each-ref --format='%(refname)' refs/heads refs/remotes \
  | while read r; do git ls-tree -r --name-only "$r" | grep db/migration/V11; done
```

---

*报告生成于只读分析会话；未执行任何写操作、`git add` 或 `git commit`。*

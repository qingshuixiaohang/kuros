# Kuros 本地整理清单

核对基准：GitHub main `70dd3648455cd213e20be0d769335c33fad130bc`。日常目录 `G:/kuros-web` 已同步到此版本。
本地复习文档候选：`codex/organize-review-docs`，提交 `9308054`。包含 ADR 复习索引和两张架构图，尚未合入主线。可用 `git show codex/organize-review-docs:docs/adr/README.md` 查看索引。
整理前 #136 的修改、删除及指定未跟踪文件保存在本地 stash `945acca674087d29e53804a38bb9d21880942b7e`。在原分支确认工作区干净后，使用 `git stash apply 945acca674087d29e53804a38bb9d21880942b7e` 恢复；不要直接应用到 main。

以下是候选清单，尚未删除。提交顶端未进主线不代表内容一定未合并。工作树移除前仍要核对未跟踪及忽略文件、子工作树和本地数据。

## 已入主线、未占用工作树：34

- `codex/distributed-session-auth` `0e4b6b080a3f698d30118bf6c75b42f0056355e0`
- `codex/issue-1-contract` `51ea671bfff0efa7eefb8d0623e0c219bfeb11fc`
- `codex/issue-132-redis` `3d3e81afe76797b691d92ca04853fe47052f8ae9`
- `codex/issue-134-minio-source` `1f7072268b247157f23254172e0a28ba55ff597f`
- `codex/issue-136-compose-user-profile` `28a0fb9b0dc5020519fc7bf2095541117d494eb0`
- `codex/issue-14-home-feed` `185f49b82424941c43039ecca743448deb3fd6f7`
- `codex/issue-17-image-upload` `02d530677e81bc3de590fb07fe3ff1ea1779f3a9`
- `codex/issue-19-moderation` `094391ff00765691e67aba1124de6c8f94b2418a`
- `codex/issue-2-post-browse` `6523cc78483d1962edee269dff4f01bb7ff82912`
- `codex/issue-21-report-ui` `738f9cb1b25c0d4dec4d3a2e226cf5353dbb15e6`
- `codex/issue-23-post-management` `cbf3a2dea5c01f07c611c0572c9c6b9cdc883649`
- `codex/issue-25-discovery` `89e37b9fd30917219dd2c1689ffd178dd65fb7a0`
- `codex/issue-27-publish-editor` `3e0e2af4991c96b924a5bd2237bf2e70cb43051c`
- `codex/issue-29-public-profile` `5dea013b0c036865fd276bd17243c614860221af`
- `codex/issue-3-login` `074052082529e92520476cf0adcbb7aac03a0273`
- `codex/issue-31-ci` `bf3c173e0e79053368220e1536452c35f674444a`
- `codex/issue-34-community-ui` `120dce08751253d93f28671bce4c86bd025bc08e`
- `codex/issue-35-post-detail-ui` `e9fb2d3115a990f501a7e575d2d66f3253c180d2`
- `codex/issue-36-profile-ui` `195afe6aefb2915fd118d8148ccad7c754fb5344`
- `codex/issue-37-publish-ui` `7e5359d12f6700fdc654b6aba8bd24a7011416f8`
- `codex/issue-38-ui-regression` `d0ba72abfab2d207cbe2531d65fddf0c0bc2e87b`
- `codex/issue-4-comments` `03da5c62e221dd4b954953d332f60c0b085be6cb`
- `codex/issue-44-account-comment-ui` `90836bc277a1d9dbbdc0c1a8b4d6492e92216fa3`
- `codex/issue-5-profile` `a2ea17321182a2d0ddc315b5aa25eaa471ed9620`
- `codex/issue-67-service-split` `b9fdc2530027146336f66b73c5462f70f546224c`
- `codex/issue-7-interactions` `809e3ee1d7607d6c1c9f7878e4c1d1bd2d20a576`
- `codex/issue-8-publishing` `8a6fede3e06934d04beb8937d3f1939299244443`
- `codex/minio-compose-retro` `769bb26436b69afd82430c5a5d34f302c9ecad28`
- `codex/slice-11-async-interaction` `51a39fbd266e59eea15216e51ea8e865852b3691`
- `codex/slice-12-feed-timeline` `df6d5d105a1dc87f815899c7e446936f4436527c`
- `codex/slice-13-read-path` `fd4e041c85ac13c846f41b7ad7c7f4d86e46d20c`
- `codex/slice-14-search-cdc` `cceef393f0e6a508e9e6784fa46b02be6f169d50`
- `docs/agent-rules` `51ba4db992f5d9eac2feb83c30355c9fc79ec9d9`
- `fix/batch-a-security` `416134d33da88be638d82174e16cf72300868427`

## 已入主线、仍占用工作树：30

- `codex/api-error-contract` `7f72ca1a8a15623e10a3271ff19291bc6c213d6f` → `G:/kuros-web/.scratch/worktrees/api-error-contract`
- `codex/browser-baseline-repair` `20d546c64718b0c3af882cbf2295d55ea8d7ae66` → `G:/kuros-web/.scratch/worktrees/browser-baseline-repair`
- `codex/character-filter-a11y` `006e5fec2988cc46e9d7d9ac25e7495c4d465b7b` → `G:/kuros-web/.scratch/worktrees/character-filter-a11y`
- `codex/characters-empty-state-a11y` `32f5141761e04e0810f6c86c3d0adeca985a10fa` → `G:/kuros-web/.scratch/worktrees/characters-empty-state-a11y`
- `codex/community-channel-drawer` `19e2501a622e1005a06ea36f3290fd0acbfdb850` → `G:/kuros-web/.scratch/worktrees/community-channel-drawer`
- `codex/community-feed` `2a361963d2438dde50fe8026117021028b92e3c4` → `G:/kuros-web/.scratch/worktrees/community-feed`
- `codex/community-navigation` `2c44b64348c4d915ae3c7b4106add5262f5d4238` → `G:/kuros-web/.scratch/worktrees/community-navigation`
- `codex/community-right-rail` `9ce4e9a25d89c105fd001f54de78adfff140f156` → `G:/kuros-web/.scratch/worktrees/community-right-rail`
- `codex/echoes-status-live` `85094d683699676d0abbe1b13b2f57b6b7f510d5` → `G:/kuros-web/.scratch/worktrees/echoes-status-live`
- `codex/echoes-view-a11y` `89507c2bc0c22ad18b71906acf3083bc0a1b02e3` → `G:/kuros-web/.scratch/worktrees/echoes-view-a11y`
- `codex/fix-post-detail-test-mock` `fbee87850cb135c2837e2de57514d7b73f18b666` → `G:/kuros-web/.scratch/worktrees/fix-post-detail-test-mock`
- `codex/gateway-nacos-container-ports` `feeea00519a7b577f3889aef1acdffe0c90bd699` → `G:/kuros-web/.scratch/worktrees/gateway-nacos-container-ports`
- `codex/global-skip-link` `5534d230cb7903a329b0042f1088ed40ab4a3b3b` → `G:/kuros-web/.scratch/worktrees/global-skip-link`
- `codex/guides-empty-state-a11y` `2361ca6a83555e8886b243fa80dc5e558ddfb56a` → `G:/kuros-web/.scratch/worktrees/guides-empty-state-a11y`
- `codex/guides-filter-a11y` `580c478b1efc63adde971cdf6c02220dc09bbc09` → `G:/kuros-web/.scratch/worktrees/guides-filter-a11y`
- `codex/guides-filter-e2e` `466cdaa24079319a4216c14e16e0b573f24ef324` → `G:/kuros-web/.scratch/worktrees/guides-filter-e2e`
- `codex/guides-loading-status` `16399aae5a78df22335077083242be07d11100f4` → `G:/kuros-web/.scratch/worktrees/guides-loading-status`
- `codex/home-feed-loading-status` `5288a94b85a0ec1dffb3682379d46082b50d6220` → `G:/kuros-web/.scratch/worktrees/home-feed-loading-status`
- `codex/issue-56-character-catalog` `e148eabfe5a5202675237daeec531815ba183180` → `G:/_tmp/kuros-i56`
- `codex/media-upload-consistency` `f7488ed8019cbc1a22da39b835bea7a1328c64b8` → `G:/kuros-web/.scratch/worktrees/media-upload-consistency`
- `codex/news-filter-a11y` `b9d835126404491a0861ba0df5cba71f760ca541` → `G:/kuros-web/.scratch/worktrees/news-filter-a11y`
- `codex/news-loading-status` `dd0352a8c94f1da6608e5dbc917adfba89b4d827` → `G:/kuros-web/.scratch/worktrees/news-loading-status`
- `codex/playwright-mock-guidance` `fcff1e0b74ee67ca66bc50ab6d401f50bdefa79f` → `G:/kuros-web/.scratch/worktrees/playwright-mock-guidance`
- `codex/readiness-accessibility` `f4f156dcbe796feae1213224a2902451518086f0` → `G:/kuros-web/.scratch/worktrees/readiness-accessibility`
- `codex/readiness-publishing` `f4f156dcbe796feae1213224a2902451518086f0` → `G:/kuros-web/.scratch/worktrees/readiness-publishing`
- `codex/report-moderation-boundary` `b48ba581b7e585c1e5e781e352f3559e666b8190` → `G:/kuros-web/.scratch/worktrees/report-moderation-boundary`
- `codex/request-correlation-id` `ab9a57b9fbd8988b5c95c440a18c047dba4cb68b` → `G:/kuros-web/.scratch/worktrees/post-interaction-read-boundary/.scratch/worktrees/request-correlation-id`
- `codex/roadmap-status` `03270aad684d7a4c5a9f45beccb867812e247a62` → `G:/kuros-web/.scratch/worktrees/roadmap-status`
- `docs/agent-quality` `27b683b26caf6db60315bbef104774e07aa25c92` → `G:/dsh/wt-rules2`
- `docs/workflow-fix` `0ac0ebabf4f208fdf1e8553de591412d388d1836` → `G:/dsh/wt-workflow-fix`

## 顶端不在主线：13（先保留）

- `codex/interaction-count-reconciliation-sweep` `780242a6a31aa15c1d72daca04204a1d2fa0a250` G:/kuros-web/.scratch/worktrees/minio-compose-retro
- `codex/issue-170-community-ui-polish` `d76e428350d7b566d7551aac1d5e186916061c1e` G:/kuros-web/.scratch/worktrees/product-readiness
- `codex/issue-49-asset-refresh` `132a78aa005f527d244c2f5ca3e51fbad05516ad` 
- `codex/issue-50-community-carousel` `21d22caca071fb6cf0f287e5e38144d21bd1d356` 
- `codex/issue-51-post-media` `6dec222e538e7c8d2e9e1c39aade863c509c25a3` 
- `codex/issue-54-tool-icons` `373fce51e8de66cac294ab310fd15b08eca62280` 
- `codex/organize-review-docs` `9308054f72eeaed916603f92d8dcc1c80d41dd27` 
- `codex/post-interaction-read-boundary` `a5c4c4d54cc668f1c1a486dccae5d8c3acbad0be` G:/kuros-web/.scratch/worktrees/post-interaction-read-boundary
- `codex/product-readiness` `270b7fa45535381048063f08c0a6753d276ebdc7` 
- `codex/readiness-handoff` `e9749e3d229940a5ee171812fd4d49f0d925df71` 
- `codex/roadmap-release-final` `8fcb839bfe98d871f736648a3c0c37a0fde179f4` G:/kuros-web/.scratch/worktrees/roadmap-release-final
- `codex/roadmap-release-integration` `bfb3caf73857353eaa8ee33b2597e883b5a5a916` G:/kuros-web/.scratch/worktrees/roadmap-release-integration
- `docs/adr-unify` `797a45d16c87ec429902591f9c5bb4015c9a95cb` G:/dsh/adr-unify

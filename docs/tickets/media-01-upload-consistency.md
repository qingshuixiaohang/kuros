# MEDIA-01 媒体上传一致性补偿

状态：implemented（本地与 Testcontainers 验证通过，待 CI 复验）

依赖：无

规格 Issue：[#149](https://github.com/qingshuixiaohang/kuros/issues/149)

实现 Issue：[#150](https://github.com/qingshuixiaohang/kuros/issues/150)

规格：[`docs/specs/media-upload-consistency.md`](../specs/media-upload-consistency.md)

## 交付

- 元数据保存失败时，对象存储零写入。
- 上传成功时，媒体资产先以临时态持久化，再写一次对象。
- 对象存储失败时，已登记的临时资产被标记删除，并传播原存储错误。
- REST 请求与响应保持不变。

## TDD 验收

1. 写“元数据保存失败仍写对象存储”的回归测试，确认 RED。
2. 调整为元数据优先并确认 GREEN。
3. 写对象写入失败补偿测试，先确认 RED，再最小实现。
4. 跑媒体域单元测试、现有上传 API 相关测试和 `mvnw test-compile`。

## 已知限制

- 不新增自动调度；临时资产清理继续由后续任务机制触发。
- 不盘点本次改动前已经存在的无元数据对象。

## 验证记录

- RED：元数据保存失败用例最初观察到对象存储写入 1 次，断言期望 0 次。
- RED：对象存储写入失败用例最初只保存 `TEMPORARY`，没有补偿写入 `DELETED`。
- GREEN：`MediaUploadConsistencyTest,TemporaryAssetCleanupTest` 8/8，覆盖元数据失败零写入、成功顺序、存储失败补偿、补偿失败异常传播和既有清理语义。
- `mvnw test-compile`：通过。
- `KurosBackendApplicationTests` 38/38 通过，覆盖既有上传、所有权、帖子绑定和认证端点。
- Windows 本机验证需要显式使用 Docker Desktop 命名管道、JDK 的 unix-domain socket 临时目录和静态 Byte Buddy agent；这些参数只用于验证命令，未写入项目配置。

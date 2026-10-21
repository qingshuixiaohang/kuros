# 29 · 双服务 API 错误契约

> 对应 Issue：[#145](https://github.com/qingshuixiaohang/kuros/issues/145)、[#146](https://github.com/qingshuixiaohang/kuros/issues/146)

## 一、架构迁移全景

`kuros-backend` 与 `kuros-user` 原先只覆盖了一部分业务异常。坏 JSON、参数类型错误、上传超限和未预期异常会落入 Spring 默认响应，客户端拿不到稳定的 `code`；`ResourceNotFoundException` 还会把默认错误码误标成 `POST_NOT_FOUND`。

本次让两个服务各自输出同一条错误信封：

```json
{
  "code": "INVALID_REQUEST",
  "message": "请求体格式不正确"
}
```

`fieldErrors` 只在 Bean Validation 或绑定错误产生字段映射时出现。业务异常继续保留细分错误码；资源不存在时，由抛出点显式提供 `POST_NOT_FOUND`、`COMMENT_NOT_FOUND`、`MEDIA_NOT_FOUND`、`REPORT_NOT_FOUND` 或 `USER_NOT_FOUND`。

没有新增跨服务 common 模块。两个服务按同一契约维护各自处理器，避免为了少量共享代码引入版本同步和发布耦合。

## 二、关键难点解析

### 1. 稳定错误码与展示文案分离

客户端分支只依赖 `code`，`message` 面向用户展示。未知异常固定返回 `INTERNAL_ERROR`，服务端仍记录完整堆栈和 requestId，但响应中不返回异常消息、类名或堆栈。

### 2. 校验错误不能压成单字段

`MethodArgumentNotValidException` 与 `BindException` 都从 `BindingResult` 提取字段映射，并使用 `LinkedHashMap + putIfAbsent` 保持稳定顺序。普通错误直接省略 `fieldErrors`，不再混用 `null` 和空对象。

### 3. 资源码由生产者选择

`ResourceNotFoundException` 不再提供默认错误码构造器。编译器会迫使每个调用点表达资源类型，避免新增资源后静默复用 `POST_NOT_FOUND`。`kuros-user` 当前 404 生产者只有用户查询，因此保留 `USER_NOT_FOUND` 映射。

### 4. 容器上限与应用校验是两层

业务层仍负责 10 MB 图片规则；请求进入 Spring MVC 前，容器可能在更早阶段拒绝超大请求，并产生 `MaxUploadSizeExceededException`。两个服务把该异常映射为 `413 UPLOAD_TOO_LARGE`。本地最小 Compose 栈随后用 15 MB multipart 请求验证了真实容器链路，响应为 HTTP 413 与 `UPLOAD_TOO_LARGE`。

### 5. 框架级 4xx 不能被通用 500 吞掉

multipart 缺少必填 part 会抛 `MissingServletRequestPartException`，它不是 `ServletRequestBindingException` 的子类；`Accept` 不兼容则抛 `HttpMediaTypeNotAcceptableException`。两者若不单独映射，都会落入通用 500。现已分别映射为 400 `INVALID_REQUEST` 与 406 `NOT_ACCEPTABLE`，并补齐 405、415 契约测试。406 响应显式声明 JSON，因为客户端拒绝 JSON 时普通内容协商会连错误信封一起丢掉。

### 6. 测试接缝

契约测试直接经过应用异常处理器，分别验证坏 JSON、缺查询参数、缺 multipart part、字段校验、405、406、415 和未知异常。应用级回归还覆盖真实 SaToken、CSRF 和业务端点，避免接口契约只在孤立单测中成立。

## 三、简历 STAR 写法

- **S**：两个 Spring 服务对异常的处理不一致，部分框架错误返回默认响应，资源不存在还可能被误标成帖子错误码。
- **T**：在不引入共享模块的前提下，让所有应用层 4xx/5xx 返回稳定、可解析且不泄露内部信息的错误信封。
- **A**：统一两个服务的 `@RestControllerAdvice`，覆盖绑定、校验、上传、方法、媒体类型、静态资源和未知异常；把资源错误码改为调用点必填；补文档与 compose 413 断言。
- **R**：backend 契约 9/9、应用级 41/41，user 契约 8/8、登录集成 7/7；两侧测试源码编译通过，资源错误码不再依赖默认猜测，最小 Compose 栈也实测返回 413 `UPLOAD_TOO_LARGE`。

## 四、原理详解

请求错误的处理顺序：

```text
request enters Servlet container
  ├─ container rejects oversized body
  │    -> MaxUploadSizeExceededException
  │    -> 413 UPLOAD_TOO_LARGE
  └─ Spring MVC dispatch
       ├─ JSON unreadable / type mismatch / missing parameter
       │    -> 400 INVALID_REQUEST
       ├─ Bean Validation
       │    -> 400 VALIDATION_FAILED + fieldErrors
       ├─ domain exception
       │    -> domain status + explicit stable code
       └─ unhandled exception
            -> log with requestId -> 500 INTERNAL_ERROR
```

错误处理器越具体越优先；`Exception.class` 只负责最后兜底。日志保留内部上下文，响应只暴露安全、稳定的信息。

## 五、面试八股文整理

**问：为什么每个服务复制错误处理器，而不抽 common 模块？**

答：当前共享面只有信封形状，异常类型和领域码仍由各服务拥有。复制少量明确代码比建立一个横跨所有服务的发布依赖更简单，也避免服务升级被 common 版本锁住。

**问：为什么资源不存在不让异常类提供默认码？**

答：默认码会把编译期应回答的问题推迟到线上。要求调用点显式传码，新增资源类型时编译器会立刻暴露遗漏。

**问：为什么未知异常还要记录完整堆栈？**

答：客户端不应看到内部实现细节，但服务端必须保留排障证据。requestId 用于把安全响应与完整日志关联起来。

**问：为什么 `fieldErrors` 缺失而不是返回空对象？**

答：字段错误的“不存在”和“存在但为空”应只有一种表示。省略字段让普通错误信封更小，也消除客户端对 `null`/`{}` 的双重分支。

## 六、技术选型对比

| 方案 | 判断 |
| --- | --- |
| 继续依赖 Spring 默认错误响应 | 拒绝；客户端无法稳定按 `code` 分支 |
| 两个服务各自维护一致 handler | 采用；契约一致，领域异常仍由服务拥有 |
| 新建 common 模块共享 handler | 暂不采用；当前共享收益不足，增加发布耦合并不能消除领域差异 |
| 资源异常保留默认 `POST_NOT_FOUND` | 拒绝；错误码依赖调用点记忆，容易线上误报 |
| 未知异常直接返回异常消息 | 拒绝；可能泄露 SQL、路径和框架内部信息 |

## 七、面试叙事模板

**30 秒版**：我统一了后端和用户服务的 API 错误契约。坏 JSON、缺参数、405/406/415、上传超限和未知异常现在都返回稳定错误信封，资源不存在必须由调用点显式给出资源码。补了契约测试和真实端点回归后，backend 41 个应用级用例和 user 登录链路都通过，最小 Compose 栈也实测返回 413 `UPLOAD_TOO_LARGE`。

**2 分钟版**：这个项目拆成 backend 和 user 后，两边只处理了部分业务异常，框架错误会落到 Spring 默认响应，客户端拿不到统一 code；更危险的是资源不存在曾有默认 `POST_NOT_FOUND`，新增资源类型很容易误报。我先用契约测试把框架边界写成 RED，再在各服务补 handler：坏 JSON、查询参数、multipart part 统一 `INVALID_REQUEST`，字段校验保留 `fieldErrors`，405/406/415 分别保留正确 HTTP 语义，上传超限是 `413 UPLOAD_TOO_LARGE`，未知异常只返回 `INTERNAL_ERROR` 并在服务端带 requestId 记完整堆栈。资源异常类移除默认码，所有调用点必须显式选择。最后跑 backend 应用级 41/41、user 登录集成 7/7，并在最小 Compose 栈实发 15 MB multipart 请求，确认容器链路返回 413 `UPLOAD_TOO_LARGE`。

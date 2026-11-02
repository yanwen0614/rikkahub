# 跨助手会话委托：设计计划

## Goal

为助手增加可选的本地委托工具，将文本请求交给被允许调用的另一助手，支持复用该助手的会话或新建专属会话，并仅返回本次请求最终完成的 assistant 回复。安全边界必须由运行时校验，不可依赖模型看到的工具描述。本文为设计，不代表现有 API 已支持委托关联。

## Architecture

### 动态工具和权限

新增 Assistant 配置字段 `acceptsAssistantCalls: Boolean = false`，配置页提供明确开关。字段属于 Assistant，而非会话/工具调用设置；新增字段有默认值以兼容旧 JSON/备份，沿现有 Assistant 序列化路径传递。新增 strings 按 app i18n 指引覆盖资源。

每轮工具集合按 `ChatService.runTurn()` → `ChatRequestFactory.createContext(settings, conversation, delegationContext/gateway)` → `ChatToolFactory.createTools(settings, assistant, ..., gateway/source context)` → `LocalTools` 构建。当前 `LocalTools` 不读取当前 Settings 来构造动态委托列表；由 `ChatToolFactory`/`LocalTools` 使用显式传入的本轮 Settings、source assistant 和 request-scoped gateway 构造描述与闭包。目标列表为当前 Settings 中 `acceptsAssistantCalls=true` 且不是源助手的项。目标委托 generation 传 `delegationAllowed=false` 并在创建工具时明确过滤委托工具。该可信运行时标志与可选的消息审计 marker 分离。工具参数是 `assistant_name`（不是 UUID），`message`，可选 `conversation_id`，`create_new` 默认 **true**。若 `create_new=true`，拒绝同时传 `conversation_id`；若为 false，必须传属于目标助手的会话 ID，不猜测“最近会话”。

描述中的名称只是提示，不是授权。名称匹配采用精确、大小写敏感匹配；执行时重新读取最新 Settings，先对全体助手按名称解析：零匹配返回 `unknown_assistant`，重名（无论是否允许调用）返回 `ambiguous_assistant_name`；唯一项再检查 `acceptsAssistantCalls` 与非源助手条件，失败返回 `assistant_not_allowed`。描述随每轮当前 Settings 动态列出 allowed assistants，但执行必须再次检查最新权限。若 Assistant 改名，旧名不再解析，新名仅在下一轮工具描述体现；权限撤销立即阻止已创建工具的后续执行。目标会话还须校验 assistantId。

工具本地选项经 `Assistant.localTools` → `LocalToolOption` → `ChatToolFactory.createTools()` → `LocalTools.getTools()` 注册。当前 `ChatToolFactory` 只向 `LocalTools.getTools(options)` 提供 options；需扩展本轮调用参数，使 Settings/source Assistant/gateway 显式传入，不能描述成 LocalTools 已有读取 Settings 并动态构造描述的能力。

### 协调服务和 DI

禁止把依赖 `ChatService` 的 Gateway 作为 `LocalTools` / `ChatToolFactory` 的构造器依赖：AppModule 当前分别构造 `LocalTools`、`ChatToolFactory`、`ChatRequestFactory`、`ChatService`，调用方向为 `ChatService → ChatRequestFactory → ChatToolFactory → LocalTools`，构造器注入回边会形成环。

采用普通运行时参数传递而非 Koin/service locator：Gateway 由 ChatService 实现，或在当前 `runTurn()` 创建 adapter；调用 `requestFactory.createContext(settings, conversation, delegationContext/gateway)` 时传入，再由 `ChatRequestFactory` 传给 `ChatToolFactory.createTools(settings, assistant, ..., gateway/source context)` 和 `LocalTools`，执行闭包捕获该 request-scoped 接口以及源 assistant/conversation ID。目标 generation 创建工具时传 `delegationAllowed=false` 并过滤委托工具。接口只暴露校验、提交、等待 request 结果所需操作。不得注入依赖 ChatService 的 Coordinator，也不得在构造时 `get<ChatService>()`。编译/DI smoke 可在现有测试设施适用时验证；不把单独的 Koin graph resolution test 规定为必做。

### 请求级结果关联

目前 `enqueueVoiceMessage()` 已将 `CompletableDeferred<String?>` 放在各 `QueuedMessage`，`dispatchNextQueuedMessage()` 按会话串行派发；`sendQueuedMessage()` 在该队列项被取出并开始处理后、追加用户消息前记录 `previousIds`，结束时把所有新增 assistant 消息 `joinToString`。这只是一种 voice 回复采集方式，不是可靠的委托 request 关联 API。不得直接沿用 previousIds 拼接所有新增消息：同一 turn 可能包含工具/检查点/多条 assistant 消息，排队和审批续跑也会混淆结果。

每个 queued delegation item 有 `requestId` 与唯一 USER message/node ID；该 ID 在入队前/入队时分配并随队列项可靠保存。dispatch 开始前或开始时建立该 item 与目标 assistant reply node ID 的显式关联。当前 `runTurn()` 内部按 history/regenerate 分支逻辑确定 `replyNodeId`，MessageUpdated 事件以该 ID 回写，最终只返回 `TurnResult.Finished(reason)` 或 `Failed`；实现须扩展返回结果或回调，将明确 reply node ID 提供给 request 关联。只从该 reply node 对应的最终已完成 ASSISTANT message 取文本，不依赖流式 MessageUpdated，也不按全局新增消息或 previousIds 拼接。只有 `Finished` 且 `reason !is AwaitingApproval` 才可成功；`Failed`、缺失/未完成目标消息、空文本均须有明确失败语义。

排队时即登记 requestId 与 USER 节点身份，dispatch 时关联 reply node；排队项依次执行仍只按自身 ID 关联。首版遇 `AwaitingApproval` 立即以 `target_approval_required` 终结委托 request，目标会话保留等待用户审批状态；该 request 不跨审批续跑，也不声称审批续跑与原 waiter 关联。审批通过后现有路径可继续目标会话生成，但其结果不是该委托 request 的结果。所有结果经单一终态/Deferred complete-once 机制竞争，后到事件 no-op。

### 会话、取消与失败语义

目标会话由 ChatService 所有。新建会话必须显式指定目标 assistantId 并使用其 preset/config；现有私有 `newConversation(conversationId)` 取当前全局助手，不能复用为目标创建路径。遵守 `ConversationSession` 单一内存状态及 `update {}` + `flush()` 写入规则。目标生成在目标 session job 中推进，源助手只等待独立 Deferred，不持有源 session monitor、Mutex 或 DB 写锁。

取消分阶段处理：若队列项尚未派发，可按 queued item ID 移除并以明确失败完成 waiter。目标生成一旦开始，源侧取消/timeout 只停止等待，目标生成继续并正常保存；迟到答案不回传。目标 conversation 显式删除是独立生命周期动作：现有 `deleteConversation()` 调用 `ConversationSession.replace()`，后者取消并 join 会话任务，因此删除会取消该目标 session generation。关联 waiter 由删除/会话替换路径以 `target_conversation_deleted` 终结；不得声称目标删除时 generation 仍继续。删除与生成完成并发时，由 session 生命周期和单一终态/Deferred complete-once 决定唯一结果。

默认策略：源工具 `needsApproval=true`，由现有工具审批机制审批；源工具被拒绝则不入目标队列。目标助手 `AwaitingApproval` 时立即以 `target_approval_required` 终结委托，不等待目标审批/续跑，目标会话仍保留待审批状态；只测试审批状态不会错误地完成委托，不测试/要求审批续跑与原 waiter 关联。目标生成失败、目标不存在/未授权、超时均返回明确工具错误。timeout 到期时若仍未派发则按 queue item ID 移除并失败；若已开始，仅停止源侧等待，目标生成继续，迟到答复不回传。目标 conversation 删除/会话替换则取消目标 generation，并终结关联 waiter 为 `target_conversation_deleted`。进程重启时运行时 Deferred 不恢复，不自动重放或重复发送。以上为已确定首版契约。

### 委托标记、递归和 Web

可信递归防护使用每次 generation 显式传递的 `delegationAllowed=false` 运行时标志，创建工具时过滤委托工具；持久化 USER marker 仅用于审计，不作为权限依据。AI module 的 `UIMessage` 变更需同步 TypeScript 内部 `UIMessage` mirror type；Web API/SSE 使用独立的 `MessageDto` 显式白名单映射，不暴露 marker，验收不要求 DTO marker roundtrip。旧 JSON 字段默认 false 以真实 serializer test 验收。

## KeyDecisions

- 用户已决定：`create_new=true`；参数使用 `assistant_name`；工具描述动态列出接受调用的助手，执行时仍重新验证许可。
- assistant_name 精确、大小写敏感匹配；全体助手同名即拒绝歧义；未知、拒绝调用、重名均明确报错。发起 assistant 不出现在候选列表且不能作为目标。
- `acceptsAssistantCalls` 是 Assistant 配置字段，默认 false；旧 JSON/备份缺字段按 false 兼容。描述的来源为最新 Settings assistants 过滤，不是模型工具文本自认证。
- 现有 per-item Deferred 可复用，但 `QueuedMessage` 当前只有 id/parts/answer/edit/reply；无 requestId、USER/reply node 关联和最终结果。voice 当前用 previousIds 收集所有新增 assistant 文本，不满足本需求。
- `runTurn()` 内部决定 replyNodeId、以该 ID upsert MessageUpdated，并只返回 `Finished(reason)`/`Failed`；需显式暴露 node ID 关联/最终消息结果。审批 resume 由 `handleToolApproval()` 再跑 `runTurn()`，但首版委托在 AwaitingApproval 即终结，不跨审批关联。
- DI 不采用 Coordinator → ChatService 的 Koin 构造注入；ChatService 在每轮请求构建时向下传 Gateway 参数，工具执行闭包捕获本轮 Gateway。
- 源工具需审批；目标审批不挂起 waiter，报 `target_approval_required`；取消已开始生成只停止等待、生成继续；超时同理；进程重启不恢复并终结 interrupted。
- 可持久化审计 marker 非递归可信凭据；TS 内部 `UIMessage` mirror type 与 Kotlin 对齐，但 Web API/SSE 的 `MessageDto` 不公开来源元数据，也不要求 DTO marker roundtrip。可信深度只在运行时队列/生成上下文。
- 附件不在首版支持范围，仅文本请求。

## Impact

### 已核实调用点

- `app/.../data/model/Assistant.kt`：Assistant 为 Serializable，新增 `acceptsAssistantCalls=false`。
- `LocalToolOption.kt` / `LocalTools.kt` / `ChatToolFactory.kt`：工具 option、动态 description、执行闭包及 Gateway 注入。
- `ChatService.kt`：当前 `newConversation()` 取全局助手；`enqueueVoiceMessage()` 挂独立 Deferred；`dispatchNextQueuedMessage()` 会话串行；`sendQueuedMessage()` 以 previousIds 拼接新增 assistant 文本；`runTurn()` 以 `Finished(reason)`/`Failed` 返回；审批续跑单独调用 `runTurn()`。
- `MessageQueue.kt`：QueuedMessage 目前只含 id/parts/answer/isEditing/reply；remove 仅处理未派发项，reply null 完成；pause 会失败排队 waiter。需增加 requestId、目标 USER node 关联/结果回调及 exactly-once 终态。
- `AppModule.kt`：ChatService → ChatRequestFactory → ChatToolFactory → LocalTools；Gateway 通过 ChatService 每轮构建请求时的参数传递，不加入反向 Koin 构造依赖。
- `AssistantLocalToolPage.kt`、`values*/strings.xml`：工具 opt-in 和 Assistant 接受委托设置 UI/文案。
- 备份/旧 JSON：Assistant 新字段缺失默认 false；实现阶段核实 Settings JSON codec 兼容测试。
- `WebDto.kt`：MessageDto 显式字段映射，不直接透出 UIMessage 新字段；marker 默认不加到 DTO。

### 相似能力

- `enqueueVoiceMessage()` 是 per-item Deferred 样板，但结果提取不安全，不能复制 previousIds 拼接逻辑。
- `createConversationTools()` 是会话查阅，不是跨助手委托；`AskUserTool` 可参考审批管线；`sendMessage()` 无结果关联。

### 风险

| 风险 | 等级 | 控制 |
|---|---|---|
| 名称歧义或动态权限变更越权 | 高 | 全局重名拒绝；执行时最新 Settings 唯一解析、授权及会话归属重验 |
| 请求结果关联错误/等待器泄漏 | 高 | requestId→唯一 USER node→turn final assistant；统一 exactly-once 终态 |
| 同目标排队/结果错配 | 高 | 每项 requestId + USER node + reply node 显式关联；审批立即终结为 target_approval_required |
| DI 环/跨会话死锁 | 高 | Gateway 运行时参数由 ChatService 向工具工厂下传，不做 Koin 回注；await 不持源锁 |
| 取消误杀会话级 job | 高 | 源取消/timeout 不取消已开始目标 job；目标会话删除另由 replace 取消 generation 并终结 waiter |
| Web 泄漏内部元数据/递归伪造 | 中 | WebDto 白名单不映射 marker；递归可信 context 不用持久化 marker |

## TaskDecomposition

| task_id | name | description | files (dep / acceptance) |
|---|---|---|---|
| T1 | Assistant 权限字段与设置 | 新增 `acceptsAssistantCalls=false` Assistant 字段、配置 UI、字符串及旧 JSON/备份默认兼容。 | `Assistant.kt`; Assistant settings page; `values*/strings.xml`; Settings/backup tests (dep: none; accept: 旧 JSON 解码字段为 false；可保存/读取开关) |
| T2 | 动态名称描述与唯一安全解析 | 工具参数采用 `assistant_name`；由当前 Settings 中接受调用且非发起者的助手动态生成描述；精确匹配、全局重名歧义拒绝、运行时最新权限和会话归属校验。 | `LocalToolOption.kt`; `LocalTools.kt`; `ChatToolFactory.kt`; new tool; (dep: T1; accept: 默认 `create_new=true`；可调用列表动态更新；未知/拒绝/重名/源助手均明确拒绝；唯一允许目标通过) |
| T3 | 请求关联队列与完成协议 | 扩展队列项 requestId/唯一 USER node/reply node 关联；`runTurn` 暴露其 reply node 与终态；审批立即以 target_approval_required 终结；定义 complete-once。 | `MessageQueue.kt`; `ChatService.kt`; session APIs (dep: none; accept: 多排队项精确关联；只从明确完成 reply node 取文本；审批不错误完成委托；不使用 previousIds) |
| T4 | 目标会话协调与 DI | 新建显式 assistantId 会话；普通参数向下传 Gateway/source context；目标 generation 的 delegationAllowed=false；不新增反向 Koin 依赖；源端不持锁等待；处理取消、删除和重启语义。 | `ChatService.kt`; `ChatRequestFactory.kt`; `ChatToolFactory.kt`; `LocalTools.kt`; new delegation tool source (dep: T2,T3; accept: 目标 preset/config；目标删除 replace 取消其 generation 并终结 waiter；源取消不取消已开始目标 generation) |
| T5 | Web DTO 隐私边界 | 如有审计 marker，保持 WebDto 显式白名单不映射内部来源数据；不要求 TS 字段或 marker roundtrip。 | `WebDto.kt`; optional marker model (dep: T3; accept: DTO/SSE 不泄漏 source/request metadata；Web既有消息兼容) |
| T6 | 测试与验证 | 覆盖权限、动态描述、Assistant 旧 JSON 默认值、精确队列结果、审批不错误完成、取消/删除生命周期及 Web DTO 隐私；执行与改动相关的验证。DI smoke 仅在现有测试设施适用时执行。 | app tests, AI tests, web-ui checks if affected (dep: T1–T5; accept: 相关测试通过) |

## Review / 验收计划

- 名称解析测试：唯一允许、未知、拒绝调用、重复名（含一允许一拒绝）、自身目标、改名及工具创建后撤权；均确保执行端最新检查。
- schema 测试：参数 `assistant_name`，`create_new` 默认 true；新建与 conversation_id 冲突明确报错；复用时会话必须属于解析助手。
- Assistant 字段旧 JSON/备份兼容，缺省 false；配置 UI 可切换；工具描述从最新 Settings 动态构造。
- 每项 requestId 与唯一 USER node、明确 reply node 关联；仅从 Finished 且非 AwaitingApproval 对应的最终已完成 ASSISTANT message 提取文本；流式更新不算结果；失败/空答复明确失败。多排队项不串线，不使用 previousIds。
- AwaitingApproval 立即以 target_approval_required 终结委托；目标会话留在审批状态，委托不跨审批续跑；测试审批不错误地完成委托。
- 未派发队列项按 item ID 移除并失败；已开始时源侧停止等待而目标生成继续；目标删除经 replace 取消 generation，waiter 以 target_conversation_deleted 终结；并发仅一个终态。
- 重启待处理调用 interrupted、不自动重发；Web DTO 不公开内部 marker；递归防护不信任持久化数据。

### 验证命令（实现阶段执行；本轮文档审阅不执行）

```bash
./gradlew :ai:test :app:test --no-daemon
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:lint --no-daemon
(cd web-ui && pnpm run typecheck && pnpm run fmt:check)
```

实现阶段统一七关和命令以 `REVIEW_CHECKLIST.md` 为准。不强制规定 pnpm 安装/包管理器细节，不为每类异常设计独立状态机；只保留实现必需的错误结果与 complete-once 终态约束。

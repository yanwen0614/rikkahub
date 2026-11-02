# Harness Progress：跨助手会话委托

## Phase

- 当前阶段：**Phase 2.5 — Overall Quality Gate 完成**
- 状态：实现已完成；Quality Gate 为 PASS WITH BASELINE ISSUES。无本次变更引入的阻塞问题。
- Generator / per-task Quality Gate：T1、T3、T2/T4 均完成；整体 Gate 完成。
- 源码修改：已完成 T1–T5。
- 测试：`ai:test`、`app:test`、Android 编译/资源处理、web-ui typecheck 通过；lint/fmt 基线问题见下文。
- Commit：未创建；本次未执行提交。

## Task List

| task_id | 子任务 | 状态 | 依赖 | 验收概要 | 修复轮次 |
|---|---|---|---|---|---|
| T1 | Assistant接受调用字段与设置 | completed | — | Assistant字段默认false；设置UI/strings；旧JSON和备份缺省兼容 | 0 |
| T2 | 动态工具描述与名称安全解析 | completed | T1 | `assistant_name`；当前Settings allow-list动态构建；`create_new=true`；未知/拒绝/重名/自身目标明确拒绝 | 0 |
| T3 | 请求级队列结果关联 | completed | — | 每项 requestId→唯一USER node→明确 reply node；`runTurn` 返回/回调 reply node；审批立即 `target_approval_required`，不错误完成委托 | 1 |
| T4 | 委托协调、目标会话与DI | completed | T2,T3 | 显式目标assistant创建；普通参数下传Gateway/source context；目标 generation `delegationAllowed=false`；目标删除按 session replace 终结 waiter | 1 |
| T5 | Web DTO 隐私边界 | completed | T3 | Kotlin/TS内部UIMessage marker类型对齐；API `MessageDto`不映射marker/source metadata | 0 |
| T6 | 自动化测试与验证 | completed | T1–T5 | 动态权限/名称、JSON兼容、协调器/队列、审批/取消/Web覆盖 | 0 |

## 已核实的实现边界

- `Assistant` 当前无“接受其他助手调用”字段；新增字段需默认 false。
- `MessageQueue.QueuedMessage` 当前有 per-item `reply` Deferred，但没有 requestId 或目标 USER 节点关联。
- `ChatService.sendQueuedMessage()` 当前在队列项开始处理后、追加 USER 消息前记录 `previousIds`，结束时汇总之后所有新增 ASSISTANT 文本；委托不得复用此采集方式。
- `runTurn()` 根据 history/regenerate 路径生成 `replyNodeId`，`MessageUpdated` 以该 ID 写回，最终仅返回 `Finished(reason)`/`Failed`；实现须显式返回/回调 node ID 与完成消息。结果只取该明确 reply node 的最终已完成 ASSISTANT message，流式更新不作为答复。
- 队列按目标 session 串行；`AwaitingApproval` 时 turn 返回 Finished(AwaitingApproval)，审批通过后 `handleToolApproval()` 另行 `runTurn()`。首版委托立即终结为 `target_approval_required`，不跨审批续跑，也不声称原 waiter 与审批续跑关联。
- `MessageQueue.remove()` 只移除队列中的待处理项；新增委托移除需按 queued item ID，未派发可移除并失败 waiter。目标 generation 开始后源取消/timeout 只停止等待，目标继续并保存；显式删除则通过 `deleteConversation()`→`ConversationSession.replace()` 取消并 join session jobs，删除路径应以 `target_conversation_deleted` 终结 waiter。
- DI 现有方向为 `ChatService → ChatRequestFactory → ChatToolFactory → LocalTools`（AppModule 分别构造这些对象）。Gateway 由 ChatService 实现或当前 `runTurn()` 创建 adapter，作为普通参数沿 `createContext(settings, conversation, delegationContext/gateway)`、`createTools(settings, assistant, ..., gateway/source context)` 向下传递；LocalTools/ChatToolFactory 不持有反向构造依赖，不用 service locator。工具描述由本轮 Settings 显式构造；目标 generation 创建工具时过滤 delegation tool。
- WebDto 的 `MessageDto` 显式字段映射，因此内部 marker 默认不进入 Web API/SSE。

## 默认策略与确认状态

1. 已确定：`create_new` 默认 true；工具参数是 `assistant_name`；动态描述只列接受调用的目标，运行时仍重新校验。
2. 名称采用精确大小写敏感匹配；全体助手重名均拒绝歧义；未知/拒绝调用/重名明确错误；发起助手排除且不可自委托。
3. 源工具默认 `needsApproval=true`；目标助手遇审批立即以 `target_approval_required` 终结委托，不等待审批续跑，目标会话仍可由用户处理；验收仅要求审批不错误完成委托。
4. 未派发队列项按 item ID 移除并失败；已开始只停止源端等待，目标生成继续、迟到答复不回传。目标 conversation 删除由 replace 取消该会话 generation，并终结关联 waiter 为 `target_conversation_deleted`。
5. 失败/超时/会话删除各返回明确错误；并发以 session lifecycle 与 Deferred complete-once 定终态；重启中断不自动恢复或重发。
6. 上述是首版安全默认，不需另行阻塞确认；仅若用户要求改变策略才需确认。

## Acceptance / Validation Plan

- 具体验收门见 `REVIEW_CHECKLIST.md`；架构及调用链见 `PLAN.md`。
- 实际执行的验证命令：

```bash
./gradlew :ai:test :app:test --no-daemon
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:processDebugResources --no-daemon
./gradlew :app:lint --no-daemon
(cd web-ui && pnpm run typecheck && pnpm run fmt:check)
```

- 通过：最终修改后重跑 `:ai:test :app:test`、`:app:compileDebugKotlin`、相关 delegation focused tests、`:app:processDebugResources`、`pnpm run typecheck`、`git diff --check`。
- 基线 known issue：`:app:lint` 在未修改的 `StatsPage.kt:290` 报 `NonObservableLocale`（全量 lint 汇总 65 errors / 340 warnings / 1 hint）；`pnpm run fmt:check` 报告 19 个未修改文件格式问题。两者均不在本次 diff，未修改无关文件。
- TDD RED→GREEN 由各任务 Generator 记录：Assistant 缺省字段序列化、MessageQueue 请求协议、assistant 名称解析、coordinator 新建/复用路径。
- T4 测试通过 fake runtime 覆盖生产 Coordinator 和目标会话构造 helper；未搭建完整 ChatService + fake-agent 端到端 fixture。Quality Gate 将其判为非阻塞残余集成风险，而非已发现缺陷。

验证关统一为七关，见 `REVIEW_CHECKLIST.md`；旧 JSON `acceptsAssistantCalls` 缺省 false 必须由真实 serializer tests 验收。DI graph smoke 仅在现有测试设施适用时执行，不将专门的 Koin graph resolution test 设为必需。无需强制定义 pnpm 安装/包管理器细节，也不为每类异常扩建独立状态机；只保留必要错误语义与 complete-once。

## Repair Rounds

- 设计修订轮次：2；设计复审 PASS。T1/T3/T2-T4 每任务 Quality Gate 均通过（T3 PASS WITH FOLLOW-UP 后由 T4 coordinator tests覆盖）；Overall Phase 2.5 为 PASS WITH BASELINE ISSUES。
- 实现涉及 Assistant 配置/UI、动态委托工具、目标会话协调、消息队列结果关联、内部 marker/DTO 隐私和测试。完整文件清单以当前 git diff/status 为准。
- 未创建 commit；无关 lint/fmt 基线项保留为 follow-up。

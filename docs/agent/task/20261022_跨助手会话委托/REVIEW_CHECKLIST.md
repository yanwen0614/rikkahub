# Review Checklist：跨助手会话委托

## 需求覆盖

- [ ] `create_new` 默认 true；true 时不允许指定 conversation_id；false 时必须指定目标助手所属会话。
- [ ] 工具参数名为 `assistant_name`，不使用 `assistant_id`；描述由当前 Settings 动态生成。
- [ ] Assistant 增加接受其他助手调用字段，默认 false；旧 JSON/备份缺字段兼容 false；设置 UI/strings 覆盖。
- [ ] 描述候选为当前 Settings 中 `acceptsAssistantCalls` 的助手且排除发起者；运行时必须重新校验。
- [ ] 名称精确、大小写敏感匹配；未知/拒绝调用/重名均明确失败；同名不任意挑选；唯一允许目标及目标会话归属双重校验。
- [ ] 请求协议是 `requestId → 唯一目标 USER 节点 → 对应队列项 turn 的最终已完成 assistant 消息`；不可用 previousIds 拼接所有新增 assistant 消息。
- [ ] 每个 queued item 有 requestId + 唯一 USER message/node ID，dispatch 时建立目标 reply node ID 关联；`runTurn` 需返回/回调 reply node ID。仅从明确 reply node 的最终已完成 ASSISTANT message 取文本；仅 Finished 且非 AwaitingApproval 成功，流式更新不作为结果；失败/缺失/空文本有明确语义。
- [ ] AwaitingApproval 立即以 `target_approval_required` 终结委托，目标会话保留审批状态；委托 request 不跨审批续跑，不要求/声称审批续跑关联原 waiter；测试审批不错误完成委托。
- [ ] 各终态由 complete-once 处理；目标删除/替换、失败、取消、timeout 等不会重复完成 waiter。
- [ ] 已开始的目标生成不因源取消/超时而取消会话级 job；停止等待，目标生成继续、迟到回复仅留目标会话。
- [ ] TypeScript 内部 `UIMessage` mirror type 与 Kotlin 对齐；Web API/SSE 使用独立显式 `MessageDto` 且不公开内部 marker；不要求 DTO roundtrip marker。递归防护依赖可信运行时 context，不依赖持久化 marker。

## 影响分析核对

- [ ] `Assistant.kt` 字段默认 false，设置存储/旧备份 JSON兼容；UI/资源文案存在。
- [ ] 每轮 `ChatToolFactory`/`LocalTools` 通过显式传入的当前 Settings/source assistant 构造动态描述；LocalTools 不被假定已有读取 Settings 的能力。
- [ ] 执行闭包重新读取最新设置并解析唯一允许目标；拒绝名称重复，包括允许/拒绝助手间的重名；自身不在可调用目标。
- [ ] 新建目标会话显式指定 assistantId/preset/config，不用当前全局助手；会话更新仍由 ConversationSession + flush 管理。
- [ ] MessageQueue 当前不含 requestId/node ID/精确终态接口；设计新增能力而非假定已存在。
- [ ] ChatService 的 voice Deferred 在队列项开始处理后记录 previousIds 并收集之后所有新增 assistant 消息；委托不得复用该结果采集方式。`runTurn` 当前内部确定 replyNodeId、事件按该 ID 写回，但仅返回 Finished(reason)/Failed，需提供显式 node 关联结果。
- [ ] DI 无环方案具体为 ChatService 在每轮请求构建时以窄接口参数向下传递 Gateway；不把依赖 ChatService 的对象构造注入 LocalTools/ChatToolFactory，不使用 Koin 回边或延迟 service locator。
- [ ] 不持源会话锁等待目标；目标会话串行约束不变。
- [ ] WebDto 的 MessageDto 显式白名单映射，不添加内部 marker/source 字段；TS 内部 UIMessage mirror type 同步 marker，但不视为 API/SSE DTO，也不要求 DTO roundtrip。

## 重复检测 / 可复用能力

- [ ] 复用 `enqueueVoiceMessage()` 的 per-item Deferred 思路，但不复用其 previousIds 拼接结果逻辑。
- [ ] 与会话查阅工具、AskUser 审批、fire-and-forget `sendMessage()` 的能力边界清楚。
- [ ] 不新建平行会话 repository/生命周期；所有目标会话经 ChatService。
- [ ] 首版只接收文本，schema/描述不暗示附件/多模态。

## 七关卡（实现阶段验收）

1. **需求/接口关**：`assistant_name`、`create_new=true` 默认、错误分类、timeout/文本限制确定。
2. **权限/动态描述关**：Assistant 默认 false；描述由当前 Settings allow-list 构造；运行时唯一解析、拒绝歧义/未知/未授权/自身目标及目标会话不匹配。
3. **关联/生命周期关**：requestId→唯一 USER message/node→明确 reply node→最终已完成 ASSISTANT message；AwaitingApproval 以 target_approval_required 终结且不续跑原 waiter；失败/空答复、删除、取消/timeout 有语义，complete-once。
4. **队列/并发关**：同目标排队逐项匹配；不持锁 await；未开始可移除单项，已开始只停止等待而不取消 session job。
5. **DI/递归关**：Gateway 作为每轮请求参数从 ChatService 向下传给工具工厂，不进入 Koin 反向构造依赖；递归防护来自可信 generation context，不能被 marker 伪造绕过。
6. **存储/UI/Web关**：Assistant旧 JSON默认兼容、设置 UI/strings；TS 内部 UIMessage mirror type 对齐 Kotlin；Web API/SSE 的显式 MessageDto 不公开内部 marker/source，不要求 DTO roundtrip。
7. **验证关**：执行 `./gradlew :ai:test :app:test --no-daemon`、`./gradlew :app:compileDebugKotlin --no-daemon`、`./gradlew :app:lint --no-daemon` 及 `(cd web-ui && pnpm run typecheck && pnpm run fmt:check)`；按实际改动覆盖验证并检查 diff。旧 Assistant JSON 默认 false 由真实 serializer test 验收；DI smoke 仅在现有测试设施支持时做。无需为文档/实现强制定义 pnpm 安装细节。

## 默认策略 / 仍需确认

- 源委托工具 `needsApproval=true`；源审批拒绝即不入队。
- 目标 turn 遇 `AwaitingApproval` 时立即以 `target_approval_required` 终止委托等待，不挂起等目标审批；目标会话保留待审批状态。
- 队列项未派发时取消/超时可按 item ID 移除并失败；目标生成开始后源侧只停止等待，目标继续生成，晚结果不回源。目标会话删除是独立动作，会按 replace 取消目标 session job。
- 进程重启后未完成 waiter 作为 interrupted，不自动恢复/重发。
- 上述为首版安全默认，用户无需在实现前再次确认。若产品要变更审批/重启语义，应另行决策。

## 风险检查

- [ ] 高：动态名称描述被当成授权，或重名误解析；执行端必须最新设置唯一解析。
- [ ] 高：排队结果错配或 Deferred 重复完成；验证 USER/reply node 关联和 complete-once。审批只验 AwaitingApproval 不错误成功，不验跨审批关联。
- [ ] 高：DI 回边/跨会话死锁；Gateway 不形成 Koin 回边，await 不持源锁。
- [ ] 高：源取消误杀共享会话级生成；目标开始后源侧只停止等待。显式删除由 `deleteConversation`→`ConversationSession.replace()` 取消并 join 目标任务，删除路径终结 waiter 为 `target_conversation_deleted`。
- [ ] 中：Assistant 新字段旧 JSON/备份 decode 失败；缺省 false 测试。
- [ ] 中：Web 内部来源数据泄漏；MessageDto 映射白名单检查。

## 文档阶段状态

- [x] 已核对根/app/ai/web-ui 指引及 Assistant、ChatService、MessageQueue、ChatToolFactory、LocalTools、AppModule、WebDto 关键实现。
- [x] 已区分现有 Deferred 能力与尚未存在的 request/node/final-result 关联 API。
- [x] 已记录名称动态描述与运行时授权、DI方向、request/reply node 关联、审批立即终结及删除生命周期语义。
- [x] 本轮仅编辑三份设计文档；未改源码、未执行测试、未进入实现、未提交。

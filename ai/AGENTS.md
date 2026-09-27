# ai Module

AI SDK 抽象层：跨 provider 的消息模型与无状态 Provider 接口（OpenAI/Google/Anthropic 等）。
注意：`ai/README.md` 内容有误（MNN），不要引用。

## Message 模型（`ui/`）

- **UIMessage**（`ui/Message.kt`）：role（SYSTEM/USER/ASSISTANT/TOOL，`core/MessageRole`）、
  parts、annotations、usage（`core/TokenUsage`）、modelId；支持 chunk 合并流式更新；
  `limitContext()` 阶梯截断保提示词缓存，`migrateToolMessages()` 合并旧 TOOL 消息。
- **UIMessagePart**（`ui/UIMessagePart.kt`）：Text/Image/Video/Audio/Document/
  Reasoning/Tool/ServerTool；ToolCall/ToolResult/Search 已废弃，只做迁移兼容。

## 结构

- `provider/`：`Provider<T : ProviderSetting>` 无状态接口（generateText/streamText/listModels），
  各厂商实现在 `provider/providers/`（openai/google/claude…），流式解码在 `provider/stream/`。
- `registry/`：ModelRegistry（模型能力：vision/tool/reasoning，按 token 规则匹配）。
- `core/`：MessageRole、TokenUsage、Tool、Reasoning 等基础类型。
- `util/`：SSE、KeyRoulette（key 轮询）、Request/Json 等。

## 约束

- 改 `UIMessage`/`UIMessagePart` 时必须同步更新 `web-ui` 对应前端类型，保持两端对齐。

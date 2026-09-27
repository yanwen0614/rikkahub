# app Module

主应用模块：Compose UI、ViewModel、聊天核心逻辑（Material 3）。

## Concepts

- **Assistant**（`data/model/Assistant.kt`）：助手配置：system prompt、temperature/topP、
  contextMessageLimit（0=不限，阶梯截断）、custom headers/bodies、tools、memory、
  regex、prompt 注入（mode/lorebook），各助手相互隔离。
- **Conversation**（`data/model/Conversation.kt`）：持久化对话线程：title、pin、
  chatSuggestions、customSystemPrompt、mode/lorebook 绑定、workspaceCwd、folderId；
  `currentMessages` 取各节点选中消息。
- **MessageNode**：分支树容器，持多条 UIMessage 备选 + `selectIndex`；
  regenerate 追加新分支并切换选中，形成树状结构（`updateCurrentMessages`）。

## Transformer 管线（`data/ai/transformers/Transformer.kt`）

- `InputMessageTransformer`：发送前处理；`OutputMessageTransformer`：接收后处理。
- 6 种常用：TemplateTransformer（Pebble 模板，`{{ message }}`/时间变量）、
  ThinkTagTransformer（`<think>`→reasoning）、RegexOutputTransformer、
  DocumentAsPromptTransformer、Base64ImageToLocalFileTransformer、OcrTransformer。
- `visualTransform()`：流式中仅供 UI 展示，不改真实消息；
  `onGenerationFinish()`：生成结束后最终处理。

## i18n

- 字符串在 `app/src/main/res/values*/strings.xml`，Compose 用 `stringResource(R.string.key)`。
- 页面 key 加 page 前缀（如 `setting_page_*`）。
- 用户未明确要求本地化时默认不做 l10n，直接写 `Text("...")`。

## Storage & DI

- Room（`data/db/AppDatabase.kt`，v25）：Conversation/MessageNode/Memory/
  GenMedia/ManagedFile/Favorite/Workspace/Folder 共 8 entities；手写 migration
  放 `data/db/migrations/`，其余用 AutoMigration。
- DI（Koin，`di/`）：AppModule / DataSourceModule / RepositoryModule / ViewModelModule。

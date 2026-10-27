# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material 3 Expressive (MD3 Expressive) design language.

## Design Language

The UI follows Material 3 Expressive. New and reworked screens should match it rather than the older baseline
Material 3 look (cards with dividers, plain forms).

- The app theme is `MaterialExpressiveTheme` (app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt); prefer the
  expressive variants of components, shapes (`MaterialShapes`), emphasized typography (e.g. `titleSmallEmphasized`)
  and motion from `androidx.compose.material3` over hand-rolled equivalents.
- Settings-style and form pages use segmented groups via `CardGroup` (
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt) instead of a card with dividers.
- Favor larger corner radii, filled/tonal containers, and whole-row click targets.

## Build, Test, and Development Commands

```bash
./gradlew assembleGithubDebug    # 构建 GitHub 渠道的 Debug APK
./gradlew assembleGithubRelease  # 构建 GitHub Release / 每日构建用的 APK
./gradlew bundlePlayRelease      # 构建上架 Google Play 的 AAB
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
./gradlew spotlessApply          # 用 ktlint 格式化相对 origin/master 改动过的 Kotlin 文件
./gradlew spotlessCheck          # 只检查格式不改文件
```

App 有一个 `distribution` 维度的 product flavor：`github`（默认）和 `play`，共用同一个 applicationId。
渠道差异用 `BuildConfig.IS_PLAY_STORE` 判断（如 Play 版不检查更新、不显示更新提醒）。

## Module Structure

- **app**: App UI + data (Assistant/Conversation/Transformer) -> `app/AGENTS.md`
- **ai**: AI SDK + UIMessage/providers/registry -> `ai/AGENTS.md`
- **search**: Search services + Key pool -> `search/AGENTS.md`
- **highlight**: Code highlighting + fixtures -> `highlight/AGENTS.md`
- **speech**: TTS/ASR -> `speech/AGENTS.md`
- **workspace**: Sandbox FS + shell tools -> `workspace/AGENTS.md`
- **document**: PDF/DOCX/PPTX/EPUB parsing -> `document/AGENTS.md`
- **web**: Ktor static server (build output from web-ui) -> `web-ui/AGENTS.md` (Build Process)
- **web-ui**: Embedded React SPA -> `web-ui/AGENTS.md`
- **locale-tui**: strings.xml TUI tool -> `locale-tui/AGENTS.md`
- **common/material3/videogen/trace-cli/oauth/build-logic**: small shared modules, no separate guide.
- **ui**: Reusable Compose UI components that do not depend on app logic

## Git / Branch / Upstream Sync（fork私用，不提PR）

- Remotes: `origin = git@github.com:yanwen0614/rikkahub.git` (sole push target, SSH);
  `upstream = https://github.com/rikkahub/rikkahub.git` (official read-only, never push).
- Work branch: daily use `dev/refactor` (tracks `origin/dev/refactor`).
- `master`: mirror only. Sync: `git checkout master && git fetch upstream && git merge --ff-only upstream/master && git push origin master`. Non-FF -> stop, investigate.
- Sync upstream into work (merge, keep history): 分步执行，避免 fetch 空跑导致漏合并：
  ```bash
  git checkout dev/refactor
  git fetch upstream
  git rev-list --count HEAD..upstream/master   # 预期 >0；若为 0 警吀可能上游未刷新
  git merge upstream/master
  git merge-base --is-ancestor upstream/master HEAD  # 验证：应返回 0 (true)
  git push origin dev/refactor
  ```
  No rebase, no `push upstream`, keep `pull.rebase=false`.
- Daily check: `git rev-list --left-right --count HEAD...upstream/master`, merge first if behind.
- Upstream branches `feat/context-limit、refactor/ai-stream、refactor/chat-service` ignored by default, `git fetch upstream <branch>` on demand.

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings.
  Once a conversation is persisted it also holds a `ConversationConfig` snapshot (chat model, reasoning level, search,
  MCP servers, workspace, skills) taken from the assistant; from then on chat-page changes to those settings stay on
  the conversation, and code should read them through `Settings.getAssistantOf(conversation)` /
  `Settings.getChatModelOf(conversation)` instead of the assistant directly. Once loaded, the in-memory
  `ConversationSession` is the single source of truth: change a conversation through `ChatService` (which applies
  `session.update { latest -> ... }` and then `flush()`), never by saving a whole `Conversation` snapshot or one read
  back from the database; deleting also goes through `ChatService` so a running generation cannot re-insert it. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt,
  app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt,
  app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt)

## Skills

- `.agents/skills/`: `claude-api/gemini-api-dev/gemini-interactions-api` (provider APIs), `find-hugeicons` (Compose icons), `locale-tui-localization` (strings.xml ops), `publish-release` (release flow).

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract ` thinking` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- Add, translate or clean up strings with the `tools/i18n` CLI (Bun); see the `i18n-localization` skill.

# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

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

## Skills

- `.agents/skills/`: `claude-api/gemini-api-dev/gemini-interactions-api` (provider APIs), `find-hugeicons` (Compose icons), `locale-tui-localization` (strings.xml ops), `publish-release` (release flow).

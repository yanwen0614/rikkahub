# highlight 模块说明

## 模块定位
`highlight` 是自研 Kotlin 代码高亮引擎，为 Compose 聊天消息提供与 highlight.js 对齐的词法着色能力。

## 支持语言（30 种，以 `tools/languages.mjs` 为准）
- json（含 json5）、ini、cmake、go、glsl、yaml、bash（含 shell）、dockerfile
- javascript（含 js/jsx/mjs/cjs）、typescript（含 ts/tsx/mts/cts）、xml（含 html/svg 等 10 别名）、css
- dart、java（含 jsp）、kotlin（含 kt/kts）、latex（含 tex）、lua、ruby、powershell
- properties、python、c（含 h）、cpp（含 cc/hpp 等 7 别名）、csharp、sql
- diff（含 patch）、markdown（含 md/mkd）、rust（含 rs）、php、swift
- Kotlin 侧语法定义位于 `src/main/.../highlight/languages/<lang>/`，以此目录为准新增/删除语言。

## Fixture 生成流程
- 真源：`highlight/tools/generate-hljs-fixtures.mjs` + `highlight/tools/languages.mjs`（hljs 11.11.1）。
- 跑法：`cd highlight/tools && npm install && npm run generate`（即 `node generate-hljs-fixtures.mjs`）。
- 输入：`src/test/resources/hljs/<language>/*.txt`（手写用例源码）。
- 输出：同目录同名 `*.tokens` 文件，每行 `scope<TAB>text`，空 scope 表示无高亮。
- 扁平规则与 Kotlin 侧 `TokenEmitter` 对齐：最内层 scope 生效，`language:` 容器透明，同 scope 相邻 token 合并；脚本自带源码重建校验。

## 何时跑脚本
- 新增/删除语言：先改 `languages.mjs` 与 Kotlin 语法目录，再补 `.txt` 用例后跑脚本。
- 升级 `highlight.js` 版本：必须全量重跑并检查 `.tokens` diff。
- 新增/修改 `.txt` 用例后：重跑脚本生成对应 `.tokens`，与代码一起提交。

## 构建 / 测试
- `./gradlew :highlight:assembleDebug`：构建本模块。
- `./gradlew :highlight:testDebugUnitTest`：跑本模块单测（含 hljs fixture 对比 `CodeHighlighterTest`）。
- `./gradlew lint`：全仓 Lint；勿提交未生成的 `.tokens` 缺失用例。

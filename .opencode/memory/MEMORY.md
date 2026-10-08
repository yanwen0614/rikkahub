# Project Memory

## Architecture & Decisions

### ui 模块下沉（通用组件）
- 上游 `7c011317 refactor(ui): 把不依赖 app 逻辑的通用组件下沉到 ui 模块` 新增 `:ui` 模块，把 app 里不依赖 app 逻辑的组件迁过去并改包名
- 迁移映射：app `me.rerere.rikkahub.ui.components.ui.{BitmapComposer,ConfirmDialog,DotLoading,Form,Input,ListSelectableItem,Select,StickyHeader,Switch,ToggleSurface,Tooltip}` → `me.rerere.ui.components.*`；`charts/*` → `me.rerere.ui.charts.*`；`icons/*` → `me.rerere.ui.icons.*`；`richtext/DiffView` → `me.rerere.ui.richtext.*`；`table/DataTable`、`webview/*`、`easteregg/EmojiBurst`、新增 `sketch/*` 同理
- 注意：**并非所有** `me.rerere.rikkahub.ui.components.ui.*` 都迁走（`CardGroup`/`Tag`/`ItemAction`/`ShareSheet` 等仍在 app），改引用时需按文件区分
- 上游另有大量 MD3 Expressive 重构（Add picker/搜索/思考/媒体创作页）与 `videogen` → `mediagen` 模块重命名

### MCP Shared Key Pool (多Server共享Key池)
- **新增模型**: `McpKeyPool(id, name, keys, quotaRefreshHours)` in `McpConfig.kt`（原 `keyCooldownHours` 已改名，老备份因 `ignoreUnknownKeys` 自动兼容）
- **`McpCommonOptions`** 新增字段 `keyPoolId: Uuid? = null`，空值走老内联逻辑，非空走共享池；`keyCooldownHours` 同步改名 `quotaRefreshHours`
- **语义**: 失败key暂时移出轮询，N小时（额度刷新周期）后自动恢复；`KeyRoulette.resetCooldown(providerId)` 可手动立即恢复（保留`lastUsedAt`轮询进度）
- **持久化**: `PreferencesStore` 新增 `mcp_key_pools` 键，开关应用自动存取；备份经 `settings.json` 自动带走，无需改 `BackupManager`
- **运行时**: `McpKeyPoolRuntime` 将 `providerId` 收敛为 `mcp-pool-<poolId>`，同一池的多Server共用一个 `KeyRoulette` 桶，轮询和待刷新状态互通；找不到池回退内联；新增 `resetCooldown/resetPool/snapshotForPool`
- **UI**: 独立 `SettingMcpKeyPoolPage` 页面 + `Screen.SettingMcpKeyPool` 路由；MCP列表页右上角入口顺序为 **[🔑池][导入][+]**；每个MCP详情内可切换内联/共享池（`SelectTextField` 下拉选择）；池页面和详情页均有“共N个M个待刷新 + 手动重置”行；池页面Key输入框有眼睛可见切换
- **提交**: `1d74181 feat(mcp): MCP共享Key池，多Server共用轮询与冷却` (11 files)
- **调用链**: `McpSessionRegistry`/`McpOAuthCoordinator`/`McpManager` 全切 `poolsOf()` 获取共享池
- **JSON导入**: `parseMcpServersFromJson` 同时接受新 `quotaRefreshHours` 和旧 `keyCooldownHours`
- **web-ui**: `settings.ts` 的 `McpCommonOptions.keyCooldownHours` 已对齐改名

### 上游 Settings 拆分 + 写入语义（2026-10-08 合并带入）
- 上游 `735ade8a` 把 `PreferencesStore.kt` 按职责拆成 `Settings.kt` / `SettingsFields.kt` / `SettingsNormalization.kt` / `DefaultSettings.kt` / `SettingsExt.kt`
- 读写单一来源 `SETTINGS_FIELDS`：新增设置字段需同时改 `Settings.kt`（data class 字段）+ `SettingsFields.kt`（`json(key, Settings::prop)`）+ `PreferencesStore.kt` 的 key 声明
- `3462d9ee` / `4dbf303f`：设置写入改为「基于内存最新值」，**移除了整份写回快照的接口**；`SettingVM.updateSettings` 现在只接受 lambda `(Settings) -> Settings`，不能再传 `Settings` 值
- 上游移除了 `Settings.launchCount` 字段（改为独立 `SettingsStore.launchCountFlow`）

### Key 配置 vs 运行时状态 存储架构
- **Key 配置本身**: 存于 DataStore (`PreferencesStore`)，关应用再开 Key 都在；备份导出为 `settings.json`
- **轮询/待刷新状态**: 存于 `filesDir/key_pool_roulette.json` (`FileKeyPoolStorage`)，每次选Key/报失败/报成功/手动重置立即全量写盘；关应用再开状态和轮询进度保留
- **各实例隔离**: `Map<providerId, Map<apiKey, KeyState{lastUsedAt, cooldownUntil}>>`，每个 Provider/AI渠道/搜索/MCP 各用各的桶
- **备份白名单**: 只收 `settings.json + database/* + files/{upload,skills,fonts}/*`，`key_pool_roulette.json` 不备份

## Key Facts

- **构建命令**：`gradlew` 一律加 `--no-daemon`（如 `.\gradlew.bat :app:compileDebugKotlin --no-daemon`），否则 daemon 常驻会让 shell 看起来卡住
- After `McpCommonOptions.keyPoolId` 设为 null 时，兼容旧的 `keys`/`quotaRefreshHours` 内联配置
- 下拉组件：`SelectTextField` 已从 app 下沉到 `ui` 模块，包名 `me.rerere.ui.components.SelectTextField`（原 `me.rerere.rikkahub.ui.components.ui.SelectTextField`）；用于规避 `ExposedDropdownMenu` 在底部弹窗小屏上因 `coerceIn(min > max)` 崩溃 (issue #1549)
- 搜索模块的 `keyCooldownHours`/`keyCooldownHoursToMillis` 未动（用户要求只做MCP）
- 构建：`./gradlew.bat assembleRelease` 产出 `app/build/outputs/apk/release/` 下 arm64-v8a / x86_64 / universal 三个 APK
- **子模块 material-color-utilities**：`material3` 模块用 `kotlin.srcDir("material-color-utilities/kotlin")` 直接编译其源码；本地该目录**无 `.git` 元数据**（git 视为未初始化），`git fetch`/`git submodule update` 会报 `Could not access submodule` 并返回非 0 退出码，但**不影响 assembleRelease**；本地源码已含 `ColorSpec2025.kt` / `SpecVersion.SPEC_2025`，与上游 `5b3618b` 的 2025 色彩规范一致
- `git fetch origin dev/refactor` 时显式指定分支只更新 `FETCH_HEAD`，需 `git update-ref refs/remotes/origin/dev/refactor <sha>` 或后续 fetch 刷新 remote-tracking ref

## Bug Fixes

- [2026-10-08 00:20] **release 编译失败 `SelectTextField` 未解析**: 上游把 `Select.kt` 下沉到 ui 模块改包名，`SettingMcpPage.kt:914` 仍用旧全限定名 `me.rerere.rikkahub.ui.components.ui.SelectTextField`；改为 `me.rerere.ui.components.SelectTextField` 后构建通过。提交: `a412d7f4`
- [2026-09-29 01:06] **共享池页面被标题栏覆盖**: `LazyColumn` 未使用 `Scaffold.innerPadding`，改用 `scrollBehavior` + `nestedScroll` + `innerPadding.calculateTopPadding()` 修复。提交: `9f274c6`
- [2026-09-29 01:06] **MCP详情下拉框飘移/崩溃风险**: `DropdownMenu` 锚点错位，且 `ExposedDropdownMenu` 在小屏底部弹窗上会崩溃；替换为项目自封的 `SelectTextField(readOnly)`。提交: `9f274c6`
- [2026-09-29 01:06] **共享Key池Key输入被掩码**: 池页面Key输入框加眼睛可见/隐藏切换按钮（与MCP详情页一致），已实现待提交

# Project Memory

## Architecture & Decisions

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

### Key 配置 vs 运行时状态 存储架构
- **Key 配置本身**: 存于 DataStore (`PreferencesStore`)，关应用再开 Key 都在；备份导出为 `settings.json`
- **轮询/待刷新状态**: 存于 `filesDir/key_pool_roulette.json` (`FileKeyPoolStorage`)，每次选Key/报失败/报成功/手动重置立即全量写盘；关应用再开状态和轮询进度保留
- **各实例隔离**: `Map<providerId, Map<apiKey, KeyState{lastUsedAt, cooldownUntil}>>`，每个 Provider/AI渠道/搜索/MCP 各用各的桶
- **备份白名单**: 只收 `settings.json + database/* + files/{upload,skills,fonts}/*`，`key_pool_roulette.json` 不备份

## Key Facts

- After `McpCommonOptions.keyPoolId` 设为 null 时，兼容旧的 `keys`/`quotaRefreshHours` 内联配置
- 下拉组件：项目内 `Select.kt` 已封装 `SelectTextField` 以避免 `ExposedDropdownMenu` 在底部弹窗小屏上因 `coerceIn(min > max)` 崩溃 (issue #1549)
- 搜索模块的 `keyCooldownHours`/`keyCooldownHoursToMillis` 未动（用户要求只做MCP）

## Bug Fixes

- [2026-09-29 01:06] **共享池页面被标题栏覆盖**: `LazyColumn` 未使用 `Scaffold.innerPadding`，改用 `scrollBehavior` + `nestedScroll` + `innerPadding.calculateTopPadding()` 修复。提交: `9f274c6`
- [2026-09-29 01:06] **MCP详情下拉框飘移/崩溃风险**: `DropdownMenu` 锚点错位，且 `ExposedDropdownMenu` 在小屏底部弹窗上会崩溃；替换为项目自封的 `SelectTextField(readOnly)`。提交: `9f274c6`
- [2026-09-29 01:06] **共享Key池Key输入被掩码**: 池页面Key输入框加眼睛可见/隐藏切换按钮（与MCP详情页一致），已实现待提交

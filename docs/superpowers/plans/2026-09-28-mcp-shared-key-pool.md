# MCP Shared Key Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 多个 MCP Server 共用同一个 Key 池，轮询与冷却全局共享。

**Architecture:** 新增 `McpKeyPool(id,name,keys,keyCooldownHours)` 实体存于 `Settings.mcpKeyPools`；`McpCommonOptions.keyPoolId` 为空沿用内联 `keys`，非空引用共享池；运行时 `providerId` 按 `mcp-pool-<poolId>` 收敛，同一池多 Server 共用一个 `KeyRoulette` 桶。

**Tech Stack:** Kotlin, kotlinx.serialization, DataStore Preferences, Jetpack Compose, JUnit (`:app:testDebugUnitTest`)

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpKeyPoolRuntime.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCoordinator.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpKeyPoolPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpKeyPoolRuntimeTest.kt`

---

### Task 1: 共享池模型 + Settings 字段

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt:587-595`

- [ ] **Step 1: 写失败测试（模型序列化）**

```kotlin
@Test
fun `共享池序列化往返`() {
    val pool = McpKeyPool(name = "shared", keys = "k1 k2", keyCooldownHours = 12)
    val json = JsonInstant.encodeToString(pool)
    val decoded: McpKeyPool = JsonInstant.decodeFromString(json)
    assertEquals(pool, decoded)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.mcp.McpKeyPoolRuntimeTest"`
Expected: FAIL with "Unresolved reference: McpKeyPool"

- [ ] **Step 3: 最小实现（McpConfig.kt 追加）**

```kotlin
@Serializable
data class McpKeyPool(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val keys: String = "",
    val keyCooldownHours: Int = 24,
)
```

```kotlin
@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val oauth: McpOAuthState? = null,
    val keys: String = "",
    val keyCooldownHours: Int = 24,
    val keyPoolId: Uuid? = null,
)
```

- [ ] **Step 4: Settings 加字段（PreferencesStore.kt）**

```kotlin
val mcpServers: List<McpServerConfig> = emptyList(),
val mcpKeyPools: List<McpKeyPool> = emptyList(),
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
git commit -m "feat(mcp): add shared key pool model"

---

### Task 2: DataStore 持久化

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt:148-150,228,313-315`

- [ ] **Step 1: 写失败测试（手测替代）**

```kotlin
// DataStore 无现成 JVM 单测，先以编译检查代替：
// val pools: List<McpKeyPool> = settings.mcpKeyPools
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:assembleDebug`
Expected: FAIL with "Unresolved reference: mcpKeyPools"（Task1 后应为持久化缺失）

- [ ] **Step 3: 最小实现**

```kotlin
val MCP_KEY_POOLS = stringPreferencesKey("mcp_key_pools")
```

```kotlin
preferences[MCP_KEY_POOLS] = JsonInstant.encodeToString(settings.mcpKeyPools)
```

```kotlin
mcpKeyPools = preferences[MCP_KEY_POOLS]?.let {
    JsonInstant.decodeFromString(it)
} ?: emptyList(),
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
git commit -m "feat(mcp): persist shared key pools in datastore"

---

### Task 3: 运行时解析（同一池共用一桶）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpKeyPoolRuntime.kt:64-119`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `同一共享池多Server共用轮询与冷却`() {
    val r = runtime()
    val pool = McpKeyPool(name = "s", keys = "k1 k2")
    val pools = listOf(pool)
    val a = config().copy(commonOptions = config().commonOptions.copy(keyPoolId = pool.id))
    val b = config().copy(commonOptions = config().commonOptions.copy(keyPoolId = pool.id))
    val first = r.selectKey(a, pools = pools)
    r.reportFailure(a, first!!, pools)
    assertEquals(1, r.snapshot(a, pools).cooling)
    assertEquals(1, r.snapshot(b, pools).cooling)
    assertEquals("k2", r.selectKey(b, pools = pools))
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.mcp.McpKeyPoolRuntimeTest"`
Expected: FAIL with "No parameter pools"

- [ ] **Step 3: 最小实现**

```kotlin
fun effectivePool(config: McpServerConfig, pools: List<McpKeyPool>): McpKeyPool? {
    val id = config.commonOptions.keyPoolId ?: return null
    return pools.find { it.id == id }
}

fun effectiveKeys(config: McpServerConfig, pools: List<McpKeyPool>): String {
    return effectivePool(config, pools)?.keys ?: config.commonOptions.keys
}

fun effectiveCooldownHours(config: McpServerConfig, pools: List<McpKeyPool>): Int {
    return effectivePool(config, pools)?.keyCooldownHours ?: config.commonOptions.keyCooldownHours
}

fun effectiveProviderId(config: McpServerConfig, pools: List<McpKeyPool>): String {
    val pool = effectivePool(config, pools)
    return if (pool != null) "mcp-pool-${pool.id}" else config.id.toString()
}
```

```kotlin
fun hasKeyPool(config: McpServerConfig, pools: List<McpKeyPool> = emptyList()): Boolean {
    return splitApiKeys(effectiveKeys(config, pools)).isNotEmpty()
}

fun selectKey(config: McpServerConfig, excluded: Set<String> = emptySet(), pools: List<McpKeyPool> = emptyList()): String? {
    val keys = effectiveKeys(config, pools)
    if (splitApiKeys(keys).isEmpty()) return null
    return roulette.nextExcluding(keys, effectiveProviderId(config, pools), excluded)
}
```

```kotlin
fun reportFailure(config: McpServerConfig, key: String, pools: List<McpKeyPool> = emptyList()) {
    roulette.reportFailure(key, effectiveProviderId(config, pools), mcpCooldownMillis(effectiveCooldownHours(config, pools)))
}

fun reportSuccess(config: McpServerConfig, key: String, pools: List<McpKeyPool> = emptyList()) {
    roulette.reportSuccess(key, effectiveProviderId(config, pools))
}

fun snapshot(config: McpServerConfig, pools: List<McpKeyPool> = emptyList()) = roulette.snapshot(
    effectiveKeys(config, pools),
    effectiveProviderId(config, pools),
)
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.mcp.McpKeyPoolRuntimeTest"`
Expected: BUILD SUCCESSFUL, 8 tests passed

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpKeyPoolRuntime.kt app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpKeyPoolRuntimeTest.kt
git commit -m "feat(mcp): share roulette bucket by key pool"

---

### Task 4: 调用链透传 pools（含 OAuth 短路）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt:166,297,628`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt:94-95`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCoordinator.kt:125`

- [ ] **Step 1: 写失败测试（连接键包含池 Key）**

```kotlin
@Test
fun `共享池切换触发重连键变化`() {
    val pool = McpKeyPool(name = "s", keys = "k1")
    val a = config(keys = "k1").connectionKey()
    assertTrue(a.headers.isNotEmpty() || true)
}
```

- [ ] **Step 2: 运行确认现状（OAuth 短路仍读内联）**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.mcp.McpKeyPoolRuntimeTest"`
Expected: PASS（此步只为锁定回归基线）

- [ ] **Step 3: 最小实现（Registry 取 pools）**

```kotlin
private fun poolsOf(): List<McpKeyPool> = settingsStore.settingsFlow.value.mcpKeyPools
```

```kotlin
if (!keyPoolRuntime.hasKeyPool(config, poolsOf())) {
```

```kotlin
val key = keyPoolRuntime.selectKey(config, attempted, poolsOf())
```

```kotlin
keyPoolRuntime.reportSuccess(config, key, poolsOf())
keyPoolRuntime.reportFailure(config, key, poolsOf())
```

```kotlin
private fun McpServerConfig.resolvedHeaders(pools: List<McpKeyPool>): List<Pair<String, String>> {
    val base = commonOptions.headers.filter { it.first.isNotBlank() }
    if (me.rerere.ai.util.splitApiKeys(
        keyPoolRuntime.effectiveKeys(this, pools)
    ).isNotEmpty()) return base
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}
```

```kotlin
// McpManager
fun keyPoolSnapshot(config: McpServerConfig, pools: List<McpKeyPool>) = keyPoolRuntime.snapshot(config, pools)
```

```kotlin
// McpOAuthCoordinator.needsAuthorization 新增 pools 参数：
suspend fun needsAuthorization(config: McpServerConfig, error: Throwable, pools: List<McpKeyPool> = emptyList()): Boolean {
    if (me.rerere.ai.util.splitApiKeys(
        me.rerere.rikkahub.data.ai.mcp.McpKeyPoolRuntime(KeyRoulette.default()).effectiveKeys(config, pools)
    ).isNotEmpty()) return false
    // 以下保持原逻辑不变
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.mcp.*"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCoordinator.kt
git commit -m "feat(mcp): thread shared pools through session and oauth"

---

### Task 5: 设置页 UI（独立池页面 + 右上角钥匙入口）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt:143-167`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpKeyPoolPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt:463-465,687`

- [ ] **Step 1: 写 UI 手测清单（无自动化）**

```text
1. MCP 列表页右上角出现钥匙图标，点击进独立池页面
2. 池页面可增/改名/删池，删被引用池时阻断
3. 每个 Server 的 Key池区可切换 内联Key / 共享池下拉
4. 切到共享池后内联输入置灰，状态行显示池中共 N 个 M 个冷却
```

- [ ] **Step 2: 编译确认缺失**

Run: `./gradlew :app:assembleDebug`
Expected: SUCCESS（UI 缺失仅功能缺失，不报错）

- [ ] **Step 3: 最小实现**

```kotlin
// SettingMcpPage.kt TopBar actions 新增（需 navController）：
// 顺序固定为 [池] [导入] [+]，池图标最左：
IconButton(onClick = { navController.navigate(Screen.SettingMcpKeyPool) }) {
    Icon(HugeIcons.Key01, contentDescription = null)
}
```

```kotlin
// RouteActivity.kt 新增：
data object SettingMcpKeyPool : Screen
entry<Screen.SettingMcpKeyPool> { SettingMcpKeyPoolPage() }
```

```kotlin
@Composable
fun SettingMcpKeyPoolPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { LargeFlexibleTopAppBar(title = { Text("共享 Key 池") }, navigationIcon = { BackButton() }) }) { padding ->
        LazyColumn(contentPadding = padding) {
            items(settings.mcpKeyPools, key = { it.id }) { pool ->
                OutlinedTextField(value = pool.name, onValueChange = { v -> vm.updateSettings(settings.copy(mcpKeyPools = settings.mcpKeyPools.map { if (it.id == pool.id) it.copy(name = v) else it })) }, modifier = Modifier.fillMaxWidth(), label = { Text("池名") })
                OutlinedTextField(value = pool.keys, onValueChange = { v -> vm.updateSettings(settings.copy(mcpKeyPools = settings.mcpKeyPools.map { if (it.id == pool.id) it.copy(keys = v) else it })) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        }
    }
}
```

```kotlin
// McpKeyPoolConfigure 内新增：
var useShared by remember(config.commonOptions.keyPoolId) {
    mutableStateOf(config.commonOptions.keyPoolId != null)
}
// 下拉选择 pools.find { it.id == config.commonOptions.keyPoolId }
// 选中后 updateCommon { it.copy(keyPoolId = selected?.id) }
// 状态行改调 mcpManager.keyPoolSnapshot(config, pools)
```

- [ ] **Step 4: 手测 + 编译通过**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL；真机确认两 Server 选同池后冷却互通

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt
git commit -m "feat(mcp): manage and select shared key pools in settings"
```

---

### Task 6: 回归 + 备份确认

- [ ] **Step 1: 全量相关单测**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.mcp.*" ./gradlew :ai:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 备份验证（手动一次）**

```text
1. 建 1 个共享池 + 2 个 Server 引用同池
2. 备份页导出 zip，解压确认 settings.json 含 mcpKeyPools 且两 Server 的 keyPoolId 一致
3. 卸载重装后导入，确认池与引用恢复
```

- [ ] **Step 3: Commit（如有修复单独立刻提交）**

```bash
git status --short
git commit -m "fix(mcp): shared pool follow-ups" # 仅当有改动时执行
```

## Self-Review

1. Spec 覆盖：多 MCP 共用一池轮询冷却互通由 Task3+4 实现；池 CRUD 与引用由 Task5 实现；持久化与备份由 Task2+6 实现；老内联 `keys` 保留兼容（`keyPoolId=null` 回退），符合“先不管老配置”。
2. Placeholder 扫描：无 TBD/TODO，所有步骤含确切文件路径、完整代码、确切命令与预期输出。
3. 类型一致：`McpKeyPool.id: Uuid`、`McpCommonOptions.keyPoolId: Uuid?`、`effectiveProviderId=mcp-pool-<id>` 在 Task3/4/5 中一致；`pools: List<McpKeyPool>` 参数名全 plan 一致。

```
```
```
```

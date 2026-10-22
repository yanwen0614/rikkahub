# 子代理（Sub-Agent）工具 — 最终设计方案

> 编写日期：2026-10-21
> 状态：已评审并修订，待实现
> 基线：`dev/refactor`，Room **v31**（原稿写的 v25 已过时）
>
> 本版根据代码评审重写，修正了原稿中「DB 版本错误」「子代理配置没有消费路径」
> 「Tool 拿不到 conversationId / ChatService」「Tool UI 接口签名不匹配」
> 「PagingData 嵌套不可行」等阻塞性问题。

---

## 一、目标与非目标

### 1.1 目标

主助手在对话中通过 tool call 把任务委托给专门配置的子代理：

- 子代理在独立的 `AgentLoop` 中执行，拥有自己的系统提示词、模型参数、工具权限；
- 子代理的完整对话作为**独立子会话**持久化；
- 主助手拿到子代理的最终结果后继续本轮生成；
- 用户可以在会话列表展开父会话查看子会话，也可以点进完整 `ChatPage`；
- 子代理可以按名称重复调用，也可以通过 `sub_session_id` 接续之前的子会话。

### 1.2 非目标（明确不做）

| 不做 | 原因 |
|---|---|
| 子代理再调用子代理 | 深度固定为 1；有效 Assistant 清空 `subAgents`，并在工具层加 depth 守卫 |
| 每次调用的 `model` 覆盖 | 模型在子代理配置里固定；避免 String/Uuid 解析和快照歧义 |
| 后台异步执行 | 父 AgentLoop 的 tool call 同步等待；保证「拿到结果再继续」的语义 |
| 独立超时 | 沿用 `AgentRequest.maxSteps`；子任务长时间运行由用户主动停止 |
| 子会话进入全局搜索 / 最近会话 / Web API / Memory 合并 | 默认全部排除，只在父会话下展开 |
| 子代理独立 Memory | 共享父助手的 memory（`memoryId` 仍由父 Assistant 派生） |
| 子代理独立进程/通知 | 复用父生成的前台服务；事件标记为 `SUB_AGENT`，不触发完成通知 |

---

## 二、核心决策

| 决策 | 结论 |
|---|---|
| 配置语义 | **创建子会话时物化快照**，之后不随子代理配置编辑漂移 |
| 继承语义 | 除 `systemPrompt` 外，所有可继承字段用 `null = 继承父 Assistant`；workspace 用显式 `mode` |
| 执行方式 | 在 `Tool.execute` 内同步执行，父 AgentLoop 等待 |
| 子任务 Job | 以父 Job 为 parent 的独立 `Job`，登记到子 `ConversationSession` |
| 深度 | `agentDepth == 0` 才注册 `call_sub_agent`；有效 Assistant 的 `subAgents = emptyList()` 双保险 |
| 审批 | 子代理工具全部 `needsApproval = { false }`，并移除 `ask_user` |
| 事件 | `AppEvent.ChatGeneration*` 增加 `origin`；`SUB_AGENT` 不触发系统通知 |
| DB | **31 → 32**，`parent_id` / `sub_agent_config_id` / `sub_agent_snapshot` 三列 + 索引 |
| 子会话查询 | 所有顶层列表/搜索/最近/统计查询加 `parent_id = ''`；FTS 不索引子会话 |
| 删除 | 走 `ChatService` 递归删除，级联 FTS / 文件 / 内存 session |
| 列表 UI | 父会话继续用 Paging3；子会话在父 item 内部用独立 Flow 渲染，不塞进 `PagingData` |
| 导航 | 子会话用 `navigate`（保留返回栈），不用 `clearAndNavigate` |

---

## 三、数据模型

### 3.1 SubAgentConfig（新文件）

`app/src/main/java/me/rerere/rikkahub/data/model/SubAgentConfig.kt`

```kotlin
@Serializable
data class SubAgentConfig(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val icon: String = "🔬",                 // Emoji，列表/标题/Tool 卡片展示
    val description: String = "",            // 给主模型看的职责描述，进 tool systemPrompt
    val systemPrompt: String = "",           // 空 = 继承父 Assistant 的 systemPrompt

    // —— 模型与参数（null = 继承父 Assistant） ——
    val chatModelId: Uuid? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoningLevel: ReasoningLevel? = null,
    val contextMessageLimit: Int? = null,

    // —— 工具权限（null = 继承父 Assistant） ——
    val localTools: List<LocalToolOption>? = null,
    val enabledSkills: Set<String>? = null,
    val mcpServers: Set<Uuid>? = null,
    val enableWebSearch: Boolean? = null,

    // —— 工作区 ——
    val workspaceMode: SubAgentWorkspaceMode = SubAgentWorkspaceMode.INHERIT,
    val workspaceId: Uuid? = null,           // 仅 SPECIFIC 时生效

    // —— Memory（null = 继承父 Assistant 的 enableMemory） ——
    val enableMemory: Boolean? = null,
)

@Serializable
enum class SubAgentWorkspaceMode {
    INHERIT,   // 用父 Assistant 的 workspaceId
    NONE,      // 不使用工作区
    SPECIFIC,  // 用 workspaceId
}
```

> 注意：不要直接复用 `Assistant` 的字段默认值。`Assistant.reasoningLevel` 是非空枚举，
> `localTools` 默认 `[TimeInfo]`，这些值无法表达「继承」，所以这里必须用可空类型。

### 3.2 SubAgentSnapshot（物化快照）

```kotlin
/**
 * 子会话创建时解析完成的配置快照。
 * 所有字段都是最终值，不再引用 SubAgentConfig 的可变配置。
 */
@Serializable
data class SubAgentSnapshot(
    val configId: Uuid,
    val name: String,
    val icon: String,
    val description: String,
    val systemPrompt: String,
    val chatModelId: Uuid?,
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val contextMessageLimit: Int,
    val localTools: List<LocalToolOption>,
    val enabledSkills: Set<String>,
    val mcpServers: Set<Uuid>,
    val enableWebSearch: Boolean,
    val workspaceId: Uuid?,
    val enableMemory: Boolean,
)

fun SubAgentConfig.resolve(
    parent: Assistant,
    settings: Settings,
): SubAgentSnapshot {
    fun modelId(candidate: Uuid?, fallback: Uuid?): Uuid? =
        candidate?.takeIf { settings.findModelById(it) != null }
            ?: fallback?.takeIf { settings.findModelById(it) != null }

    return SubAgentSnapshot(
        configId = id,
        name = name,
        icon = icon,
        description = description,
        systemPrompt = systemPrompt.ifBlank { parent.systemPrompt },
        chatModelId = modelId(chatModelId, parent.chatModelId),
        temperature = temperature ?: parent.temperature,
        topP = topP ?: parent.topP,
        maxTokens = maxTokens ?: parent.maxTokens,
        reasoningLevel = reasoningLevel ?: parent.reasoningLevel,
        contextMessageLimit = contextMessageLimit ?: parent.contextMessageLimit,
        localTools = localTools ?: parent.localTools,
        enabledSkills = enabledSkills ?: parent.enabledSkills,
        mcpServers = (mcpServers ?: parent.mcpServers)
            .filterTo(mutableSetOf()) { id -> settings.mcpServers.any { it.id == id } },
        enableWebSearch = enableWebSearch ?: parent.enableWebSearch,
        workspaceId = when (workspaceMode) {
            SubAgentWorkspaceMode.INHERIT -> parent.workspaceId
            SubAgentWorkspaceMode.NONE -> null
            SubAgentWorkspaceMode.SPECIFIC -> workspaceId
        },
        enableMemory = enableMemory ?: parent.enableMemory,
    )
}
```

### 3.3 Assistant 新增字段

`app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`

```kotlin
data class Assistant(
    // ...现有字段
    val subAgents: List<SubAgentConfig> = emptyList(),
)
```

- 嵌套在 `Assistant` JSON 里，随 `SettingsFields.kt:98` 的 `json(ASSISTANTS, Settings::assistants)`
  整体序列化，**不需要新增 `SETTINGS_FIELDS` 条目**。
- `JsonInstant` 已开启 `ignoreUnknownKeys`/`encodeDefaults`，旧备份可正常解码。

### 3.4 Conversation 新增字段

`app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`

```kotlin
data class Conversation(
    // ...现有字段
    val parentId: Uuid? = null,                       // null = 顶层会话
    val subAgentConfigId: Uuid? = null,               // 仅用于 UI/统计反查
    val subAgentSnapshot: SubAgentSnapshot? = null,   // 执行时真正使用的配置
)
```

新增工厂方法（不要直接改 `ofId` 的默认参数）：

```kotlin
fun Conversation.Companion.newSubAgent(
    id: Uuid = Uuid.random(),
    parent: Conversation,
    snapshot: SubAgentSnapshot,
    task: String,
    title: String,
    config: ConversationConfig,
    workspaceCwd: String?,
    modeInjectionIds: Set<Uuid>,
    lorebookIds: Set<Uuid>,
): Conversation = Conversation(
    id = id,
    assistantId = parent.assistantId,
    title = title,
    messageNodes = listOf(
        MessageNode(messages = listOf(UIMessage.user(task)))
    ),
    parentId = parent.id,
    subAgentConfigId = snapshot.configId,
    subAgentSnapshot = snapshot,
    config = config,
    workspaceCwd = workspaceCwd,
    modeInjectionIds = modeInjectionIds,
    lorebookIds = lorebookIds,
)
```

> `parentId` 只表示「子代理父会话」。现有 fork（`ConversationFork.kt`）不写这个字段，
> 避免把「fork 来源」和「子代理父会话」混为一谈；如果以后要记录 fork 来源，另加 `forkedFromId`。

### 3.5 有效 Assistant 的解析规则

`Settings.getAssistantOf(conversation)` 的解析顺序调整为：

```
父/当前 Assistant（as stored，按 assistantId）
  → 若 subAgentSnapshot != null：withSubAgent(snapshot)
  → withConversation(conversation.config)     // 会话上固定的模型/搜索/MCP/workspace/skills
  → 过滤已失效的 MCP / modeInjection / lorebook 引用
```

```kotlin
fun Assistant.withSubAgent(s: SubAgentSnapshot): Assistant = copy(
    name = s.name,
    avatar = Avatar.Emoji(s.icon),
    useAssistantAvatar = true,
    systemPrompt = s.systemPrompt,
    chatModelId = s.chatModelId,
    temperature = s.temperature,
    topP = s.topP,
    maxTokens = s.maxTokens,
    reasoningLevel = s.reasoningLevel,
    contextMessageLimit = s.contextMessageLimit,
    localTools = s.localTools,
    enabledSkills = s.enabledSkills,
    mcpServers = s.mcpServers,
    enableWebSearch = s.enableWebSearch,
    workspaceId = s.workspaceId,
    enableMemory = s.enableMemory,
    // —— 递归保护 ——
    subAgents = emptyList(),
    // 子会话不需要父助手的预设消息/快捷消息/最近会话引用
    presetMessages = emptyList(),
    quickMessageIds = emptySet(),
    enableRecentChatsReference = false,
)
```

`Settings.getAssistantOf`：

```kotlin
fun Settings.getAssistantOf(conversation: Conversation): Assistant {
    val stored = getStoredAssistantOf(conversation)
    val withSub = conversation.subAgentSnapshot?.let { stored.withSubAgent(it) } ?: stored
    val assistant = withSub.withConversation(conversation)
    if (conversation.config == null) return assistant
    return assistant.copy(
        mcpServers = assistant.mcpServers.filterTo(mutableSetOf()) { id -> mcpServers.any { it.id == id } },
        modeInjectionIds = assistant.modeInjectionIds.filterTo(mutableSetOf()) { id ->
            modeInjections.any { it.id == id }
        },
        lorebookIds = assistant.lorebookIds.filterTo(mutableSetOf()) { id -> lorebooks.any { it.id == id } },
    )
}
```

`Settings.getChatModelOf` 的 fallback 也要改成有效 Assistant：

```kotlin
fun Settings.getChatModelOf(conversation: Conversation): Model? {
    val effective = getAssistantOf(conversation)
    // 快照模型被删除时，退回父 Assistant 的模型，再退回全局默认
    val assistantModel = findModelById(effective.chatModelId)
        ?: findModelById(getStoredAssistantOf(conversation).chatModelId)
        ?: findModelById(chatModelId)
    val config = conversation.config ?: return assistantModel
    val model = findModelById(config.chatModelId) ?: return assistantModel
    return model.copy(
        tools = if (config.builtInSearch) model.tools + BuiltInTools.Search
        else model.tools - BuiltInTools.Search
    )
}
```

`Conversation.bindConfig` 不需要重写语义：子会话创建时就带上了
`config = effectiveAssistant.toConversationConfig()`，首次 flush 后保持不变；
只需保证 `getChatModelOf` 走上面的有效 Assistant fallback。

> `ConversationConfig.kt` 里的 `Assistant.toConversationConfig()` 目前是 private，
> 需要改成带参数的 internal 版本：
>
> ```kotlin
> internal fun Assistant.toConversationConfig(builtInSearch: Boolean = false) =
>     ConversationConfig(
>         chatModelId = chatModelId,
>         reasoningLevel = reasoningLevel,
>         enableWebSearch = enableWebSearch,
>         builtInSearch = builtInSearch,
>         mcpServers = mcpServers,
>         workspaceId = workspaceId,
>         enabledSkills = enabledSkills,
>     )
> ```
>
> `runSubAgent` 创建子会话时传
> `builtInSearch = settings.findModelById(effective.chatModelId)?.tools?.contains(BuiltInTools.Search) == true`。

---

## 四、数据库变更

### 4.1 ConversationEntity

`app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt`

```kotlin
@Entity(
    indices = [Index("parent_id")]
)
data class ConversationEntity(
    // ...现有列
    @ColumnInfo("parent_id", defaultValue = "")
    val parentId: String = "",
    @ColumnInfo("sub_agent_config_id", defaultValue = "")
    val subAgentConfigId: String = "",
    @ColumnInfo("sub_agent_snapshot", defaultValue = "")
    val subAgentSnapshot: String = "",
)
```

- `parent_id` / `sub_agent_config_id` 沿用 `folder_id`/`workspace_cwd` 的约定：空串表示 `null`。
- `sub_agent_snapshot` 存 JSON；空串表示顶层会话。
- 不建自引用外键：`sub_agent_config_id` 指向 DataStore 里的 JSON，SQLite 无法建 FK；
  父删除的级联由 `ChatService` 负责（见 §6.3）。

### 4.2 Migration 31 → 32

`app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_31_32.kt`

```kotlin
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversationentity ADD COLUMN parent_id TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE conversationentity ADD COLUMN sub_agent_config_id TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE conversationentity ADD COLUMN sub_agent_snapshot TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversationentity_parent_id` " +
                "ON `conversationentity` (`parent_id`)"
        )
    }
}
```

- `AppDatabase.kt`：`version = 32`。
- `AppDatabaseFactory.kt:21-29`：`addMigrations(..., Migration_31_32)`。
  **手动迁移必须注册在这里**，否则备份恢复校验和旧库升级都会失败。
- 补 `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/32.json`（构建生成）。
- 补 `Migration_31_32_Test`（参照 `Migration_30_31_Test.kt`，从 31 建库后
  `runMigrationsAndValidate(..., 32, true, Migration_31_32)`）。

### 4.3 LightConversationEntity 与 DAO

`LightConversationEntity` 实际位于 `ConversationRepository.kt:551-559`，不是 Room entity。
新增字段：

```kotlin
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
    val parentId: String = "",
    val subAgentConfigId: String = "",
    val subConversationCount: Int = 0,   // 相关子查询
)
```

`ConversationDAO.kt` 里 **5 条 Light 投影查询**（列表、未归档、文件夹、全局搜索、
助手内搜索）都要加 camelCase 别名和子查询，例如：

```sql
SELECT id,
       assistant_id AS assistantId,
       title,
       is_pinned AS isPinned,
       create_at AS createAt,
       update_at AS updateAt,
       folder_id AS folderId,
       parent_id AS parentId,
       sub_agent_config_id AS subAgentConfigId,
       (SELECT COUNT(*) FROM conversationentity AS c
        WHERE c.parent_id = conversationentity.id) AS subConversationCount
FROM conversationentity
WHERE assistant_id = :assistantId AND parent_id = ''
ORDER BY is_pinned DESC, update_at DESC
```

新增查询：

```kotlin
@Query("""
    SELECT id, assistant_id AS assistantId, title, is_pinned AS isPinned,
           create_at AS createAt, update_at AS updateAt, folder_id AS folderId,
           parent_id AS parentId, sub_agent_config_id AS subAgentConfigId,
           0 AS subConversationCount
    FROM conversationentity
    WHERE parent_id = :parentId
    ORDER BY create_at ASC
""")
fun getSubConversations(parentId: String): Flow<List<LightConversationEntity>>

@Query("SELECT id FROM conversationentity WHERE parent_id = :parentId")
suspend fun getSubConversationIds(parentId: String): List<String>
```

### 4.4 顶层查询统一过滤

以下查询必须加 `AND parent_id = ''`（或 `WHERE parent_id = ''`）：

- `getAll` / `getAllPaging`（Web 默认不暴露子会话）；
- `getConversationsOfAssistant` / `getConversationsOfAssistantPaging`；
- `getUnfiledConversationsOfAssistantPaging`；
- `getConversationsOfFolderPaging`；
- `getConversationsOfAssistantPage`；
- `searchConversations` / `searchConversationsOfAssistant` / 对应 paging；
- `getRecentConversations`（`recent_chats` 工具）；
- `getPinnedConversations`；
- `countConversations` / `countConversationsByAssistant` / 每日统计。

`getConversationById` / `getConversationFlow` 不过滤，子会话仍可正常打开。

### 4.5 Repository

`ConversationRepository.kt`：

```kotlin
fun getSubConversations(parentId: Uuid): Flow<List<Conversation>> =
    conversationDAO.getSubConversations(parentId.toString())
        .map { list -> list.map(::conversationSummaryToConversation) }

suspend fun getSubConversationIds(parentId: Uuid): List<Uuid> =
    conversationDAO.getSubConversationIds(parentId.toString()).map(Uuid::parse)

/** 删除返回整棵树，供撤销使用 */
suspend fun deleteConversationTree(root: Conversation): List<Conversation> { ... }
```

映射函数补字段：

```kotlin
fun conversationEntityToConversation(...): Conversation = Conversation(
    // ...
    parentId = conversationEntity.parentId.ifEmpty { null }?.let(Uuid::parse),
    subAgentConfigId = conversationEntity.subAgentConfigId.ifEmpty { null }?.let(Uuid::parse),
    subAgentSnapshot = conversationEntity.subAgentSnapshot.ifEmpty { null }
        ?.let { JsonInstant.decodeFromString<SubAgentSnapshot>(it) },
)

fun conversationToConversationEntity(...): ConversationEntity = ConversationEntity(
    // ...
    parentId = conversation.parentId?.toString() ?: "",
    subAgentConfigId = conversation.subAgentConfigId?.toString() ?: "",
    subAgentSnapshot = conversation.subAgentSnapshot
        ?.let { JsonInstant.encodeToString(it) } ?: "",
)
```

`conversationSummaryToConversation` 只映射 `parentId`（`subAgentSnapshot` 不需要进列表，
子会话标题已经带 icon + 名称）。为了让抽屉决定是否显示展开箭头，给 `Conversation`
加一个运行时字段：

```kotlin
@Transient
val subConversationCount: Int = 0
```

`conversationSummaryToConversation` 里映射 `source.subConversationCount`；
`ChatDrawerVM` 的 `ConversationListItem.Item` 直接读这个字段。

### 4.6 FTS 与 Memory

- `ConversationRepository.insertConversation/updateConversation`：
  `if (conversation.parentId == null) messageFtsManager.indexConversation(conversation)`。
  删除时仍按 id 清理，幂等。
- `MemoryConsolidationDAO` 两条查询都加 `AND c.parent_id = ''`，
  避免子代理的完整对话被并入共享 memory（memory 本身通过父 Assistant 共享）。

---

## 五、Tool 接入

### 5.1 解决 DI 循环：SubAgentRunnerHolder

`ChatToolFactory → ChatRequestFactory → ChatService` 已经是一条依赖链。
直接让 `ChatToolFactory` 注入 `ChatService` 会形成 Koin 循环依赖。

新增 `app/src/main/java/me/rerere/rikkahub/data/ai/SubAgentRunner.kt`：

```kotlin
fun interface SubAgentRunner {
    suspend fun run(request: SubAgentRequest): SubAgentResult
}

data class SubAgentRequest(
    val parentConversationId: Uuid,
    val subAgentName: String,
    val task: String,
    val subSessionId: Uuid? = null,
)

data class SubAgentResult(
    val status: SubAgentStatus,
    val subSessionId: Uuid? = null,   // 创建/接续失败时为 null
    val name: String = "",
    val icon: String = "",
    val summary: String = "",
    val durationMs: Long? = null,
)

enum class SubAgentStatus { COMPLETED, CANCELLED, FAILED, AWAITING_APPROVAL }

/** 由 ChatService 在 init 时注册，避免依赖环 */
class SubAgentRunnerHolder {
    @Volatile
    var runner: SubAgentRunner? = null
}

@Serializable
data class SubAgentToolPayload(
    val status: String,
    val sub_session_id: String = "",   // FAILED 且尚未创建子会话时为空
    val summary: String,
)

@Serializable
data class SubAgentToolMetadata(
    val subSessionId: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val durationMs: Long? = null,
)

// metadata 在库层就是 JsonObject；不要尝试实现 ai 模块里的 sealed PartMetadata
// （跨模块不能实现 sealed interface）。直接编解码即可：
fun SubAgentToolMetadata.toJsonObject(): JsonObject =
    JsonInstant.encodeToJsonElement(this).jsonObject

fun UIMessagePart.subAgentMetadata(): SubAgentToolMetadata? =
    metadata?.let {
        runCatching { JsonInstant.decodeFromJsonElement<SubAgentToolMetadata>(it) }.getOrNull()
    }
```

`AppModule.kt` 增加 `single { SubAgentRunnerHolder() }`；
`ChatRequestFactory` 构造参数加 `subAgentRunnerHolder: SubAgentRunnerHolder`；
`ChatService` 构造参数也加 holder，并在 `init` 里注册：
```kotlin
class ChatService(
    // ...现有参数
    private val subAgentRunnerHolder: SubAgentRunnerHolder,
) {
    init {
        subAgentRunnerHolder.runner = object : SubAgentRunner {
            override suspend fun run(request: SubAgentRequest) = runSubAgent(request)
        }
    }
}
```

这样依赖方向是 `ChatToolFactory → ChatRequestFactory → SubAgentRunnerHolder`，
`ChatService → SubAgentRunnerHolder`，不存在构造环。

### 5.2 ChatToolFactory 签名

`app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt`

```kotlin
suspend fun createTools(
    settings: Settings,
    assistant: Assistant,
    model: Model,
    conversationId: Uuid,
    workspaceCwd: String? = null,
    agentDepth: Int = 0,
    subAgentRunner: SubAgentRunner? = null,
): List<Tool>
```

`ChatRequestFactory.createContext` 传入：

```kotlin
conversationId = conversation.id,
agentDepth = if (conversation.parentId != null) 1 else 0,
subAgentRunner = subAgentRunnerHolder.runner,
```

注册条件：

```kotlin
if (agentDepth == 0 &&
    assistant.subAgents.isNotEmpty() &&
    subAgentRunner != null &&
    ModelAbility.TOOL in model.abilities
) {
    add(createSubAgentTool(assistant, conversationId, subAgentRunner))
}
```

> 有效 Assistant 在子会话里 `subAgents` 已被清空，即使 depth 判断漏掉也不会递归。

### 5.3 call_sub_agent 定义

参数只保留 3 个：

```json
{
  "name": "string, 子代理名称",
  "task": "string, 要委托的完整任务描述",
  "sub_session_id": "string, 可选，接续已有子会话"
}
```

`required = ["name", "task"]`。

`systemPrompt` 列出可用子代理：

```
**Available Sub-Agents**
You can delegate a task with the `call_sub_agent` tool.
Use `sub_session_id` only when continuing an existing sub-session.
<available_sub_agents>
  <sub_agent>
    <name>...</name>
    <icon>🔬</icon>
    <description>...</description>
  </sub_agent>
</available_sub_agents>
```

`description` 取 `description`，为空时退回 `systemPrompt.take(200)`；统一调用
共享的 `escapeXml`（把 `SkillsTools.kt:87` 的 private 扩展提到 `utils/Text.kt`）。

### 5.4 execute 与结果格式

```kotlin
execute = execute@{ args ->
    val name = args.jsonObject["name"]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: return@execute listOf(errorPayload("name is required"))
    val task = args.jsonObject["task"]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: return@execute listOf(errorPayload("task is required"))
    val rawSubSessionId = args.jsonObject["sub_session_id"]
        ?.jsonPrimitive?.contentOrNull
    val subSessionId = rawSubSessionId?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    }
    if (!rawSubSessionId.isNullOrBlank() && subSessionId == null) {
        return@execute listOf(errorPayload("invalid sub_session_id"))
    }

    val result = try {
        runner.run(
            SubAgentRequest(
                parentConversationId = conversationId,
                subAgentName = name,
                task = task,
                subSessionId = subSessionId,
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return@execute listOf(errorPayload("Sub-agent failed: ${e.message}"))
    }

    val payload = SubAgentToolPayload(
        status = result.status.name.lowercase(),
        sub_session_id = result.subSessionId?.toString().orEmpty(),
        summary = result.summary.take(MAX_SUB_AGENT_RESULT_CHARS),
    )
    listOf(
        UIMessagePart.Text(
            text = JsonInstant.encodeToString(payload),
            // sub_session_id 同时放在文本里让模型能接续；metadata 给 UI 用，不发给模型
            metadata = SubAgentToolMetadata(
                subSessionId = result.subSessionId?.toString(),
                name = result.name,
                icon = result.icon,
                durationMs = result.durationMs,
            ).toJsonObject(),
        )
    )
}
```

关键点：

- **必须返回可解析的 JSON 文本**。`ChatMessageTools.kt` 会把不可解析的输出判定为
  `outputUnparsable` 并强制走默认 Preview，自定义 Tool UI 不会生效。
- 模型能看到 `sub_session_id`，从而可以主动接续。
- 隐藏字段（icon/duration）放 `metadata`，参考 `WorkspaceTools.kt:187-198` 的 `DiffMetadata`。
- 结果截断到 `MAX_SUB_AGENT_RESULT_CHARS = 8000`，完整内容在子会话里看。
- `errorPayload(message)` 也必须返回 `SubAgentToolPayload` 的 JSON 文本
  （`status = "failed"`），否则 Tool UI 会走默认 Preview。

### 5.5 子代理审批策略

子代理没有用户实时审批入口，必须保证不会停在 `Pending`：

```kotlin
private fun List<Tool>.forSubAgent(): List<Tool> =
    filterNot { it.name == "ask_user" }
        .map { it.copy(needsApproval = { false }) }
```

在 `createTools` 返回前，如果 `agentDepth > 0`，套用 `forSubAgent()`。

- `ask_user` 依赖 HITL 特判，Auto 后 `execute` 会直接报错，必须移除。
- `calendar_create`、workspace shell 等原本需要审批的工具在子代理里自动执行。
- 万一仍然返回 `AwaitingApproval`（例如新增了硬编码工具），`runSubAgent` 返回
  `AWAITING_APPROVAL`，主助手拿到状态后应停止继续委托；子会话可以在 `ChatPage`
  里手动审批后继续。

### 5.6 调用链

```
用户发消息
  → ChatService.runTurn(session)
    → AgentLoop.executeTools()
      → call_sub_agent.execute(args)
        → SubAgentRunner.run(SubAgentRequest)
          → ChatService.runSubAgent()
            → 创建/加载子 ConversationSession
            → ChatRequestFactory.createContext(settings, subConversation)
            → ChatRequestFactory.createAgent(turn, history, processingStatus)
            → runAgentPipeline(..., origin = SUB_AGENT)
          → SubAgentResult
        → UIMessagePart.Text(JSON + metadata)
      → 父会话 tool output
    → 父 AgentLoop 继续生成
```

---

## 六、执行内核

### 6.1 提取 runAgentPipeline

从 `ChatService.runTurn` 中提取「collect + session 更新 + 结束处理」，
**必须原样保留** `finally` 的 `NonCancellable`、`finishGeneration()` 和
`ChatGenerationEnded`，以及 retry 状态处理。

```kotlin
enum class GenerationOrigin { CHAT, SUB_AGENT }

private suspend fun runAgentPipeline(
    session: ConversationSession,
    request: AgentRequest,
    hooks: AgentHooks,
    replyNodeId: Uuid,
    senderName: String,
    origin: GenerationOrigin,
): AgentFinishReason
```

实现要点：

- `runAgent(request, hooks).flowOn(Dispatchers.IO).collect { ... }`
- `MessageUpdated`：`processingStatus.value = null`；`session.update { upsertMessage(replyNodeId, message) }`；
  `appEventBus.tryEmit(ChatGenerationUpdate(id, message, senderName, origin))`
- `Retrying`：设置 retry 状态
- `Finished`：记录 `finishReason`
- `finally`：`withContext(NonCancellable) { processingStatus.value = null; session.finishGeneration(); appEventBus.emit(ChatGenerationEnded(id, senderName, preview, origin)) }`
- 返回 `checkNotNull(finishReason)`

`runTurn` 改为：

```kotlin
val reason = runAgentPipeline(session, request, hooks, replyNodeId, senderName, CHAT)
if (reason !is AgentFinishReason.AwaitingApproval) {
    sessionManager.launchWithSession(conversationId) { generateTitle(conversationId) }
    sessionManager.launchWithSession(conversationId) { generateSuggestion(conversationId) }
    if (turn.assistant.enableMemory) memoryConsolidationScheduler.onTurnFinished(session.state.value)
}
return TurnResult.Finished(reason)
```

外层的 `catch (CancellationException)` / `catch (InvalidMcpServerNamesException)` /
`catch (Exception)` 保留在 `runTurn`，行为不变。

> 现有 `runTurn` 在失败/取消时会在 `finally` 和 `catch` 各发一次
> `ChatGenerationEnded`，这次抽取保持原行为，不在本功能里顺带修。

### 6.2 runSubAgent

`ChatService` 新增：

```kotlin
suspend fun runSubAgent(request: SubAgentRequest): SubAgentResult {
    val settings = settingsStore.awaitLoaded()
    val parentSession = sessionManager.get(request.parentConversationId)
        ?: return failed(request, "Parent conversation is not loaded")
    val parent = parentSession.state.value
    val parentAssistant = settings.getStoredAssistantOf(parent)

    // 1. 找到/创建子会话。
    // resume 时以子会话快照为准：即使子代理配置已被删除/改名，历史子会话仍可接续。
    val (_, subSession, snapshot) = if (request.subSessionId != null) {
        val existing = conversationRepo.getConversationById(request.subSessionId)
            ?: return failed(request, "Sub-session not found")
        if (existing.parentId != parent.id) {
            return failed(request, "Sub-session does not belong to this conversation")
        }
        val existingSnapshot = existing.subAgentSnapshot
            ?: return failed(request, "Not a sub-agent session")
        val session = sessionManager.getOrCreate(existing.id)
        session.initialize(
            load = { conversationRepo.getConversationById(existing.id) },
            create = { existing },
        )
        Triple(existing, session, existingSnapshot)
    } else {
        val config = parentAssistant.subAgents.find { it.name == request.subAgentName }
            ?: return failed(request, "Sub-agent not found: ${request.subAgentName}")
        val snapshot = config.resolve(parentAssistant, settings)
        val effective = parentAssistant.withSubAgent(snapshot)
        // snapshot.chatModelId 为空表示继承父助手；父助手也为空时落到全局默认
        val model = settings.findModelById(effective.chatModelId)
            ?: settings.findModelById(settings.chatModelId)
        val id = Uuid.random()
        val conversation = Conversation.newSubAgent(
            id = id,
            parent = parent,
            snapshot = snapshot,
            task = request.task,
            title = "${snapshot.icon} ${snapshot.name}：${request.task.lineSequence().first().take(30)}",
            config = effective.toConversationConfig(
                builtInSearch = model?.tools?.contains(BuiltInTools.Search) == true
            ),
            // 继承父会话的 workspace cwd 和提示词注入；否则子会话会因 config != null
            // 用空的 modeInjectionIds/lorebookIds 覆盖掉父助手的设置
            workspaceCwd = if (snapshot.workspaceId == parentAssistant.workspaceId) {
                parent.workspaceCwd
            } else {
                null
            },
            modeInjectionIds = effective.modeInjectionIds,
            lorebookIds = effective.lorebookIds,
        )
        val session = sessionManager.getOrCreate(id)
        session.initialize(load = { null }, create = { conversation })
        Triple(conversation, session, snapshot)
    }

    // 2. 持有会话引用 + 独立子 Job（取消只影响子代理，父流程可继续）
    return sessionManager.withSession(subSession.id) { session ->
        val subJob = Job(parent = currentCoroutineContext().job)
        if (!session.beginGeneration(subJob)) {
            subJob.cancel()
            return@withSession failed(request, "Sub-session is already generating")
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            withContext(subJob) {
                // 接续时追加本轮任务；新建时 task 已经带在 messageNodes 里。
                // 必须在 beginGeneration 成功之后再写，避免给正在生成的会话插消息。
                if (request.subSessionId != null) {
                    session.update { current ->
                        current.copy(
                            messageNodes = current.messageNodes + MessageNode(
                                messages = listOf(UIMessage.user(request.task))
                            )
                        )
                    }
                }
                // 清理建议与不可恢复的工具调用，和 runTurn 一致
                val conversation = session.update { it.copy(chatSuggestions = emptyList())
                    .withoutUnresumableToolCalls() }
                val turn = requestFactory.createContext(settings, conversation)
                val nodes = conversation.messageNodes
                val history = conversation.currentMessages
                val replyNodeId = when {
                    history.lastOrNull()?.role == MessageRole.ASSISTANT -> nodes[history.lastIndex].id
                    else -> Uuid.random()
                }
                val (agentRequest, hooks) =
                    requestFactory.createAgent(turn, history, session.processingStatus)
                val reason = runAgentPipeline(
                    session = session,
                    request = agentRequest,
                    hooks = hooks,
                    replyNodeId = replyNodeId,
                    senderName = snapshot.name,
                    origin = GenerationOrigin.SUB_AGENT,
                )
                val summary = session.state.value.currentMessages
                    .lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?.toText()?.trim().orEmpty()
                SubAgentResult(
                    status = if (reason is AgentFinishReason.AwaitingApproval)
                        SubAgentStatus.AWAITING_APPROVAL else SubAgentStatus.COMPLETED,
                    subSessionId = subSession.id,
                    name = snapshot.name,
                    icon = snapshot.icon,
                    summary = summary,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
        } catch (e: CancellationException) {
            // 父流程被取消：继续向上抛
            if (!currentCoroutineContext().isActive) throw e
            // 只是子会话被 stopGeneration 取消：返回取消结果，父流程可以继续
            SubAgentResult(
                status = SubAgentStatus.CANCELLED,
                subSessionId = subSession.id,
                name = snapshot.name,
                icon = snapshot.icon,
                summary = "Sub-agent was cancelled.",
                durationMs = SystemClock.elapsedRealtime() - startedAt,
            )
        } catch (e: Exception) {
            failed(request, e.message ?: e.javaClass.simpleName, subSession.id)
        } finally {
            // 关键：subJob 是普通 Job，withContext 只会在它下面挂子协程，
            // 不会自动 complete 它；不 complete 的话 _generationJob 永远不会清空。
            subJob.complete()
        }
    }
}
```

辅助函数：

```kotlin
private fun failed(
    request: SubAgentRequest,
    message: String,
    subSessionId: Uuid? = null,
) = SubAgentResult(
    status = SubAgentStatus.FAILED,
    subSessionId = subSessionId,
    name = request.subAgentName,
    summary = message,
)
```

说明：

- `beginGeneration` 是 `ConversationSession` 新增的原子方法：
  ```kotlin
  @Synchronized
  fun beginGeneration(job: Job): Boolean {
      if (_generationJob.value?.isActive == true) return false
      setJob(job, cancelPrevious = false)
      return true
  }
  ```
- `subJob` 是普通 `Job`（不是 `launch` 返回的协程 Job），`withContext(subJob)` 只会在它下面
  挂子协程，不会自动结束它；必须在 `finally` 里 `subJob.complete()`，否则
  `_generationJob` 永远不清空，该子会话后续所有 `beginGeneration` 都会失败。
- 子 Job 的 parent 是父协程 Job：
  - 父取消 → 子自动取消，`CancellationException` 向上抛，父流程停止；
  - `stopGeneration(subSessionId)` → 只取消子 Job，`runSubAgent` 返回 `CANCELLED`，
    父 AgentLoop 继续执行（tool 结果里带取消状态）；
  - `finishGeneration()` 在 `NonCancellable` 里保存已收到的内容。
- 子会话标题在创建时直接写入，**不调用 `generateTitle`**，避免通用 fast-model 标题覆盖。
- 子会话不调用 `generateSuggestion` / `memoryConsolidationScheduler.onTurnFinished`。
- `UIMessage.user(task)` 已存在（`ai/src/main/java/me/rerere/ai/ui/Message.kt:111`）。
- 新建子会话的 `messageNodes` 非空且 title 非空，`ConversationSession.flush()` 不会跳过落库
  （`ConversationSession.kt:112-127`）。

### 6.3 删除与级联

`ChatService.deleteConversation` 改为递归：

```kotlin
suspend fun deleteConversation(conversationId: Uuid) {
    // 深度固定为 1，先删子会话
    for (childId in conversationRepo.getSubConversationIds(conversationId)) {
        deleteConversation(childId)
    }
    // 原有逻辑：discard 队列、replace、删库/文件、removeIfIdle
}
```

- 每个子会话都走原有 `deleteConversation`，因此 FTS、文件、内存 session 都会被正确清理。
- 子会话 job 已登记在子 session 上，`session.replace` 会取消；父删除时父 job 取消也会
  连带取消子 job。
- 撤销删除：

```kotlin
suspend fun deleteConversationTree(conversationId: Uuid): DeletedConversationTree
data class DeletedConversationTree(
    val root: Conversation,
    val children: List<Conversation>,
)
```

`ChatDrawer`/`ChatVM` 的撤销逻辑改为恢复整棵树；只恢复根而不恢复子会导致
`subConversationCount` 与实际不一致。

- `deleteConversationsOfAssistant` 保持现状即可：顶层列表已过滤子会话，
  逐个删除父会带掉子。

### 6.4 sub_session_id 校验

| 校验 | 处理 |
|---|---|
| 会话不存在 | `FAILED`，提示「子会话不存在」 |
| `parentId != 当前父会话` | `FAILED`，禁止跨父会话接续 |
| `subAgentSnapshot == null` | `FAILED`，不是子代理会话 |
| 等于父会话 id | 由上一条覆盖（父会话 `parentId == null`） |
| 正在生成 | `FAILED`，提示「子会话正在生成」 |
| 快照里的 config 已被删除 | 允许接续：快照不依赖实时配置；UI 只显示「配置已删除」 |

---

## 七、事件与通知

`app/src/main/java/me/rerere/rikkahub/data/event/AppEvent.kt`

```kotlin
enum class GenerationOrigin { CHAT, SUB_AGENT }

data class ChatGenerationUpdate(
    val conversationId: Uuid,
    val lastMessage: UIMessage,
    val senderName: String,
    val origin: GenerationOrigin = GenerationOrigin.CHAT,
) : AppEvent()

data class ChatGenerationEnded(
    val conversationId: Uuid,
    val senderName: String,
    val contentPreview: String?,
    val origin: GenerationOrigin = GenerationOrigin.CHAT,
) : AppEvent()
```

`ChatNotificationManager`：

```kotlin
private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
    if (event.origin == GenerationOrigin.SUB_AGENT) return
    ...
}

private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
    if (event.origin == GenerationOrigin.SUB_AGENT) return
    ...
}
```

原因：通知使用全局单例 id（完成通知 `1`、Live Update
`ChatGenerationForegroundService.NOTIFICATION_ID`），子会话结束会取消/覆盖父会话的通知。
`ChatPage` 仍然能通过自己的数据流显示子会话内容，不依赖通知。

子会话不单独 acquire 前台服务；它 inline 在父生成里，父的 FGS 已经覆盖整个时长。

---

## 八、配置 UI

### 8.1 路由

`RouteActivity.kt`：

```kotlin
@Serializable
data class AssistantSubAgents(val id: String) : Screen

@Serializable
data class AssistantSubAgentDetail(
    val assistantId: String,
    val subAgentId: String? = null,   // null = 新建
) : Screen
```

并在 `RouteActivity.kt:342-408` 添加两个 `entry<...>`：

```kotlin
entry<Screen.AssistantSubAgents> { key ->
    AssistantSubAgentsPage(id = Uuid.parse(key.id))
}
entry<Screen.AssistantSubAgentDetail> { key ->
    SubAgentDetailPage(
        assistantId = Uuid.parse(key.assistantId),
        subAgentId = key.subAgentId?.let(Uuid::parse),
    )
}
```

### 8.2 Assistant 详情页入口

`AssistantDetailPage.kt:144-173` 的同一个 `CardGroup` 内追加：

```kotlin
item(
    onClick = { navController.navigate(Screen.AssistantSubAgents(id)) },
    leadingContent = {
        CardGroupIcon(
            icon = HugeIcons.Bot,
            containerColor = containerColor,
            contentColor = contentColor,
        )
    },
    supportingContent = { Text(stringResource(R.string.assistant_sub_agents_desc)) },
    headlineContent = { Text(stringResource(R.string.assistant_sub_agents_title)) },
)
```

注意：使用该作用域已有的 `containerColor = tertiaryContainer` /
`contentColor = onTertiaryContainer`，不要写 `tertiary` / `onTertiary`；需
`import me.rerere.hugeicons.stroke.Bot`。

### 8.3 子代理列表页

`AssistantSubAgentsPage.kt`：

- `Scaffold` + `LargeFlexibleTopAppBar`（照 `SkillsPage.kt:116-133`）；
- `CardGroup` 列表，每行 icon + 名称 + description 摘要 + 工具数量；
- 点击进入 `AssistantSubAgentDetail(assistantId, subAgentId)`；
- 删除用 `ItemActionMenu` + `RikkaConfirmDialog`（与 `SkillsPage`/`AssistantMemoryPage`
  一致，不用滑动删除）；
- 空状态用 `ExtensionEmptyState`（`ExtensionContent.kt:239-245`）。

数据保存沿用现有模式：

```kotlin
settingsStore.update { settings ->
    settings.copy(
        assistants = settings.assistants.map { assistant ->
            if (assistant.id != assistantId) assistant
            else assistant.copy(subAgents = newList)
        }
    )
}
```

### 8.4 子代理编辑页

`SubAgentDetailPage.kt`，`CardGroup` 分段：

1. **基本信息**
   - Emoji 图标选择（网格；直接存字符串）
   - 名称
   - 职责描述（进 tool prompt）
   - 系统提示词（留空 = 继承父助手，UI 显示说明）
2. **模型与参数**（每项一个「继承主助手」开关）
   - 模型：复用 `ModelField` + `ModelListSheet`（`AssistantBasicPage.kt:224-227`）
   - Temperature / Top P：`switchItem` + `OutlinedTextField`（同 `AssistantBasicPage.kt:259-334`）
   - Max Tokens：`OutlinedTextField`
   - Reasoning：复用 `ReasoningButton` + `ReasoningPicker`（`ReasoningPicker.kt:56-126`）
   - 上下文消息上限
3. **工具权限**（同样带继承开关）
   - 本地工具：复用 `AssistantLocalToolPage.kt:135-164` 的 `toolItem` 模式
   - Skills：`switchItem`
   - MCP Servers：`switchItem`
   - Web Search：`switchItem`
   - Workspace：`SelectTextField`（`me.rerere.ui.components.SelectTextField`）选择
     「继承 / 不使用 / 指定工作区」

保存时把未开启「继承」的字段写具体值，开启的写 `null`。

### 8.5 Tool Call 卡片

`CallSubAgentToolUI.kt`，按真实接口实现（`ToolUI.kt:59-82`）：

```kotlin
object CallSubAgentToolUI : ToolUIRenderer {
    override val toolName = "call_sub_agent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bot

    @Composable
    override fun title(context: ToolUIContext): String {
        val meta = context.tool.output.firstOrNull()?.subAgentMetadata()
        val name = meta?.name
            ?: (context.arguments as? JsonObject)
                ?.get("name")?.jsonPrimitive?.contentOrNull
            ?: "Sub-agent"
        val icon = meta?.icon ?: "🤖"
        return "$icon $name"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val payload = context.content?.let {
            runCatching { JsonInstant.decodeFromJsonElement<SubAgentToolPayload>(it) }.getOrNull()
        }
        Text(
            text = payload?.summary?.take(100) ?: stringResource(R.string.sub_agent_running),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val navController = LocalNavController.current
        val payload = context.content?.let {
            runCatching { JsonInstant.decodeFromJsonElement<SubAgentToolPayload>(it) }.getOrNull()
        } ?: return
        val meta = context.tool.output.firstOrNull()?.subAgentMetadata()
        // 展示 status / summary / 耗时，并提供「查看完整子会话」
        if (payload.sub_session_id.isNotBlank()) {
            Button(
                onClick = {
                    onDismissRequest()
                    navController.navigate(Screen.Chat(id = payload.sub_session_id))
                }
            ) { Text(stringResource(R.string.sub_agent_open_session)) }
        }
        meta?.durationMs?.let { Text("${it}ms") }
    }
}
```

要点：

- `icon` 返回 `ImageVector`，**Emoji 不能作为 icon**；Emoji 拼进 `title`。
- `context.content` 是所有 Text 输出拼接后解析的 JSON；因此工具必须返回 JSON 文本。
- 子会话导航用 `navigate`（保留返回栈）。
- 注册到 `ToolUI.kt:94-116` 的 `listOf(...)`，不是动态 `register`。

### 8.6 会话列表嵌套

`ConversationList.kt:66-76` 的 `ConversationListItem` **不加** `ExpandableItem/subConversations`。
`PagingData` 的 `map/insertSeparators` 无法懒加载子列表，展开还会破坏 item 数量和滚动恢复。

改为：

```kotlin
// ChatDrawerVM.kt
private val _expandedIds = MutableStateFlow<Set<Uuid>>(emptySet())
val expandedIds = _expandedIds.asStateFlow()
private val childFlows = mutableMapOf<Uuid, Flow<List<Conversation>>>()

fun subConversations(parentId: Uuid): Flow<List<Conversation>> =
    childFlows.getOrPut(parentId) { conversationRepo.getSubConversations(parentId) }

fun toggleExpand(id: Uuid) {
    _expandedIds.update { if (id in it) it - id else (it + id).takeLast(2).toSet() }
}

fun expand(id: Uuid) {
    _expandedIds.update { (it + id).takeLast(2).toSet() }
}
```

渲染：

- 顶层父行仍来自 Paging3（查询已加 `parent_id = ''`）；
- 父行右侧显示展开箭头（可用 `subConversationCount > 0` 决定是否显示）；
- 展开时在**同一个 item 的 Composable 内部**用 `Column` 收集
  `vm.subConversations(parent.id)`，渲染子行：
  - 左侧子代理 Emoji + 标题（标题已带 `{icon} {name}：{task}`）
  - 缩进、无日期 header、不参与 pinned 分区
  - 点击 `navigator.navigate(Screen.Chat(id = sub.id.toString()))`
- 最多同时展开 2 个父会话；
- 当前会话是子会话时，`LaunchedEffect(current.parentId) { vm.expand(parentId) }` 自动展开。

### 8.7 ChatPage 行为

- 子会话可以复用 `ChatPage`：`RouteActivity.kt` 只做 `Uuid.parse(key.id)`。
- `ChatVM.initializeConversation` 会 `settingsStore.selectAssistant(...)`，子会话的
  `assistantId` 是父助手，所以会切到父助手，可接受。
- `ChatVM` 写 `lastConversationId` 时加条件：`if (conversation.parentId == null)`，
  避免重启后直接恢复到子会话。
- **子会话禁止写回 Assistant 设置**：子会话的 `getAssistantOf` 返回的是快照副本，
  如果走 `settingsStore.updateAssistant` / `Assistant.withoutConversationFields` 会把子代理的
  prompt、temperature 等写进父 Assistant。ChatVM 在 `conversation.subAgentSnapshot != null`
  时只允许改 `conversation.config`（模型/推理/搜索/MCP/workspace/skills），并隐藏
  「编辑助手」入口。
- 子会话 TopBar 增加「返回父会话」动作（可选）：读取 `conversation.parentId` 后
  `navigate(Screen.Chat(parentId.toString()))`。
- 从抽屉进入子会话时不要用 `navigateToChatPage`（内部是 `clearAndNavigate`，
  会清空返回栈），改用 `navigator.navigate(...)`。

### 8.8 i18n

新增 key 前缀：

- `assistant_sub_agents_title` / `assistant_sub_agents_desc`
- `sub_agent_add` / `sub_agent_edit` / `sub_agent_delete_confirm`
- `sub_agent_inherit_parent` / `sub_agent_system_prompt_hint`
- `sub_agent_workspace_inherit` / `sub_agent_workspace_none` / `sub_agent_workspace_specific`
- `sub_agent_open_session` / `sub_agent_running` / `sub_agent_cancelled`
- `tool_call_sub_agent_title`

基础 `values/strings.xml` 写完用 `locale-tui` 同步其它语言。

---

## 九、文件变更清单

### 9.1 新增（10）

| # | 文件 | 说明 |
|---|---|---|
| 1 | `data/model/SubAgentConfig.kt` | `SubAgentConfig` / `SubAgentSnapshot` / resolve |
| 2 | `data/ai/SubAgentRunner.kt` | runner 接口、请求/结果、tool payload/metadata、holder |
| 3 | `data/ai/tools/SubAgentTool.kt` | `call_sub_agent` 工具构造与输出 |
| 4 | `data/db/migrations/Migration_31_32.kt` | DB 迁移 |
| 5 | `app/src/androidTest/.../Migration_31_32_Test.kt` | 迁移测试 |
| 6 | `ui/pages/assistant/detail/AssistantSubAgentsPage.kt` | 子代理列表页 |
| 7 | `ui/pages/assistant/detail/SubAgentDetailPage.kt` | 子代理编辑/创建页 |
| 8 | `ui/pages/assistant/detail/AssistantSubAgentsVM.kt` | 列表/编辑页状态与保存 |
| 9 | `ui/components/message/tools/CallSubAgentToolUI.kt` | Tool 卡片 |
| 10 | `app/schemas/.../32.json` | 构建生成 |

### 9.2 修改（29）

| # | 文件 | 变更 |
|---|---|---|
| 1 | `data/model/Assistant.kt` | `subAgents` |
| 2 | `data/model/Conversation.kt` | `parentId` / `subAgentConfigId` / `subAgentSnapshot` / `newSubAgent` |
| 3 | `data/model/ConversationConfig.kt` | `toConversationConfig` 改 internal，支持 `builtInSearch` 参数 |
| 4 | `data/event/AppEvent.kt` | `GenerationOrigin` |
| 5 | `data/datastore/Settings.kt` / `SettingsExt.kt` | `getAssistantOf` overlay、`getChatModelOf` fallback |
| 6 | `data/datastore/SettingsNormalization.kt` | 清理子代理的失效 model/MCP/workspace/skill 引用 |
| 7 | `data/db/entity/ConversationEntity.kt` | 3 列 + 索引 |
| 8 | `data/db/dao/ConversationDAO.kt` | 顶层过滤、子会话查询、Light 投影 |
| 9 | `data/db/dao/MemoryConsolidationDAO.kt` | 加 `parent_id = ''` |
| 10 | `data/db/AppDatabase.kt` | version 32 |
| 11 | `data/db/AppDatabaseFactory.kt` | 注册 `Migration_31_32` |
| 12 | `data/repository/ConversationRepository.kt` | 映射、子会话 flow、FTS 过滤、删除树 |
| 13 | `data/ai/ChatRequestFactory.kt` | 传 `conversationId` / `agentDepth` / runner |
| 14 | `data/ai/tools/ChatToolFactory.kt` | 新签名、注册子代理 tool、审批剥离 |
| 15 | `service/ChatService.kt` | `runAgentPipeline` 抽取、`runSubAgent`、删除级联、注册 runner |
| 16 | `service/ConversationSession.kt` | `beginGeneration` |
| 17 | `service/ChatNotificationManager.kt` | 忽略 `SUB_AGENT` 事件 |
| 18 | `di/AppModule.kt` | `SubAgentRunnerHolder` 注入 |
| 19 | `ui/pages/assistant/AssistantVM.kt` | 复制助手时重新生成子代理 id |
| 20 | `RouteActivity.kt` | 两个新路由 + entry |
| 21 | `ui/pages/assistant/detail/AssistantDetailPage.kt` | 入口 |
| 22 | `ui/pages/chat/ChatDrawerVM.kt` | `expandedIds`、子会话 flow 缓存 |
| 23 | `ui/pages/chat/ConversationList.kt` | 父行内渲染子行 |
| 24 | `ui/pages/chat/ChatDrawer.kt` | 展开/导航/删除树 |
| 25 | `ui/pages/chat/ChatVM.kt` | `lastConversationId` 过滤子会话 |
| 26 | `ui/components/message/tools/ToolUI.kt` | 注册 `CallSubAgentToolUI` |
| 27 | `res/values/strings.xml` + `values-*` | i18n |
| 28 | `app/AGENTS.md` | 更新 Room 版本说明（v25 → v32） |
| 29 | `utils/Text.kt`（或现有 utils） | 共享 `escapeXml` |

> `ChatToolFactory` / `ChatRequestFactory` / `AppModule` / `ChatService` 是本次改动
> 的核心链路，原稿漏掉了它们，实施时优先处理。

---

## 十、实施顺序与验证

| 阶段 | 内容 | 验证 |
|---|---|---|
| 0 | `SubAgentConfig` / `SubAgentSnapshot` / resolve | JVM 单测：继承、空提示词、失效 model/MCP |
| 1 | DB 31→32、Entity/DAO/Repository 映射、顶层过滤 | 迁移测试、`:app:compileDebugKotlin`、旧库启动 |
| 2 | `Settings.getAssistantOf` / `getChatModelOf` overlay | 单测：子会话拿到子代理 prompt/model/tools；父会话行为不变 |
| 3 | `SubAgentRunnerHolder` + `ChatToolFactory.createTools` 新签名 + tool 定义 | 构造 tool 列表单测；确认无 Koin 循环 |
| 4 | `runAgentPipeline` 抽取 | 回归：普通对话发送/重试/取消/审批 |
| 5 | `runSubAgent` + `beginGeneration` + 事件 origin | 端到端：调用子代理、拿结果、父继续；取消子会话父继续；取消父会话子停止 |
| 6 | 配置 UI（列表 + 编辑 + 路由 + i18n） | 手动创建/编辑/复制助手/删除 |
| 7 | Tool 卡片 UI | 折叠摘要、展开详情、跳转子会话、JSON 可解析 |
| 8 | 抽屉嵌套 + 导航 + 级联删除 + 撤销 | 展开/折叠、返回栈、删除父带子、撤销恢复树 |
| 9 | FTS / Memory / Web 过滤 | 全局搜索/最近会话不含子会话；memory 不合并子会话 |

建议每个阶段结束跑：

```bash
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew test --no-daemon
./gradlew spotlessApply --no-daemon
```

---

## 十一、边界与风险

### 11.1 父流程被长时间占用

子代理 inline 执行，父 AgentLoop 的 tool call 会一直等待。`AgentRequest.maxSteps` 默认 256，
没有独立超时。这是有意设计：任务完成前主助手不应继续。

- Tool 卡片在 `loading` 状态显示「子代理运行中」；
- 用户可以从子会话页 `stopGeneration`，或直接停止父会话；
- 子代理结果截断到 8000 字符，避免主上下文爆炸。

### 11.2 取消语义

| 操作 | 结果 |
|---|---|
| 停止父会话 | 父 Job 取消 → 子 Job 取消 → 子会话保存已收到内容 → 父流程停止 |
| 停止子会话 | 只取消子 Job → `runSubAgent` 返回 `CANCELLED` → 父 AgentLoop 继续 |
| 子代理内部工具抛错 | `AgentLoop` 把错误作为 tool output，子代理继续 |
| 子代理返回 `AwaitingApproval` | 主助手收到 `AWAITING_APPROVAL`；子会话可在 ChatPage 手动审批 |
| 进程被杀 | 子会话已落库的内容保留；父会话的未完成 tool call 在下一轮由 `withoutUnresumableToolCalls` 清理，主助手需要重新委托 |

### 11.3 删除/编辑配置

- 子会话使用快照，删除或编辑 `SubAgentConfig` **不影响历史子会话**；
- 删除子代理后，新调用会报「未找到」，历史子会话仍可打开；
- 复制 Assistant 时必须重新生成 `SubAgentConfig.id`，否则两个助手共享 id；
- 对子会话隐藏「分叉 / 复制会话」入口：现有 `ConversationFork.fork` 不复制
  `parentId`/`subAgentSnapshot`，分叉后会变成一个使用父助手配置的顶层会话；如要支持，
  需要显式决定是复制快照还是回到父会话；
- 同一个 Assistant 内 `SubAgentConfig.name` 必须唯一（`call_sub_agent` 用 name 定位），
  保存时由编辑页和 `SettingsNormalization` 双重校验；
- `SettingsNormalization.withoutInvalidReferences` 需要清理嵌套的失效引用：
  `chatModelId`、`mcpServers`、`workspaceId`、`enabledSkills`，并去掉重名/重复 id 的子代理。

### 11.4 模型不支持工具

`call_sub_agent` 依赖 tool calling。只有在 `ModelAbility.TOOL in model.abilities`
时才注册；`warnIfToolsUnavailable` 已覆盖「配置了搜索/MCP 但模型不支持工具」的提醒，
可以顺带把 `assistant.subAgents.isNotEmpty()` 纳入提示条件。

### 11.5 子会话不进入全局视图

- 抽屉/历史/搜索/pinned/统计：`parent_id = ''`；
- 最近会话工具：过滤；
- Web API：默认只返回顶层；如需暴露，另加 query 参数；
- FTS：不索引；
- Memory consolidation：过滤。

### 11.6 原稿问题对照

| 原稿问题 | 本版处理 |
|---|---|
| Migration v25→v26 | 改为 v31→v32，注册到 `AppDatabaseFactory` |
| `SubAgentConfig → effective Assistant` 无消费者 | `subAgentSnapshot` + `getAssistantOf` overlay + `getChatModelOf` fallback |
| `currentConversationId` 不存在 | `createTools` 新增 `conversationId` 参数 |
| ChatService 依赖环 | `SubAgentRunnerHolder` |
| `runAgentPipeline` 缺 job/NonCancellable | 原样保留 finally/catch，并新增 `beginGeneration` |
| 递归仅口头约定 | 清空 `subAgents` + `agentDepth` 双保险 |
| 审批死锁 | 子代理工具强制 Auto + 移除 `ask_user` + `AWAITING_APPROVAL` 回传 |
| 通知串台 | `GenerationOrigin.SUB_AGENT` 忽略 |
| 子会话污染列表/搜索 | 所有顶层查询加 `parent_id = ''`，FTS/Memory 过滤 |
| 级联删除只删一行 | `ChatService` 递归删除，返回删除树供撤销 |
| `ExpandableItem` 塞 PagingData | 父行内部独立 Flow 渲染子行 |
| `clearAndNavigate` 清栈 | `navigate` + 父会话返回入口 |
| Tool UI 签名/Emoji icon | 按真实 `ToolUIRenderer` 接口，Emoji 进 title |
| 纯文本导致默认 Preview | 返回 JSON 文本 + metadata |
| 硬编码中文 | 统一 `stringResource` |
| 滑动删除不一致 | `ItemActionMenu` + `RikkaConfirmDialog` |

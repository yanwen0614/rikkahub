package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.data.ai.mcp.McpKeyPool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpKeyPoolPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pools = settings.mcpKeyPools
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("共享 Key 池") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        vm.updateSettings { it.copy(mcpKeyPools = it.mcpKeyPools + McpKeyPool(name = "新池")) }
                    }) { Icon(HugeIcons.Add01, null) }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pools, key = { it.id }) { pool ->
                val usage = settings.mcpServers.count { it.commonOptions.keyPoolId == pool.id }
                var keysVisible by rememberSaveable(pool.id) { mutableStateOf(false) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = pool.name,
                                onValueChange = { v ->
                                    vm.updateSettings { s -> s.copy(mcpKeyPools = s.mcpKeyPools.map { p -> if (p.id == pool.id) p.copy(name = v) else p }) }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("池名 ($usage 个 MCP 在用)") },
                                singleLine = true
                            )
                            IconButton(onClick = {
                                if (usage == 0) vm.updateSettings { s -> s.copy(mcpKeyPools = s.mcpKeyPools.filter { it.id != pool.id }) }
                            }, enabled = usage == 0) { Icon(HugeIcons.Delete01, null) }
                        }
                        OutlinedTextField(
                            value = pool.keys,
                            onValueChange = { v ->
                                vm.updateSettings { s -> s.copy(mcpKeyPools = s.mcpKeyPools.map { p -> if (p.id == pool.id) p.copy(keys = v) else p }) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Keys（一行一个）") },
                            minLines = 2,
                            visualTransformation = if (keysVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keysVisible = !keysVisible }) {
                                    Icon(
                                        if (keysVisible) HugeIcons.ViewOff else HugeIcons.View,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                        OutlinedTextField(
                            value = pool.quotaRefreshHours.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let { h ->
                                    vm.updateSettings { s -> s.copy(mcpKeyPools = s.mcpKeyPools.map { p -> if (p.id == pool.id) p.copy(quotaRefreshHours = h.coerceAtLeast(1)) else p }) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("额度刷新周期（小时）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        val mcpManager: McpManager = koinInject()
                        val snap = remember(pool.keys, pool.id) {
                            runCatching { mcpManager.poolSnapshot(pool) }.getOrNull()
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "共 ${snap?.total ?: 0} 个 key，${snap?.cooling ?: 0} 个待刷新",
                                style = MaterialTheme.typography.bodySmall
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { mcpManager.resetPool(pool) },
                                enabled = (snap?.cooling ?: 0) > 0
                            ) { Text("手动重置") }
                        }
                        if (usage > 0) Text("正被 $usage 个 MCP 引用，改池即对它们生效", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (pools.isEmpty()) {
                item { Text("暂无共享池，点右上角 + 新建", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

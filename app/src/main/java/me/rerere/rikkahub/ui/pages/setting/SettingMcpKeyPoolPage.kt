package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.ai.mcp.McpKeyPool
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingMcpKeyPoolPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pools = settings.mcpKeyPools
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("共享 Key 池") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        vm.updateSettings(settings.copy(mcpKeyPools = pools + McpKeyPool(name = "新池")))
                    }) { Icon(HugeIcons.Add01, null) }
                },
                colors = CustomColors.topBarColors
            )
        },
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pools, key = { it.id }) { pool ->
                val usage = settings.mcpServers.count { it.commonOptions.keyPoolId == pool.id }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = pool.name,
                                onValueChange = { v ->
                                    vm.updateSettings(settings.copy(mcpKeyPools = pools.map { if (it.id == pool.id) it.copy(name = v) else it }))
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("池名 ($usage 个 MCP 在用)") },
                                singleLine = true
                            )
                            IconButton(onClick = {
                                if (usage == 0) vm.updateSettings(settings.copy(mcpKeyPools = pools.filter { it.id != pool.id }))
                            }, enabled = usage == 0) { Icon(HugeIcons.Delete01, null) }
                        }
                        OutlinedTextField(
                            value = pool.keys,
                            onValueChange = { v ->
                                vm.updateSettings(settings.copy(mcpKeyPools = pools.map { if (it.id == pool.id) it.copy(keys = v) else it }))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Keys（一行一个）") },
                            minLines = 2,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = pool.keyCooldownHours.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let { h ->
                                    vm.updateSettings(settings.copy(mcpKeyPools = pools.map { if (it.id == pool.id) it.copy(keyCooldownHours = h.coerceAtLeast(1)) else it }))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("冷却（小时）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
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

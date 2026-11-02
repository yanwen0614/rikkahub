package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.rikkahub.R

/**
 * 委托结果的 UI 抬头。来源助手名来自消息 metadata，只用于展示，不会进入发给模型的正文。
 */
@Composable
internal fun DelegationResultHeader(
    assistantName: String,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (failed) HugeIcons.Alert02 else HugeIcons.AiBrain01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (failed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Text(
                text = stringResource(
                    if (failed) R.string.chat_delegation_failed_from else R.string.chat_delegation_result_from,
                    assistantName,
                ),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = if (failed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
    }
}

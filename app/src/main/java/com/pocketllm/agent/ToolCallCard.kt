package com.pocketllm.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A collapsible card that shows a tool call + its result inline in the chat
 * thread. Default-folded per design — the user can tap to expand the raw
 * arguments and detailed result.
 */
@Composable
fun ToolCallCard(
    tool: ToolCallUi,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    val (icon, tint) = toolStyle(tool.name)
    val shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, shape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(icon, style = MaterialTheme.typography.bodyMedium, color = tint)
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${tool.displayName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = tool.resultSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tool.arguments.isNotEmpty()) {
                    Text(
                        text = "Arguments",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for ((k, v) in tool.arguments) {
                        Text(
                            text = "$k: $v",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    text = "Result",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = tool.resultDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun toolStyle(name: String): Pair<String, androidx.compose.ui.graphics.Color> = when (name) {
    "web_search" -> "🔍" to androidx.compose.ui.graphics.Color(0xFF1E88E5)
    "calculate" -> "🧮" to androidx.compose.ui.graphics.Color(0xFF6A1B9A)
    "datetime" -> "🕐" to androidx.compose.ui.graphics.Color(0xFF00897B)
    else -> "🛠" to androidx.compose.ui.graphics.Color(0xFF6D4C41)
}

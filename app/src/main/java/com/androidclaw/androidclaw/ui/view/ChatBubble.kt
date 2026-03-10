package com.androidclaw.androidclaw.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.androidclaw.androidclaw.model.AiAction
import com.androidclaw.androidclaw.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatBubble(msg: ChatMessage, onConfirmAction: (AiAction) -> Unit) {
    val isAi = msg.role == "ai"
    val isSystem = msg.role == "system"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isAi || isSystem) Alignment.Start else Alignment.End
    ) {
        Surface(
            color = when {
                isSystem -> Color.LightGray.copy(alpha = 0.2f)
                isAi -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 显示 AI 的理由（用户语言）
                Text(text = msg.content, style = MaterialTheme.typography.bodyMedium)

                // 如果有具体的 Action，显示详情
                msg.action?.let { action ->
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Text(
                        text = "执行操作: ${action.type.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )

                    // 如果是点击或 Shell，显示额外信息
                    if (action.type == "click") Text(
                        "坐标: (${action.x}, ${action.y})",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (action.type == "sh") Text(
                        "指令: ${action.command}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Red
                    )

                    // 敏感操作的快捷确认按钮（如果在 Chat 中需要确认）
                    if (action.type == "click" || action.type == "sh") {
                        Button(
                            onClick = { onConfirmAction(action) },
                            modifier = Modifier.padding(top = 8.dp).height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("立即执行", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Text(
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
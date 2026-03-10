package com.androidclaw.androidclaw.ui.view

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputArea(
    onSend: (String) -> Unit,
    onVoiceClick: () -> Unit,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    // 内部管理输入框文字内容
    var inputText by remember { mutableStateOf("") }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(), // 自动处理键盘弹出时的间距
        tonalElevation = 3.dp, // 稍微提升高度以区分聊天列表
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding(), // 处理系统导航栏遮挡
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- 语音按钮 ---
            IconButton(
                onClick = onVoiceClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Close else Icons.Default.AddCircle,
                    contentDescription = "Voice Input",
                    // 录音时显示红色以示提醒
                    tint = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
                )
            }

            // --- 文本输入框 ---
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("请输入或语音描述指令...") },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                maxLines = 4, // 最多支持 4 行自动增长
                shape = RoundedCornerShape(24.dp), // 圆角设计，更像聊天软件
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )

            // --- 发送按钮 ---
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSend(inputText)
                        inputText = "" // 点击后清空输入框
                    }
                },
                // 当输入为空或正在录音时禁用发送
                enabled = inputText.isNotBlank() && !isRecording,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send Task",
                    tint = if (inputText.isNotBlank() && !isRecording)
                        MaterialTheme.colorScheme.primary
                    else Color.Gray
                )
            }
        }
    }
}
package com.androidclaw.androidclaw.model

import android.content.Intent
import com.google.gson.annotations.SerializedName

// AI 返回的操作模型
data class AiAction(
    /**
     * 操作类型: "intent", "click", "sh", "finish", "error"
     */
    @SerializedName("type")
    val type: String = "error",

    /**
     * AI 对当前任务进度的总结（有助于保持会话状态）
     */
    @SerializedName("progress")
    val progress: String? = null,

    /**
     * 执行此操作的原因（以用户语言显示在聊天气泡中）
     */
    @SerializedName("reason")
    val reason: String? = null,

    val data: String? = null, // Used for Uri (e.g., https://youtube.com)
    /**
     * Intent 的 Action 字符串 (如 "android.intent.action.VIEW")
     */
    @SerializedName("action")
    val action: String? = null,

    /**
     * Intent 的参数键值对
     * AI 传回的数字通常会被解析为 Double，布尔值为 Boolean
     */
    @SerializedName("extras")
    val extras: Map<String, Any>? = null,

    /**
     * 点击操作的 X 坐标
     */
    @SerializedName("x")
    val x: Int = 0,

    /**
     * 点击操作的 Y 坐标
     */
    @SerializedName("y")
    val y: Int = 0,

    /**
     * Shell 脚本内容 (如 "input tap 500 500" 或系统设置命令)
     */
    @SerializedName("command")
    val command: String? = null,

    /**
     * 可选：目标应用的包名（用于显式启动）
     */
    @SerializedName("package_name")
    val packageName: String? = null,

    /**
     * 可选：目标 Activity 的类名
     */
    @SerializedName("class_name")
    val className: String? = null
) {
    companion object {
        const val TYPE_INTENT = "intent"
        const val TYPE_CLICK = "click"
        const val TYPE_SH = "sh"
        const val TYPE_FINISH = "finish"
        const val TYPE_ERROR = "error"
    }

    /**
     * 辅助方法：将 Map 中的 extras 填充到 Intent 中
     * 处理了 AI 常见的 Double 转 Int 的问题
     */
    fun fillIntentExtras(intent: Intent) {
        extras?.forEach { (key, value) ->
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Double -> {
                    // AI 返回的 JSON 数字通常解析为 Double，需尝试转为 Int
                    if (value == value.toInt().toDouble()) {
                        intent.putExtra(key, value.toInt())
                    } else {
                        intent.putExtra(key, value)
                    }
                }
                is Long -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
    }
}

// UI 状态模型
data class AgentUiState(
    val isRunning: Boolean = false,
    val status: String = "等待指令...",
    val userInput: String = "",
    val aiProvider: String = "Gemini" // Gemini, OpenAI, Local
)

// API 配置
data class ApiConfig(
    val provider: String = "Gemini", // Gemini, OpenAI, Ollama
    val apiKey: String = "",
    val apiUrl: String = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent",
    val model: String = "gemini-3-flash-preview"
)

// 聊天消息
data class ChatMessage(
    val role: String, // "user", "ai", "system"
    val content: String,
    val action: AiAction? = null,
    val timestamp: Long = System.currentTimeMillis()
)
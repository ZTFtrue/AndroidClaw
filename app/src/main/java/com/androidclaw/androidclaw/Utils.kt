package com.androidclaw.androidclaw

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.androidclaw.androidclaw.model.AiAction
import com.androidclaw.androidclaw.model.ApiConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Utils {
    fun buildSystemPrompt(userGoal: String): String {
        return """
    You are an expert Android Automation Agent.
    
    ULTIMATE GOAL: "$userGoal"
    
    OPERATING PROTOCOL:
    1. PERSISTENCE: You must remember the ULTIMATE GOAL across multiple steps. Do not get distracted by intermediate screens.
    2. CONTEXT: You will be provided with a history of your PREVIOUS ACTIONS. Use this to avoid loops and detect if a click failed to change the screen.
    3. PRIORITIES:
       - 1st: Use 'intent' if a direct Android shortcut exists.
       - 2nd: Use 'click' to interact with UI elements.
       - 3rd: Use 'sh' (root) for advanced system settings.
       - 4th: Use 'finish' ONLY when the ULTIMATE GOAL is fully achieved.

    RESPONSE FORMAT (Strict JSON):
    {
      "progress": "Summary of steps completed so far in user's language",
      "reason": "Why this specific next step is needed in user's language",
      "type": "intent" | "click" | "sh" | "finish",
      "action": "android.intent.action.VIEW",
      "extras": {},
      "x": 0, "y": 0,
      "command": "input tap x y",
      "confirmation_required": true/false
    }
    
    LANGUAGE RULE: Write "progress" and "reason" in the same language as the user's goal.
    """.trimIndent()
    }

    suspend fun callLLM(prompt: String, config: ApiConfig): String =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()

            // 统一转换为 OpenAI 兼容格式 (Gemini 1.5 现已支持 OpenAI 格式)
            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                // 降低随机性，让 AI 严格遵守 JSON
                put("temperature", 0.1)
            }

            val request = Request.Builder()
                .url(config.apiUrl.ifEmpty { "https://api.openai.com/v1/chat/completions" })
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${config.apiKey}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("API Error: ${response.code}")
                val body = response.body.string()

                // 解析内容
                val jsonResponse = JSONObject(body)
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        }

    fun parseAction(rawResponse: String): AiAction {
        val gson = Gson()
        return try {
            // 1. Try to extract content between ```json and ``` or just ``` and ```
            val regex = "(?s)```(?:json)?\\s*(.*?)\\s*```".toRegex()
            val match = regex.find(rawResponse)
            val jsonContent = match?.groups?.get(1)?.value ?: run {
                // 2. Fallback: Find the first '{' and the last '}'
                val start = rawResponse.indexOf("{")
                val end = rawResponse.lastIndexOf("}")
                if (start != -1 && end != -1) {
                    rawResponse.substring(start, end + 1)
                } else {
                    rawResponse // Hope it's just raw JSON
                }
            }

            // 3. Clean up any accidental invisible characters
            val cleanJson = jsonContent.trim()

            // 4. Parse to Object
            val action = gson.fromJson(cleanJson, AiAction::class.java)

            // Basic validation: ensure type is present
            if (action.type.isNullOrEmpty()) throw Exception("AI returned empty action type")

            action
        } catch (e: Exception) {
            Log.e("Parser", "Failed to parse AI response: $rawResponse", e)
            // Return a safe error action
            AiAction(
                type = "error",
                reason = "Failed to parse AI response. Please check API output format.",
                progress = "Error"
            )
        }
    }


    suspend fun callLLMWithHistory(
        userGoal: String,
        screenData: String,
        history: List<Map<String, String>>,
        config: ApiConfig,
        context: Context
    ): String = withContext(Dispatchers.IO) {

        // 默认的错误返回 JSON（用于让后续逻辑识别出错误）
        val errorJsonStub = { message: String ->
            "{\"type\": \"error\", \"reason\": \"$message\", \"progress\": \"Error\"}"
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            // 1. 构建 System Prompt
            val systemPrompt = """
            You are an Android Automation Agent.
            Your Ultimate Goal: "$userGoal"
            
            INITIAL CONTEXT:
            - You are currently running inside the "AndroidClaw Agent" app.
            - If the goal can be achieved via a direct 'intent' (e.g., setting an alarm, opening a URL, opening app settings), use 'intent' IMMEDIATELY. This is the fastest way.
            - If you need to interact with the screen of ANOTHER app, you must first use an 'intent' to open that app or use 'click' to navigate.
            
            
            
            Rules:
            1. Use 'intent' for system-wide actions. This is the HIGHEST priority.
            2. Use 'click' for UI interaction.
            3. Use 'sh' for root-level shell commands.
            4. Use 'finish' when the task is done.
            5. Return ONLY a valid JSON object.
            
            Intent Guide:
            - To open a website or app like YouTube, use: 
              type: "intent", action: "android.intent.action.VIEW", data: "https://www.youtube.com"
            - To set an alarm, use:
              type: "intent", action: "android.intent.action.SET_ALARM", extras: {"android.intent.extra.alarm.HOUR": 8}
           
           SPECIAL RULE:
    - 'intent' actions are TERMINAL. The agent will STOP automatically after firing an intent.
    - If a task requires multiple steps (e.g., Open App THEN Click), use 'intent' to open the app in the first step, and the agent will re-scan the screen. 
    - But for simple tasks like "Set Alarm" or "Open YouTube URL", 'intent' is enough to finish the task.
           
            Output Format (Strict JSON ONLY):
            {
              "progress": "Short summary of progress",
              "reason": "Why this step?",
              "type": "intent" | "click" | "sh" | "finish",
              "action": "android.intent.action.VIEW",
              "data": "optional URI string",
              "extras": {},
              "x": 0, "y": 0,
              "command": ""
            }
        """.trimIndent()

            // 2. 构建消息数组
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            history.forEach { msg ->
                messagesArray.put(JSONObject().apply {
                    put("role", msg["role"])
                    put("content", msg["content"])
                })
            }

            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", "Current UI State:\n$screenData\n\nPerform the next step.")
            })



            val isGeminiNative = config.provider.equals("Gemini", ignoreCase = true)
            val requestBody: String
            val url: String
            val headers = mutableMapOf<String, String>()

            if (isGeminiNative) {
                // --- Google Gemini Native 格式 ---
                // 默认地址示例: https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent
                url = if (config.apiUrl.contains(":generateContent")) config.apiUrl
                else "${config.apiUrl.removeSuffix("/")}/models/${config.model}:generateContent"

                headers["x-goog-api-key"] = config.apiKey

                val contents = JSONArray()

                // Gemini 1.5 支持 system_instruction，但为了兼容性，我们将指令放入第一个 user 消息或合并历史
                // 构造历史: OpenAI role "assistant" -> Gemini role "model"
                history.forEach { msg ->
                    val role = if (msg["role"] == "assistant" || msg["role"] == "ai") "model" else "user"
                    contents.put(JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().put(JSONObject().put("text", msg["content"])))
                    })
                }

                // 当前观察和系统提示
                contents.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text",
                        "System Instructions: $systemPrompt\n\nCurrent Screen State:\n$screenData\n\nTask: Perform the next step."
                    )))
                })

                val root = JSONObject().apply {
                    put("contents", contents)
                    // 如果需要添加 tools (如 url_context)，可在此处添加
                    // put("tools", JSONArray().put(JSONObject().put("url_context", JSONObject())))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.0)
                        put("responseMimeType", "application/json") // 强制输出 JSON (Gemini 1.5+ 支持)
                    })
                }
                requestBody = root.toString()

            } else {
                // --- OpenAI / Ollama 标准格式 ---
                url = if (config.apiUrl.contains("chat/completions")) config.apiUrl
                else "${config.apiUrl.removeSuffix("/")}/chat/completions"

                headers["Authorization"] = "Bearer ${config.apiKey}"

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    history.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", if(msg["role"] == "ai") "assistant" else msg["role"])
                            put("content", msg["content"])
                        })
                    }
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Current Screen State:\n$screenData\n\nWhat is the next step?")
                    })
                }
                val root = JSONObject().apply {
                    put("model", config.model)
                    put("messages", messagesArray)
                    put("temperature", 0.0)
                }
                requestBody = root.toString()
            }

            val request = Request.Builder().url(url).post(requestBody.toRequestBody("application/json".toMediaType()))
            headers.forEach { (k, v) -> request.addHeader(k, v) }


            client.newCall(request.build()).execute().use { response ->
                val responseString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e("AGENT_API", "Error ${response.code}: $responseString")
                    return@withContext errorJsonStub("API Error ${response.code}")
                }

                val jsonRes = JSONObject(responseString)

                // --- 4. PARSE RESPONSE BASED ON PROVIDER ---
                return@withContext if (isGeminiNative) {
                    // Google Path: candidates[0].content.parts[0].text
                    jsonRes.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                } else {
                    // OpenAI Path: choices[0].message.content
                    jsonRes.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            e.printStackTrace()
            showToastOnMain(context, "Network Timeout. Check your API server.")
            return@withContext errorJsonStub("Network Timeout")
        } catch (e: java.io.IOException) {
            e.printStackTrace()
            showToastOnMain(context, "Network Error: ${e.message}")
            return@withContext errorJsonStub("Connection Failed")
        } catch (e: Exception) {
            e.printStackTrace()
            val unknownError = "Unexpected error: ${e.message}"
            Log.e("LLM_CALL", unknownError)
            return@withContext errorJsonStub("System Error")
        }
    }

    /**
     * 辅助函数：安全地在主线程弹出 Toast
     */
    private fun showToastOnMain(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
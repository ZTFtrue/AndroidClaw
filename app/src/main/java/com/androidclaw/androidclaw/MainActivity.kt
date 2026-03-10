package com.androidclaw.androidclaw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.androidclaw.androidclaw.model.AgentUiState
import com.androidclaw.androidclaw.model.AiAction
import com.androidclaw.androidclaw.model.ApiConfig
import com.androidclaw.androidclaw.model.ChatMessage
import com.androidclaw.androidclaw.ui.view.ChatBubble
import com.androidclaw.androidclaw.ui.view.InputArea
import kotlinx.coroutines.*
import java.util.Locale
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    // --- State Management ---
    private var messages by mutableStateOf(listOf<ChatMessage>())
    private var config by mutableStateOf(ApiConfig())
    private var isRecording by mutableStateOf(false)
    private var uiState by mutableStateOf(AgentUiState())
    private var pendingAction by mutableStateOf<AiAction?>(null)
    private var isAgentRunning = false
    private var agentJob: Job? = null
    private var lastActions = mutableListOf<String>()

    // --- Components ---
    private var speechRecognizer: SpeechRecognizer? = null
    private val floatingWindow by lazy { FloatingStopWindow(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // 1. Sensitive Action Confirmation Dialog
                pendingAction?.let { action ->
                    ActionConfirmDialog(
                        action = action,
                        onConfirm = {
                            val toExecute = action
                            pendingAction = null
                            performConfirmedAction(toExecute)
                        },
                        onDismiss = {
                            pendingAction = null
                            stopAiAgent()
                        }
                    )
                }

                // 2. Main UI Surface
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainContainer()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContainer() {
        var showSettings by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AndroidClaw AI Agent") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                InputArea(
                    onSend = { text -> startAgent(text) },
                    onVoiceClick = { toggleVoiceInput() },
                    isRecording = isRecording
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                if (showSettings) {
                    ApiSettingsDialog(
                        config = config,
                        onSave = { config = it; showSettings = false },
                        onDismiss = { showSettings = false }
                    )
                }
                ChatList(messages = messages, modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    fun ChatList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(
                    msg = message,
                    onConfirmAction = { action ->
                        pendingAction = action
                    }
                )
            }
        }
    }

    @Composable
    fun ActionConfirmDialog(action: AiAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Sensitive Action Request") },
            text = {
                Column {
                    Text(
                        "Action Type: ${action.type.uppercase()}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Reason: ${action.reason ?: "No reason provided"}",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    if (action.type == "sh") Text("Command: ${action.command}", color = Color.Red)
                    if (action.type == "click") Text("Coordinates: (${action.x}, ${action.y})")
                }
            },
            confirmButton = { Button(onClick = onConfirm) { Text("Allow") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    // --- Agent Logic & Controls ---

    private fun startAgent(input: String) {
        if (MyAiAccessibilityService.instance == null) {
            addMessage("system", "Please enable Accessibility Service first!")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            addMessage("system", "Please enable Overlay Permission for the Stop button!")
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        addMessage("user", input)
        isAgentRunning = true
        uiState = uiState.copy(isRunning = true, userInput = input)
        lastActions.clear()

        // Show Global Stop Button
        floatingWindow.show { stopAiAgent() }

        // Move app to background to reveal target app
//        moveTaskToBack(true)

        agentJob = lifecycleScope.launch {
            delay(1500)
            executeAgentStep(input)
        }
    }

    private fun stopAiAgent() {
        isAgentRunning = false
        uiState = uiState.copy(isRunning = false, status = "Agent Stopped.")
        floatingWindow.dismiss()
        agentJob?.cancel()
        pendingAction = null
    }

    private suspend fun executeAgentStep(userInput: String) {
        if (!isAgentRunning) return

        withContext(Dispatchers.Main) {
            uiState = uiState.copy(status = "AI is analyzing screen...")
        }

        val screenData = MyAiAccessibilityService.instance?.dumpScreenInfo() ?: "Screen data inaccessible"

        // Build History-Aware Context
        val historyContext = messages.takeLast(6).map {
            mapOf("role" to (if (it.role == "ai") "assistant" else "user"), "content" to it.content)
        }

        try {
            // Using enhanced LLM call with history to maintain goal stability
            val response = Utils.callLLMWithHistory(userInput, screenData, historyContext, config,this@MainActivity)
            val action = Utils.parseAction(response)
            if (action.type == "error") {
                // 这里处理错误，比如停止运行动画，通知用户
                addMessage("system", "Error occurred: ${action.reason}")
                stopAiAgent()
            } else {
                withContext(Dispatchers.Main) {
                    val aiDisplayMessage = "[Progress: ${action.progress ?: "Executing"}]\n${action.reason ?: "Thinking..."}"
                    addMessage("ai", aiDisplayMessage, action)
                    handleAction(action)
                }
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                addMessage("system", "AI Request Failed: ${e.message}")
                stopAiAgent()
            }
        }
    }

    private fun handleAction(action: AiAction) {
        if (!isAgentRunning) return

        // Anti-Loop Detection
        val fingerprint = "${action.type}_${action.x}_${action.y}"
        lastActions.add(fingerprint)
        if (lastActions.size > 3) lastActions.removeAt(0)
        if (lastActions.size == 3 && lastActions.distinct().size == 1) {
            addMessage("system", "Infinite loop detected. Requesting AI to change strategy...")
        }

        when (action.type) {
            "intent" -> {
                addMessage("ai", action.reason ?: "I will use a system shortcut.", action)
                executeIntent(action)

                // 特殊逻辑：如果是设置闹钟或发邮件，通常一步到位
                if (action.action?.contains("ALARM") == true || action.action?.contains("SEND") == true) {
                    addMessage("system", "✅ Task dispatched via system.")
                    stopAiAgent()
                } else {
                    // 如果是“打开某个 App”，我们需要继续观察那个 App 的界面
                    addMessage("system", "App opened, checking next step...")
                    // 节省token
                    stopAiAgent()
                    lifecycleScope.launch {
                        delay(3000) // 等待目标 App 启动
                        executeAgentStep(uiState.userInput)
                    }
                }
            }

            "click", "sh" -> {
                // 只有当 AI 决定点按时，如果当前还在本 App 界面，
                // 此时建议加入一个逻辑：如果 AI 想要点击的是桌面元素，
                // 它应该已经先通过 Intent 跳转出去了。
                pendingAction = action
                uiState = uiState.copy(status = "Awaiting authorization for ${action.type}")
            }

            "finish" -> {
                addMessage("system", "🏁 Finished.")
                stopAiAgent()
            }

            "error" -> {
                addMessage("system", "❌ AI Error: ${action.reason}")
                stopAiAgent()
            }
            else -> {
                addMessage("system", "Unknown action: ${action.type}")
                stopAiAgent()
            }
        }
    }

    private fun performConfirmedAction(action: AiAction) {
        if (!isAgentRunning) return

        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            try {
                when (action.type) {
                    "click" -> {
                        withContext(Dispatchers.Main) {
                            MyAiAccessibilityService.instance?.performClick(action.x, action.y)
                        }
                        success = true
                    }
                    "sh" -> {
                        success = ShellUtils.executeCommand(action.command ?: "", useRoot = true)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addMessage("system", "Execution Exception: ${e.message}") }
            }

            if (success && isAgentRunning) {
                withContext(Dispatchers.Main) {
                    addMessage("system", "Action success. Waiting for UI refresh...")
                }
                delay(2500)
                executeAgentStep(uiState.userInput)
            } else {
                withContext(Dispatchers.Main) { stopAiAgent() }
            }
        }
    }

    private fun executeIntent(action: AiAction) {
        try {
           Intent(action.action).let {
               if (!action.data.isNullOrEmpty()) {
                   it.data = action.data.toUri()
               }
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                action.fillIntentExtras(it)
                startActivity(it)
//                action.extras?.forEach { (key, value) ->
//                    when (value) {
//                        is Boolean -> putExtra(key, value)
//                        is Int -> putExtra(key, value)
//                        is Double -> putExtra(key, value.toInt())
//                        else -> putExtra(key, value.toString())
//                    }
//                }

            }
        } catch (e: Exception) {
            addMessage("system", "Intent failed: ${e.message}")
        }
    }

    // --- Speech-to-Text (STT) ---

    private fun toggleVoiceInput() {
        if (isRecording) stopVoiceRecognition() else startVoiceRecognition()
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
        }
    }

    private fun startVoiceRecognition() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (speechRecognizer == null) initSpeechRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        isRecording = true
        uiState = uiState.copy(status = "Listening...")
        speechRecognizer?.startListening(intent)
    }

    private fun stopVoiceRecognition() {
        isRecording = false
        speechRecognizer?.stopListening()
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isRecording = false }
        override fun onError(error: Int) {
            isRecording = false
            addMessage("system", "Voice Recognition Error: $error")
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                startAgent(matches[0])
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // --- Helpers ---

    private fun addMessage(role: String, content: String, action: AiAction? = null) {
        runOnUiThread {
            messages = messages + ChatMessage(role, content, action)
            Log.d("Agent", "[$role]: $content")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        floatingWindow.dismiss()
    }
}

// --- Settings Dialog (English) ---

@Composable
fun ApiSettingsDialog(config: ApiConfig, onSave: (ApiConfig) -> Unit, onDismiss: () -> Unit) {
    var tempConfig by remember { mutableStateOf(config) }
    var expanded by remember { mutableStateOf(false) }
    val providers = listOf("Gemini", "OpenAI", "Ollama")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI API Configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Provider: ${tempConfig.provider}")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        providers.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p) },
                                onClick = { tempConfig = tempConfig.copy(provider = p); expanded = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = tempConfig.apiUrl,
                    onValueChange = { tempConfig = tempConfig.copy(apiUrl = it) },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tempConfig.apiKey,
                    onValueChange = { tempConfig = tempConfig.copy(apiKey = it) },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tempConfig.model,
                    onValueChange = { tempConfig = tempConfig.copy(model = it) },
                    label = { Text("Model Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(tempConfig) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
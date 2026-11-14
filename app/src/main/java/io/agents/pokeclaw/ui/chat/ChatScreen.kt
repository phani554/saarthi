// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

import android.text.format.DateUtils
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.skill.Skill
import io.agents.pokeclaw.agent.skill.SkillCategory
import io.agents.pokeclaw.agent.skill.SkillRegistry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.data.memory.MemorySource
import io.agents.pokeclaw.service.VoiceEngineState
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.ui.WidthClass
import io.agents.pokeclaw.ui.edgePadding
import io.agents.pokeclaw.ui.screenHeight
import io.agents.pokeclaw.ui.screenWidth
import io.agents.pokeclaw.ui.widthClass
import io.agents.pokeclaw.ui.widthFraction
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PokeClaw Chat Screen — Jetpack Compose
 * Inspired by WhatsApp/Telegram/Slack dark theme
 */

// ======================== THEME COLORS ========================

data class PokeclawColors(
    val background: Color,
    val surface: Color,
    val userBubble: Color,
    val userText: Color,
    val aiBubble: Color,
    val aiBubbleBorder: Color,
    val aiText: Color,
    val avatar: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val inputBorder: Color,
)

val AbyssDark = PokeclawColors(
    background = Color(0xFF0C111B),
    surface = Color(0xFF151D2E),
    userBubble = Color(0xFF2563EB),
    userText = Color.White,
    aiBubble = Color(0xFF1E2D45),
    aiBubbleBorder = Color(0xFF2A3D5A),
    aiText = Color(0xFFD0DAE8),
    avatar = Color(0xFF1D4ED8),
    accent = Color(0xFF60A5FA),
    textPrimary = Color(0xFFECECF1),
    textSecondary = Color(0xFFA3A3B5),
    textTertiary = Color(0xFF52526E),
    divider = Color(0xFF1A2234),
    inputBorder = Color(0xFF1E293B),
)

private fun Modifier.dismissKeyboardOnBackgroundTap(onDismissKeyboard: () -> Unit): Modifier =
    pointerInput(onDismissKeyboard) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null) {
                onDismissKeyboard()
            }
        }
    }

// ======================== MAIN SCREEN ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    modelStatus: String,
    needsPermission: Boolean,
    isAwaitingReply: Boolean,
    isTaskRunning: Boolean,
    isDownloading: Boolean = false,
    downloadProgress: Int = 0,
    isLocalModel: Boolean = true,
    sessionTokens: Int = 0,
    sessionCost: Double = 0.0,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onVoiceInputTap: (() -> Unit)? = null,
    onStartMonitor: (MonitorTargetSpec) -> Unit = {},
    onSendDirectMessage: (contact: String, app: String, message: String) -> Unit = { _, _, _ -> },
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onFixPermissions: () -> Unit,
    onAttach: () -> Unit,
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit = {},
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit = { _, _ -> },
    activeTasks: List<String> = emptyList(),
    onStopTask: (String) -> Unit = {},
    onStopAllTasks: () -> Unit = {},
    onKillTask: () -> Unit = onStopAllTasks,
    inputEnabled: Boolean = true,
    onModelSwitch: (modelId: String, displayName: String) -> Unit = { _, _ -> },
    colors: PokeclawColors = AbyssDark,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Shared state for prompt chip → input bar prefill
    var prefillText by remember { mutableStateOf("") }
    var prefillIsTask by remember { mutableStateOf(false) }
    // Task mode state — lifted here so content area can react
    var isTaskMode by remember { mutableStateOf(false) }
    // Local/Cloud tab — controls UI presentation AND triggers model switch.
    // Keep the tab aligned with the actual active model so returning from
    // Settings/model changes cannot leave the toolbar UI out of sync.
    var selectedTab by remember { mutableStateOf(if (isLocalModel) "local" else "cloud") }
    // Everything that describes or routes by mode (placeholder, header, Chat/Task toggle, send
    // routing) follows the model that is actually active. The tab is only the switch control.
    val isLocalUI = isLocalModel
    val noModel = modelStatus.startsWith("No model")
    // Skill dialog and activation states
    var showMonitorSheet by remember { mutableStateOf(false) }
    var showSendSheet by remember { mutableStateOf(false) }
    var activatingSkill by remember { mutableStateOf<String?>(null) }

    // Chat mode is always the default — user can switch to Task manually

    // When activating finishes (2s animation), clear state
    LaunchedEffect(activatingSkill) {
        if (activatingSkill != null) {
            kotlinx.coroutines.delay(2000)
            activatingSkill = null
        }
    }

    // Re-sync the Local/Cloud tab with the model that is actually active. Before, tapping a tab
    // whose model failed to activate (or switching from Settings) left "Local" highlighted while a
    // Cloud model ran, and the whole screen (placeholder, welcome card, task gating) followed the tab.
    LaunchedEffect(isLocalModel, modelStatus) {
        if (!modelStatus.startsWith("No model")) {
            selectedTab = if (isLocalModel) "local" else "cloud"
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
            ) {
                SidebarContent(
                    conversations = conversations,
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onNewChat()
                    },
                    onSelectConversation = {
                        scope.launch { drawerState.close() }
                        onSelectConversation(it)
                    },
                    onDeleteConversation = onDeleteConversation,
                    onRenameConversation = onRenameConversation,
                    onSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onModels = {
                        scope.launch { drawerState.close() }
                        onOpenModels()
                    },
                    colors = colors,
                )
            }
        }
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                Column(
                    modifier = Modifier.dismissKeyboardOnBackgroundTap(dismissKeyboard)
                ) {
                    ChatTopBar(
                        modelStatus = modelStatus,
                        sessionTokens = sessionTokens,
                        sessionCost = sessionCost,
                        isLocalModel = isLocalModel,
                        selectedTab = selectedTab,
                        onTabChange = { tab ->
                            selectedTab = tab
                            val kvUtils = io.agents.pokeclaw.utils.KVUtils
                            if (tab == "cloud") {
                                // Check if cloud default model is configured
                                if (kvUtils.hasDefaultCloudModel()) {
                                    val modelId = kvUtils.getDefaultCloudModel()
                                    val provider = io.agents.pokeclaw.agent.CloudProvider.fromName(
                                        kvUtils.getDefaultCloudProvider().ifBlank { kvUtils.getLlmProvider() }
                                    )
                                    val displayName = provider.models.find { it.id == modelId }?.displayName ?: modelId
                                    onModelSwitch(modelId, displayName)
                                } else {
                                    // No cloud model configured — signal "no model" state
                                    io.agents.pokeclaw.utils.XLog.i("ChatScreen", "Cloud tab: no default cloud model configured")
                                    onModelSwitch("NONE", "")
                                }
                            } else {
                                // Check if local default model is configured
                                if (kvUtils.hasDefaultLocalModel()) {
                                    val localPath = kvUtils.getLocalModelPath()
                                    val name = java.io.File(localPath).nameWithoutExtension
                                        .replace("-", " ").replace("_", " ")
                                    onModelSwitch("LOCAL", name)
                                } else {
                                    // No local model configured — signal "no model" state
                                    io.agents.pokeclaw.utils.XLog.i("ChatScreen", "Local tab: no default local model configured")
                                    onModelSwitch("NONE", "")
                                }
                            }
                        },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSettings = onOpenSettings,
                        onModelSwitch = onModelSwitch,
                        colors = colors,
                    )
                    if (activeTasks.isNotEmpty()) {
                        ActiveTaskBar(
                            tasks = activeTasks,
                            onStopTask = onStopTask,
                            onStopAll = onStopAllTasks,
                            colors = colors,
                        )
                    }
                }
            },
            bottomBar = {
                if (!isDownloading) {
                    Column(
                        modifier = Modifier.imePadding()
                    ) {
                        // Quick Tasks collapsible panel (v9 style)
                        QuickTasksPanel(
                            isLocalModel = isLocalUI,
                            onFillTask = { text ->
                                prefillText = text
                                prefillIsTask = true
                                if (isLocalUI) isTaskMode = true
                            },
                            onMonitorClick = { showMonitorSheet = true },
                            monitorActive = activeTasks.isNotEmpty(),
                            colors = colors,
                        )

                        ChatInputBar(
                            isAwaitingReply = isAwaitingReply,
                            isTaskRunning = isTaskRunning,
                            inputEnabled = inputEnabled,
                            isTaskMode = isTaskMode,
                            isLocalModel = isLocalUI,
                            onTaskModeChange = { isTaskMode = it },
                            onSendChat = onSendChat,
                            onSendTask = onSendTask,
                            onVoiceInputTap = onVoiceInputTap,
                            onStopAll = onKillTask,
                            onAttach = onAttach,
                            colors = colors,
                            prefillText = prefillText,
                            prefillIsTask = prefillIsTask,
                            onPrefillConsumed = { prefillText = "" },
                            noModel = noModel,
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .dismissKeyboardOnBackgroundTap(dismissKeyboard)
            ) {
                if (!isDownloading) {
                    // v9: always show messages or empty state regardless of mode
                    val userMessages = messages.filter { it.role != ChatMessage.Role.SYSTEM }
                    if (userMessages.isEmpty()) {
                        EmptyStateWithPrompts(
                            isLocalModel = isLocalUI,
                            modeLine = modeLine(modelStatus, isLocalModel),
                            onSelectPrompt = { text, isTask ->
                                prefillText = text
                                prefillIsTask = isTask
                                if (isTask && isLocalUI) isTaskMode = true
                            },
                            colors = colors,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MessageList(
                            messages = messages,
                            isTaskRunning = isTaskRunning,
                            colors = colors,
                            onBackgroundTap = dismissKeyboard,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Download blocking overlay
                if (isDownloading) {
                    DownloadOverlay(progress = downloadProgress, colors = colors)
                }
            }
        }
    }

    // Monitor skill dialog
    if (showMonitorSheet) {
        MonitorDialog(
            onDismiss = { showMonitorSheet = false },
            onStart = { target ->
                showMonitorSheet = false
                activatingSkill = "monitor"
                onStartMonitor(target)
            },
            colors = colors,
        )
    }

    // Send Message skill dialog
    if (showSendSheet) {
        SendMessageDialog(
            onDismiss = { showSendSheet = false },
            onSend = { contact, app, message ->
                showSendSheet = false
                onSendDirectMessage(contact, app, message)
            },
            colors = colors,
        )
    }
}

// ======================== TOP BAR ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelStatus: String,
    sessionTokens: Int = 0,
    sessionCost: Double = 0.0,
    isLocalModel: Boolean = true,
    selectedTab: String,
    onTabChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    onSettings: () -> Unit,
    onModelSwitch: (modelId: String, displayName: String) -> Unit = { _, _ -> },
    colors: PokeclawColors,
) {
    // Token count color: grey → blue → amber → red
    val tokenColor = when {
        sessionTokens < 5000 -> colors.textTertiary
        sessionTokens < 15000 -> Color(0xFF60A5FA) // blue
        sessionTokens < 25000 -> Color(0xFFFBBF24) // amber
        else -> Color(0xFFF87171) // soft red
    }

    Column {
        var showModelMenu by remember { mutableStateOf(false) }

        TopAppBar(
            title = {
                Column {
                    Text(
                        buildAnnotatedString {
                            append("Saa")
                            withStyle(SpanStyle(color = colors.accent)) {
                                append("thi")
                            }
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                    // Small mode line: follows the model that is actually active, not the tab.
                    Text(
                        modeLine(modelStatus, isLocalModel),
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                // One-tap SOS: texts and calls the care contacts in priority order.
                // Two taps within 4 s, so a stray touch in the toolbar never phones anyone.
                // Big filled red button, sized from the screen width.
                val sosContext = androidx.compose.ui.platform.LocalContext.current
                var sosArmedAt by remember { mutableStateOf(0L) }
                val sosArmed = sosArmedAt != 0L && System.currentTimeMillis() - sosArmedAt < 4_000L
                LaunchedEffect(sosArmedAt) {
                    if (sosArmedAt != 0L) {
                        kotlinx.coroutines.delay(4_000L)
                        sosArmedAt = 0L
                    }
                }
                Surface(
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (now - sosArmedAt < 4_000L) {
                            sosArmedAt = 0L
                            // trigger() speaks through VoiceManager and may wait on it: keep it off the main thread.
                            val appContext = sosContext.applicationContext
                            Thread {
                                val msg = try {
                                    io.agents.pokeclaw.care.DistressEscalator.trigger(appContext, "chat button")
                                } catch (e: Exception) {
                                    "SOS could not start: ${e.message}"
                                }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }.start()
                        } else {
                            sosArmedAt = now
                            android.widget.Toast.makeText(sosContext, "Tap SOS again to call for help", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(50),
                    color = if (sosArmed) Color(0xFF7F0000) else Color(0xFFD32F2F),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(widthFraction(0.2f, 64.dp, 96.dp))
                        .heightIn(min = 44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            if (sosArmed) "AGAIN" else "SOS",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (sosArmed) 13.sp else 17.sp,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                navigationIconContentColor = colors.textPrimary,
                actionIconContentColor = colors.textSecondary,
            ),
        )

        // Model status + dropdown — filtered by selected tab
        Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .clickable { showModelMenu = true }
                .padding(horizontal = edgePadding(), vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = modelStatus,
                fontSize = 11.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = "Switch model",
                tint = colors.textTertiary,
                modifier = Modifier.size(12.dp),
            )
            if (sessionTokens > 0 && !isLocalModel) {
                val formattedTokens = if (sessionTokens >= 1000) {
                    String.format("%.1fK", sessionTokens / 1000.0)
                } else {
                    "$sessionTokens"
                }
                val costText = if (sessionCost < 0.01) "< $0.01" else "$${String.format("%.2f", sessionCost)}"
                val tokenSuffix = if (!isLocalModel && sessionCost > 0) {
                    " · $formattedTokens tokens · $costText"
                } else {
                    " · $formattedTokens tokens"
                }
                Text(
                    text = tokenSuffix,
                    fontSize = 11.sp,
                    color = tokenColor,
                )
            }
            Spacer(Modifier.weight(1f))
            // Local/Cloud switch — moved here from the top bar so SOS has room on narrow screens.
            ModeTab("Local", selectedTab == "local", colors) { onTabChange("local") }
            Spacer(Modifier.width(4.dp))
            ModeTab("Cloud", selectedTab == "cloud", colors) { onTabChange("cloud") }
        }
            // Model switcher dropdown — only show configured/downloaded models
            DropdownMenu(
                expanded = showModelMenu,
                onDismissRequest = { showModelMenu = false },
            ) {
                val kvUtils = io.agents.pokeclaw.utils.KVUtils
                val apiKey = kvUtils.getLlmApiKey()
                val baseUrl = kvUtils.getLlmBaseUrl()
                val currentModel = kvUtils.getLlmModelName()

                if (selectedTab == "cloud") {
                    // Cloud models: from configured provider
                    if (apiKey.isNotEmpty()) {
                        val activeProvider = io.agents.pokeclaw.agent.CloudProvider.entries.find {
                            it.defaultBaseUrl == baseUrl
                        }
                        val modelsToShow = activeProvider?.models
                            ?: io.agents.pokeclaw.agent.CloudProvider.OPENAI.models
                        modelsToShow.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            model.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = if (model.id == currentModel && !isLocalModel) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (model.id == currentModel && !isLocalModel) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("✓", fontSize = 12.sp, color = colors.accent)
                                        }
                                    }
                                },
                                onClick = {
                                    showModelMenu = false
                                    onModelSwitch(model.id, model.displayName)
                                },
                            )
                        }
                    } else {
                        // No API key configured
                        DropdownMenuItem(
                            text = { Text("No API key configured", fontSize = 13.sp, color = colors.textTertiary) },
                            onClick = { showModelMenu = false; onSettings() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Configure API key...", fontSize = 13.sp, color = colors.accent) },
                        onClick = { showModelMenu = false; onSettings() },
                    )
                } else {
                    // Local models: downloaded models
                    val localPath = kvUtils.getLocalModelPath()
                    if (localPath.isNotEmpty() && java.io.File(localPath).exists()) {
                        val localName = java.io.File(localPath).nameWithoutExtension
                            .replace("-", " ").replace("_", " ")
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$localName (On-device)", fontSize = 13.sp,
                                        fontWeight = if (isLocalModel) FontWeight.Bold else FontWeight.Normal)
                                    if (isLocalModel) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("✓", fontSize = 12.sp, color = colors.accent)
                                    }
                                }
                            },
                            onClick = {
                                showModelMenu = false
                                onModelSwitch("LOCAL", localName)
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("No local model downloaded", fontSize = 13.sp, color = colors.textTertiary) },
                            onClick = { showModelMenu = false; onSettings() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Download models...", fontSize = 13.sp, color = colors.accent) },
                        onClick = { showModelMenu = false; onSettings() },
                    )
                }
            }
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, colors: PokeclawColors, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.aiBubble else Color.Transparent,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 12.sp,
                color = if (selected) colors.accent else colors.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * One honest line about which AI is answering. Built from the active model and its status text
 * (ChatSessionController writes "No model selected", "● name · Cloud", "● name · On-device"...).
 */
internal fun modeLine(modelStatus: String, isLocalModel: Boolean): String = when {
    modelStatus.startsWith("No model") -> "No AI set up yet · Settings mein chuniye"
    modelStatus.startsWith("Downloading") -> "Getting ready · Taiyaar ho raha hai"
    isLocalModel -> "Local AI · on this phone"
    else -> "Cloud AI · uses internet"
}

// ======================== PERMISSION BANNER ========================

@Composable
private fun PermissionBanner(onClick: () -> Unit, colors: PokeclawColors) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.accent.copy(alpha = 0.12f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Permissions needed. Tap to fix.",
                color = colors.accent,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

// ======================== MESSAGE LIST ========================

/** What the list draws: a normal message, or a run of agent steps folded into one row. */
private sealed class ChatRow {
    data class Single(val message: ChatMessage) : ChatRow()
    data class Steps(val firstIndex: Int, val steps: List<ToolStep>) : ChatRow()
}

/** Steps of one TOOL_GROUP message. History reloaded from disk only keeps the text lines. */
private fun stepsOf(message: ChatMessage): List<ToolStep> =
    message.toolSteps?.takeIf { it.isNotEmpty() }
        ?: message.content.lines()
            .map { it.trim().removePrefix("- ").trim().removeSuffix("→").trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                ToolStep(toolName = line.removePrefix("✓ ").removePrefix("○ "), summary = "", success = !line.startsWith("○"))
            }

/** Folds consecutive agent-step messages (TOOL_GROUP) into one row so they do not flood the chat. */
private fun buildChatRows(messages: List<ChatMessage>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (m.role == ChatMessage.Role.TOOL_GROUP) {
            val start = i
            val steps = mutableListOf<ToolStep>()
            while (i < messages.size && messages[i].role == ChatMessage.Role.TOOL_GROUP) {
                steps += stepsOf(messages[i])
                i++
            }
            if (steps.isNotEmpty()) rows += ChatRow.Steps(start, steps)
        } else {
            rows += ChatRow.Single(m)
            i++
        }
    }
    return rows
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isTaskRunning: Boolean = false,
    colors: PokeclawColors,
    onBackgroundTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = remember(messages) { buildChatRows(messages) }

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(rows.size - 1) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onBackgroundTap) {
                detectTapGestures(onTap = { onBackgroundTap() })
            },
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(rows.size) { index ->
            when (val row = rows[index]) {
                is ChatRow.Single -> {
                    val message = row.message
                    when (message.role) {
                        ChatMessage.Role.USER -> UserBubble(message.content, message.timestamp, colors)
                        ChatMessage.Role.ASSISTANT -> AssistantBubble(message.content, message.timestamp, colors, message.modelName)
                        ChatMessage.Role.SYSTEM -> SystemMessage(message.content, colors)
                        ChatMessage.Role.TOOL_GROUP -> Unit // folded into ChatRow.Steps
                    }
                }
                is ChatRow.Steps -> {
                    // Live while a task runs and nothing but the typing bubble follows this run.
                    val laterRows = rows.subList(index + 1, rows.size)
                    val live = isTaskRunning && laterRows.all {
                        it is ChatRow.Single && it.message.role == ChatMessage.Role.ASSISTANT && it.message.content == "..."
                    }
                    StepGroup(row.firstIndex, row.steps, live, colors)
                }
            }
        }
    }
}

// ======================== BUBBLES ========================

@Composable
private fun UserBubble(text: String, timestamp: Long, colors: PokeclawColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = bubbleInset(), end = edgePadding(), top = 3.dp, bottom = 3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = colors.userBubble,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            ) {
                Text(
                    text = text,
                    color = colors.userText,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Text(
            text = formatBubbleTimestamp(timestamp),
            fontSize = 9.sp,
            color = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 6.dp, top = 1.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun AssistantBubble(text: String, timestamp: Long, colors: PokeclawColors, modelName: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = edgePadding(), end = bubbleInset(), top = 3.dp, bottom = 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Avatar
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.pokeclaw_avatar),
                contentDescription = "Saathi",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(8.dp))

            // Bubble
            if (text == "...") {
                Surface(
                    color = colors.aiBubble,
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                ) {
                    TypingIndicator(
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            } else {
                Surface(
                    color = colors.aiBubble,
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                ) {
                    Text(
                        text = text,
                        color = colors.aiText,
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (text != "...") {
            val footer = listOfNotNull(
                modelName?.takeIf { it.isNotBlank() },
                formatBubbleTimestamp(timestamp)
            ).joinToString(" · ")
            Text(
                text = footer,
                fontSize = 9.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 40.dp, top = 1.dp, bottom = 2.dp),
            )
        }
    }
}

private fun formatBubbleTimestamp(timestamp: Long): String {
    val pattern = if (DateUtils.isToday(timestamp)) "h:mm a" else "MMM d, h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun TypingIndicator(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dots = listOf(0, 1, 2)

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dots.forEach { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun SystemMessage(text: String, colors: PokeclawColors) {
    Text(
        text = text,
        color = colors.textTertiary,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = edgePadding() * 2, vertical = 6.dp),
    )
}

/**
 * Agent steps ("Tap Node", "Wait", "Open App"...) folded into one line: "Working: step 5" while the
 * task runs, "Done in 5 steps" after. Tap to see the individual steps. The final answer is a normal
 * assistant bubble and is never folded.
 */
@Composable
private fun StepGroup(groupKey: Int, steps: List<ToolStep>, live: Boolean, colors: PokeclawColors) {
    var expanded by remember(groupKey) { mutableStateOf(false) }
    val failed = steps.count { !it.success }
    val last = steps.lastOrNull()?.let { stepLabel(it) }.orEmpty()
    val title = if (live) "⚙️ Working: step ${steps.size}" else "✓ Done in ${steps.size} step${if (steps.size == 1) "" else "s"}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = edgePadding() + 40.dp, end = bubbleInset(), top = 3.dp, bottom = 3.dp),
    ) {
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(12.dp),
            color = colors.aiBubble.copy(alpha = 0.6f),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (live) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                    if (live && last.isNotBlank()) {
                        Text(last, fontSize = 12.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (!live && failed > 0) {
                        Text("$failed step(s) had trouble", fontSize = 12.sp, color = colors.textTertiary)
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide steps" else "Show steps",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp)) {
                steps.forEachIndexed { i, step ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (step.success) colors.accent else colors.textTertiary),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${i + 1}. ${stepLabel(step)}",
                            fontSize = 12.sp,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

/** Space kept on the far side of a chat bubble: a share of the width instead of a fixed 64dp. */
@Composable
private fun bubbleInset(): Dp = widthFraction(0.14f, 24.dp, 120.dp)

private fun stepLabel(step: ToolStep): String =
    if (step.summary.isBlank()) step.toolName else "${step.toolName} → ${step.summary}"

// ======================== INPUT BAR ========================

@Composable
private fun ChatInputBar(
    isAwaitingReply: Boolean,
    isTaskRunning: Boolean,
    inputEnabled: Boolean = true,
    isTaskMode: Boolean,
    isLocalModel: Boolean,
    onTaskModeChange: (Boolean) -> Unit,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onVoiceInputTap: (() -> Unit)? = null,
    onStopAll: () -> Unit = {},
    onAttach: () -> Unit,
    colors: PokeclawColors,
    prefillText: String = "",
    prefillIsTask: Boolean = false,
    onPrefillConsumed: () -> Unit = {},
    noModel: Boolean = false,
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                text = spokenText
            }
        }
    }
    // Consume prefill from prompt chips
    LaunchedEffect(prefillText) {
        if (prefillText.isNotEmpty()) {
            text = prefillText
            if (isLocalModel) onTaskModeChange(prefillIsTask)
            onPrefillConsumed()
        }
    }

    val taskBg = Color(0xFF1A1410)
    val taskBorder = colors.accent.copy(alpha = 0.25f)
    val voiceEngineState by VoiceManager.engineState.collectAsState()
    val activeMemorySource by HybridMemoryRepository.activeMemorySource.collectAsState()

    Column(
        modifier = Modifier
            .background(if (isTaskMode && isLocalModel) taskBg else colors.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(
            color = if (isTaskMode && isLocalModel) taskBorder else colors.divider,
            thickness = 1.dp,
        )

        // ---- One status area, stacked above the input (nothing floats over the input or tabs) ----
        StatusArea(
            isTaskRunning = isTaskRunning,
            voiceEngineState = voiceEngineState,
            showMemoryBadge = io.agents.pokeclaw.BuildConfig.DEBUG,
            isMem0 = activeMemorySource == MemorySource.MEM0_CLOUD,
            onStopAll = onStopAll,
            colors = colors,
        )

        // Segmented Chat/Task toggle — Local LLM only
        if (isLocalModel) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = edgePadding(), end = edgePadding(), top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Chat button
                Surface(
                    onClick = { onTaskModeChange(false) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (!isTaskMode) colors.aiBubble else Color.Transparent,
                    border = if (!isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "💬 Chat · Baat",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!isTaskMode) colors.textPrimary else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                // Task button
                Surface(
                    onClick = { onTaskModeChange(true) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isTaskMode) colors.accent else Color.Transparent,
                    border = if (isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.accent) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "🤖 Task · Kaam",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isTaskMode) Color.White else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }

        // Input bar — always visible, style changes in Task mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = edgePadding(), end = edgePadding(), top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val micSize = widthFraction(0.18f, 64.dp, 96.dp)
            val isRecording = voiceEngineState is VoiceEngineState.Recording
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        // Follows the active model (isLocalModel is the real mode, not the tab).
                        when {
                            noModel -> "Set up AI in Settings first"
                            isLocalModel && isTaskMode -> "Say or type a phone task…"
                            !isLocalModel -> "Type, or tap the mic and speak…"
                            else -> "Chat with Saathi (on this phone)…"
                        },
                        color = if (isTaskMode && isLocalModel) colors.accent.copy(alpha = 0.5f) else colors.textTertiary,
                        fontSize = 15.sp,
                        maxLines = 2,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = screenHeight() * 0.2f),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isTaskMode && isLocalModel) colors.accent else colors.accent.copy(alpha = 0.4f),
                    unfocusedBorderColor = if (isTaskMode && isLocalModel) colors.accent.copy(alpha = 0.6f) else colors.inputBorder,
                    cursorColor = if (isTaskMode && isLocalModel) colors.accent else colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = if (isTaskMode && isLocalModel) taskBg else Color.Transparent,
                    unfocusedContainerColor = if (isTaskMode && isLocalModel) taskBg else Color.Transparent,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                maxLines = 4,
            )

            Spacer(Modifier.width(4.dp))

            // Large mic: the main way in for elders. Red with a stop icon while recording.
            Surface(
                onClick = {
                    if (onVoiceInputTap != null) {
                        onVoiceInputTap()
                    } else {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak command or message...")
                        }
                        try {
                            speechLauncher.launch(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Speech input not available", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                shape = CircleShape,
                color = if (isRecording) Color(0xFFD32F2F) else colors.accent,
                shadowElevation = 2.dp,
                modifier = Modifier.size(micSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop listening" else "Speak",
                        tint = Color.White,
                        modifier = Modifier.size(micSize * 0.5f)
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            var isVoiceSpeakerEnabled by remember { mutableStateOf(io.agents.pokeclaw.service.VoiceManager.isVoiceOutputEnabled) }
            IconButton(
                onClick = {
                    isVoiceSpeakerEnabled = !isVoiceSpeakerEnabled
                    io.agents.pokeclaw.service.VoiceManager.isVoiceOutputEnabled = isVoiceSpeakerEnabled
                    if (!isVoiceSpeakerEnabled) {
                        io.agents.pokeclaw.service.VoiceManager.stop()
                    }
                },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (isVoiceSpeakerEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (isVoiceSpeakerEnabled) "Voice replies on" else "Voice replies off",
                    tint = if (isVoiceSpeakerEnabled) colors.accent else Color.Gray,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            FloatingActionButton(
                onClick = {
                    if (isTaskRunning) {
                        onStopAll()
                    } else if (!isAwaitingReply && inputEnabled && text.isNotBlank()) {
                        if (!isLocalModel || isTaskMode) {
                            onSendTask(text.trim())
                            text = ""
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        } else {
                            onSendChat(text.trim())
                            text = ""
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .alpha(if ((text.isBlank() || !inputEnabled || isAwaitingReply) && !isTaskRunning) 0.35f else 1f),
                containerColor = when {
                    isTaskRunning -> Color(0xFFF44336)
                    isAwaitingReply -> colors.background
                    text.isBlank() -> colors.background
                    isTaskMode && isLocalModel -> colors.accent
                    else -> colors.userBubble
                },
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
            ) {
                Icon(
                    when {
                        isTaskRunning -> Icons.Default.Close
                        isAwaitingReply -> Icons.Default.MoreHoriz
                        else -> Icons.Default.ArrowUpward
                    },
                    contentDescription = when {
                        isTaskRunning -> "Stop"
                        isAwaitingReply -> "Waiting for reply"
                        else -> "Send"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ======================== STATUS AREA ========================

/**
 * Every transient banner lives here, stacked in one column directly above the input row:
 * running task (with Stop), voice state (listening / understanding / speaking), and the memory
 * badge (debug builds only). Replaces the separate banners and the "Listening" toast that used
 * to cover the input and the Chat/Task tabs.
 */
@Composable
private fun StatusArea(
    isTaskRunning: Boolean,
    voiceEngineState: VoiceEngineState,
    showMemoryBadge: Boolean,
    isMem0: Boolean,
    onStopAll: () -> Unit,
    colors: PokeclawColors,
) {
    val voiceText = when (voiceEngineState) {
        is VoiceEngineState.Recording -> "🔴 Listening… speak now · Boliye (tap mic to stop)"
        is VoiceEngineState.Transcribing -> "✍️ Understanding what you said…"
        is VoiceEngineState.GeneratingResponse -> "💭 Thinking… · Soch raha hai"
        is VoiceEngineState.Speaking -> "🔊 Speaking… (tap 🔈 to silence)"
        is VoiceEngineState.Error -> "⚠️ Voice problem: ${voiceEngineState.message}"
        else -> ""
    }
    if (!isTaskRunning && voiceText.isEmpty() && !showMemoryBadge) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = edgePadding(), vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isTaskRunning) {
            Surface(
                color = Color(0xFF1E3A8A),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Saathi is working… · Kaam ho raha hai",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onStopAll,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Text(
                            text = "Stop · Roko",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB91C1C)
                        )
                    }
                }
            }
        }
        if (voiceText.isNotEmpty()) {
            Surface(
                color = if (voiceEngineState is VoiceEngineState.Recording) Color(0xFFD32F2F).copy(alpha = 0.18f)
                else colors.accent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = voiceText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (voiceEngineState is VoiceEngineState.Recording) Color(0xFFFF8A80) else colors.accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (showMemoryBadge) {
            // Debug builds only: which memory backend is active.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = if (isMem0) Color(0xFF1E3A8A) else Color(0xFF27272A),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isMem0) "Mem0 Cloud (debug)" else "Local Vault (debug)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMem0) Color(0xFF93C5FD) else Color(0xFFA1A1AA),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ======================== SKILL SHORTCUT BAR ========================

@Composable
private fun SkillShortcutBar(
    skills: List<Skill>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSkillTap: (Skill) -> Unit,
    colors: PokeclawColors,
) {
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.MEDIA to Icons.Outlined.CameraAlt,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    Column {
        // Toggle row
        Surface(
            onClick = onToggle,
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Skills",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Expanded skill chips
        if (expanded) {
            // Two rows of chips using FlowRow-style layout
            val rows = skills.chunked((skills.size + 1) / 2)
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (skill in row) {
                            val icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome
                            Surface(
                                onClick = { onSkillTap(skill) },
                                shape = RoundedCornerShape(20.dp),
                                color = colors.accent.copy(alpha = 0.1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        skill.name,
                                        fontSize = 11.sp,
                                        color = colors.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ======================== DOWNLOAD OVERLAY ========================

@Composable
private fun DownloadOverlay(progress: Int, colors: PokeclawColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = edgePadding() * 2),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.pokeclaw_avatar),
                    contentDescription = "Saathi",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Downloading your AI brain",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This only happens once",
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accent,
                    trackColor = colors.inputBorder,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$progress%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
            }
        }
    }
}

// ======================== TASK TEMPLATES ========================

/**
 * Elder task templates. Each one fills the input with a full, clear instruction in Task mode; the
 * person can edit it (e.g. "son" → a name) before pressing send. Ordering templates never pay:
 * the agent's PaymentGate stops before any payment step, and the wording says so.
 */
private data class TaskTemplate(
    val emoji: String,
    val title: String,
    val hindi: String,
    val instruction: String,
    val stopsBeforePayment: Boolean = false,
)

private val ELDER_TEMPLATES = listOf(
    TaskTemplate(
        "💬", "Summarise my WhatsApp chats", "WhatsApp ke recent chats ka saar",
        "Open WhatsApp and summarise my recent chats",
    ),
    TaskTemplate(
        "🔊", "Read my latest WhatsApp message aloud", "WhatsApp ka naya message padhkar sunao",
        "Open WhatsApp and read my latest message aloud",
    ),
    TaskTemplate(
        "📹", "Video call my son on WhatsApp", "WhatsApp par bete ko video call",
        "Video call my son on WhatsApp",
    ),
    TaskTemplate(
        "🩺", "Call the family doctor", "Family doctor ko phone lagao",
        "Call the family doctor",
    ),
    TaskTemplate(
        "🥛", "Order Amul milk 1 litre on Blinkit", "Blinkit se Amul doodh 1 litre",
        "Order Amul milk 1 litre on Blinkit. Add it to the cart and stop before payment so I can check.",
        stopsBeforePayment = true,
    ),
    TaskTemplate(
        "👗", "Order a dress from Flipkart", "Flipkart se dress mangao",
        "Order a dress from Flipkart. Find one, add it to the cart and stop before payment so I can choose.",
        stopsBeforePayment = true,
    ),
)

/** Big tappable template cards: 1 column on phones, 2 columns from 600dp. */
@Composable
private fun TemplateGrid(onPick: (TaskTemplate) -> Unit, colors: PokeclawColors, modifier: Modifier = Modifier) {
    val columns = if (widthClass() == WidthClass.EXPANDED) 2 else 1
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ELDER_TEMPLATES.chunked(columns).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { t ->
                    TemplateCard(t, onClick = { onPick(t) }, colors = colors, modifier = Modifier.weight(1f))
                }
                // Keep the last card half-width when a 2-column row has one item.
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TemplateCard(t: TaskTemplate, onClick: () -> Unit, colors: PokeclawColors, modifier: Modifier = Modifier) {
    val compact = widthClass() == WidthClass.COMPACT
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder),
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(t.hindi, fontSize = 14.sp, color = colors.textSecondary)
                if (t.stopsBeforePayment) {
                    Text("Stops before payment · Payment se pehle rukega", fontSize = 12.sp, color = colors.accent)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

// ======================== EMPTY STATE ========================

@Composable
private fun EmptyStateWithPrompts(
    isLocalModel: Boolean,
    modeLine: String,
    onSelectPrompt: (String, Boolean) -> Unit,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    val pad = edgePadding()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (widthClass() == WidthClass.COMPACT) 12.dp else 24.dp))
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.pokeclaw_avatar),
            contentDescription = "Saathi",
            modifier = Modifier
                .size(widthFraction(0.14f, 48.dp, 72.dp))
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Saathi",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        // Small mode line: the model that is actually active.
        Text(
            modeLine,
            fontSize = 12.sp,
            color = colors.accent,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap a task, or press the mic and speak.\nKaam chuniye, ya mic dabakar boliye.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        TemplateGrid(
            onPick = { t -> onSelectPrompt(t.instruction, true) },
            colors = colors,
            modifier = Modifier.widthIn(max = 840.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ======================== QUICK TASKS PANEL (v9) ========================

@Composable
private fun QuickTasksPanel(
    isLocalModel: Boolean,
    onFillTask: (String) -> Unit,
    onMonitorClick: () -> Unit,
    monitorActive: Boolean,
    colors: PokeclawColors,
) {
    // Collapsed by default: the empty chat already shows the same templates as big cards.
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.background(colors.surface),
    ) {
        HorizontalDivider(color = colors.divider, thickness = 1.dp)

        // Handle bar — ▲ Quick Tasks ▲
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = edgePadding(), vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide quick tasks" else "Show quick tasks",
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Quick tasks · Jaldi kaam",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide quick tasks" else "Show quick tasks",
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }

        // Collapsible content
        if (expanded) {
            // Elder task templates — scrollable, height follows the screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeight() * 0.4f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = edgePadding()),
            ) {
                TemplateGrid(onPick = { t -> onFillTask(t.instruction) }, colors = colors)
                Spacer(Modifier.height(4.dp))
            }

            // Background section — always visible, NOT inside scroll
            Column(modifier = Modifier.padding(horizontal = edgePadding())) {
                Text(
                    "BACKGROUND",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textTertiary,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )

                // Monitor card
                val monitorBorderColor = if (monitorActive) colors.accent else colors.inputBorder
                Surface(
                    onClick = {
                        if (!monitorActive) onMonitorClick()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = colors.background,
                    border = androidx.compose.foundation.BorderStroke(
                        if (monitorActive) 1.dp else 0.5.dp,
                        monitorBorderColor,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    colors.accent.copy(alpha = 0.12f),
                                    RoundedCornerShape(9.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("👁️", fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (monitorActive) "Active" else "Monitor & Auto-Reply",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                if (monitorActive) "Monitoring active — use the top bar to stop" else "Watch messages and reply automatically",
                                fontSize = 9.sp,
                                color = colors.textTertiary,
                            )
                        }
                        if (!monitorActive) {
                            Text("›", color = colors.textTertiary, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            } // end Background Column
        }
    }
}

// ======================== SIDEBAR ========================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarContent(
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onNewChat: () -> Unit,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    colors: PokeclawColors,
) {
    var actionTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Long-press action menu: Rename / Delete
    if (actionTarget != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(actionTarget!!.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            renameTarget = actionTarget
                            renameText = actionTarget!!.title
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Rename", color = colors.textPrimary)
                        }
                    }
                    TextButton(
                        onClick = {
                            deleteTarget = actionTarget
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Delete", color = Color(0xFFF87171))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Delete confirmation
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?", color = colors.textPrimary) },
            text = { Text(deleteTarget!!.title, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(deleteTarget!!)
                    deleteTarget = null
                }) { Text("Delete", color = Color(0xFFF87171)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Rename dialog
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation", color = colors.textPrimary) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.inputBorder,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty() && renameTarget != null) {
                        onRenameConversation(renameTarget!!, newName)
                    }
                    renameTarget = null
                }) { Text("Save", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(top = 48.dp),
    ) {
        // Title with logo
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.pokeclaw_avatar),
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Saathi",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
        }

        // New Chat button
        Button(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New Chat")
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(8.dp))

        // Recent label
        Text(
            "Recent",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )

        // Conversations
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet",
                        fontSize = 13.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            items(conversations.size) { index ->
                val conv = conversations[index]
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelectConversation(conv) },
                            onLongClick = { actionTarget = conv },
                        ),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Text(
                        text = conv.title,
                        fontSize = 14.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = colors.divider)

        // Bottom nav
        TextButton(
            onClick = onSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Settings", color = colors.textSecondary)
            }
        }
        TextButton(
            onClick = onModels,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Models", color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ======================== TASK SKILLS PANEL ========================

@Composable
private fun TaskSkillsPanel(
    isLocalModel: Boolean,
    taskMessages: List<ChatMessage>,
    onMonitorClick: () -> Unit,
    onSendClick: () -> Unit,
    onSkillTap: (String) -> Unit,
    activatingSkill: String?,
    monitorActive: Boolean,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    val builtInSkills = remember { SkillRegistry.getUserFacing() }
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Workflows",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                "Background tasks powered by AI — things a single prompt can't do.",
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    "Experimental — more workflows coming soon",
                    fontSize = 11.sp,
                    color = colors.accent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Monitor Messages — always shown (background workflow, both modes need it)
        item {
            SkillCard(
                icon = Icons.Outlined.Visibility,
                title = "Monitor Messages",
                description = "Auto-reply to someone's messages in background",
                onClick = onMonitorClick,
                isActivating = activatingSkill == "monitor",
                isActive = monitorActive,
                colors = colors,
            )
        }

        // Send Message — available on both (workflow card shortcut)
        item {
            SkillCard(
                icon = Icons.Outlined.Send,
                title = "Send Message",
                description = "Send a message to someone via any messaging app",
                onClick = onSendClick,
                colors = colors,
            )
        }

        // Built-in user-facing skills from SkillRegistry
        if (builtInSkills.isNotEmpty()) {
            items(builtInSkills.size) { index ->
                val skill = builtInSkills[index]
                val example = skill.triggerPatterns.firstOrNull()
                    ?.replace(Regex("\\{\\w+\\}"), "...")
                    ?.replace(".+", "...")
                    ?: skill.name
                SkillCard(
                    icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome,
                    title = skill.name,
                    description = skill.description,
                    onClick = { onSkillTap(example) },
                    colors = colors,
                )
            }
        }

        // Task progress messages (if any)
        if (taskMessages.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Recent",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textTertiary,
                )
            }
            items(taskMessages.size) { index ->
                val msg = taskMessages[index]
                if (msg.role == ChatMessage.Role.USER) {
                    UserBubble(msg.content, msg.timestamp, colors)
                } else {
                    SystemMessage(msg.content, colors)
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    isActivating: Boolean = false,
    isActive: Boolean = false,
    colors: PokeclawColors,
) {
    val activeOrange = Color(0xFFE8751A)
    val borderColor = when {
        isActive -> activeOrange
        isActivating -> colors.accent
        else -> colors.inputBorder
    }
    val cardBg = when {
        isActive -> activeOrange.copy(alpha = 0.08f)
        else -> colors.surface
    }
    val iconBg = when {
        isActive -> activeOrange.copy(alpha = 0.15f)
        else -> colors.accent.copy(alpha = 0.12f)
    }
    val iconTint = if (isActive) activeOrange else colors.accent

    // Progress animation
    val progress by animateFloatAsState(
        targetValue = if (isActivating) 1f else 0f,
        animationSpec = if (isActivating) tween(2000, easing = LinearEasing) else snap(),
        label = "skillProgress",
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(if (isActive) 1.dp else 0.5.dp, borderColor),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(iconBg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = activeOrange, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isActive) activeOrange else colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isActive) "Running in background" else description,
                        fontSize = 12.sp,
                        color = if (isActive) activeOrange.copy(alpha = 0.7f) else colors.textTertiary,
                        lineHeight = 16.sp,
                    )
                }
                if (!isActive && !isActivating) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                }
            }

            // Progress bar during activation
            if (isActivating) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = activeOrange,
                    trackColor = colors.inputBorder,
                )
            }
        }
    }
}

// ======================== SKILL DIALOGS ========================

@Composable
private fun MonitorDialog(
    onDismiss: () -> Unit,
    onStart: (MonitorTargetSpec) -> Unit,
    colors: PokeclawColors,
) {
    var contact by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    val apps = MonitorTargetSpec.supportedApps

    // Centered modal overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // Centered card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { /* block clicks from dismissing */ },
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(colors.textTertiary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(14.dp))

                // Title
                Text(
                    "\uD83D\uDC41\uFE0F Monitor & Auto-Reply",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(12.dp))

                // Contact row: label + input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Target",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.widthIn(min = 50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = colors.textPrimary,
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .background(colors.background, RoundedCornerShape(8.dp))
                                    .then(
                                        Modifier.border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                                    )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                if (contact.isEmpty()) {
                                    Text("e.g. Mom, +1 555 123 4567", fontSize = 12.sp, color = colors.textTertiary)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))

                // App row: label + dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "App",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.widthIn(min = 50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.background,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { appMenuExpanded = true }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 12.sp, color = colors.textPrimary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app, fontSize = 12.sp) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Tone control removed (owner decision 2026-11-14): the reply tone was never used.
                Spacer(Modifier.height(6.dp))

                // Start Monitoring button
                Surface(
                    onClick = {
                        val trimmed = contact.trim()
                        if (trimmed.isNotBlank()) {
                            onStart(
                                MonitorTargetSpec(
                                    label = trimmed,
                                    app = selectedApp,
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.userBubble,
                ) {
                    Text(
                        "Start Monitoring",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendMessageDialog(
    onDismiss: () -> Unit,
    onSend: (contact: String, app: String, message: String) -> Unit,
    colors: PokeclawColors,
) {
    var contact by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    val apps = listOf("WhatsApp", "Telegram", "Messages")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text("Send Message", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        },
        text = {
            Column {
                Text("With a smarter LLM, you can just type:", fontSize = 11.sp, color = colors.textTertiary)
                Spacer(Modifier.height(2.dp))
                Text("\"send hi to Mom on WhatsApp\"", fontSize = 11.sp, color = colors.accent.copy(alpha = 0.7f))
                Spacer(Modifier.height(16.dp))

                // Fill-in-the-blank: "Send [___] to [___] on [WhatsApp ▾]"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Send ", fontSize = 15.sp, color = colors.textPrimary)
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        placeholder = { Text("message", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("to ", fontSize = 15.sp, color = colors.textPrimary)
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        placeholder = { Text("name", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text(" on ", fontSize = 15.sp, color = colors.textPrimary)
                    Box {
                        Surface(
                            onClick = { appMenuExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 13.sp, color = colors.textPrimary)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (contact.isNotBlank() && message.isNotBlank()) onSend(contact.trim(), selectedApp, message.trim()) },
                enabled = contact.isNotBlank() && message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

// ======================== ACTIVE TASK BAR ========================

@Composable
private fun ActiveTaskBar(
    tasks: List<String>,
    onStopTask: (String) -> Unit,
    onStopAll: () -> Unit,
    colors: PokeclawColors,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        // Monitor tasks bar
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (tasks.size == 1) "Monitoring: ${tasks[0]}" else "${tasks.size} monitoring",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "▴" else "▾",
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                )
            }
        }

        // Expanded — show each task with stop button
        if (expanded) {
            Divider(color = colors.textSecondary.copy(alpha = 0.2f), thickness = 0.5.dp)
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                                shape = androidx.compose.foundation.shape.CircleShape,
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = task,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Stop",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopTask(task) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (tasks.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "Stop All",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

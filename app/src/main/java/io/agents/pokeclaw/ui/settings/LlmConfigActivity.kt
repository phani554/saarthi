// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.CloudModel
import io.agents.pokeclaw.agent.CloudProvider
import io.agents.pokeclaw.agent.ModelPricing
import io.agents.pokeclaw.agent.llm.ActiveModelMode
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.ModelDownloadService
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.data.memory.HybridMemoryRepository
import io.agents.pokeclaw.ui.chat.ThemeManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton
import java.util.concurrent.Executors
import kotlin.math.max

class LlmConfigActivity : BaseActivity() {

    companion object {
        // Model ids downloading right now. Static, so leaving and re-opening this screen mid-download
        // can't start a second concurrent download of the same file.
        private val downloadsInFlight: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var isDownloading = false

    // "Import .litertlm file": copies run on their own thread so they never queue behind a download.
    private val importExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var importCancelled = false
    private var importInFlight = false // main thread only
    private var importStatus: TextView? = null
    private var importButton: TextView? = null

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) startModelImport(uri) }

    override fun onDestroy() {
        super.onDestroy()
        // Lets a running download finish (no interrupt) but stops the thread leaking with this screen.
        executor.shutdown()
        // A half-done import is stopped and its temporary file deleted.
        importCancelled = true
        importExecutor.shutdown()
    }
    private var selectedProvider: CloudProvider = CloudProvider.OPENAI
    private var selectedModelId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        // Apply dark theme from ThemeManager to match rest of app
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Models")
            showBackButton(true) { finish() }
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        val models = LocalModelManager.AVAILABLE_MODELS
        val activeModelName = findViewById<TextView>(R.id.tvActiveModelName)
        val activeModelMeta = findViewById<TextView>(R.id.tvActiveModelMeta)
        val activeModelStatus = findViewById<TextView>(R.id.tvActiveModelStatus)
        val defaultLocalName = findViewById<TextView>(R.id.tvDefaultLocalName)
        val defaultLocalMeta = findViewById<TextView>(R.id.tvDefaultLocalMeta)
        val defaultLocalStatus = findViewById<TextView>(R.id.tvDefaultLocalStatus)
        val defaultCloudName = findViewById<TextView>(R.id.tvDefaultCloudName)
        val defaultCloudMeta = findViewById<TextView>(R.id.tvDefaultCloudMeta)
        val defaultCloudStatus = findViewById<TextView>(R.id.tvDefaultCloudStatus)
        val modelList = findViewById<LinearLayout>(R.id.layoutModelList)
        val resolvedConfig = ModelConfigRepository.snapshot()
        val deviceSupport = LocalModelManager.deviceSupport(this)
        val catalog = LocalModelManager.catalog(this).associateBy { it.model.id }

        // Card 3: Sarvam Voice Audio Engine binding
        val etSarvamKey = findViewById<EditText>(R.id.etSarvamApiKeyConfig)
        val etSarvamLang = findViewById<EditText>(R.id.etSarvamLanguage)
        val etSarvamSpeaker = findViewById<EditText>(R.id.etSarvamSpeaker)
        val btnSaveSarvam = findViewById<View>(R.id.btnSaveSarvamConfig)

        etSarvamKey?.setText(KVUtils.getSarvamApiKey())
        etSarvamSpeaker?.setText(KVUtils.getSarvamSpeaker())

        // The old free-text language code box is now the Reply language chooser
        // (Auto / English / Hindi / Marathi / Telugu); it saves as soon as she picks.
        etSarvamLang?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            setText(io.agents.pokeclaw.lang.ReplyLanguageState.choiceLabel())
            setOnClickListener {
                io.agents.pokeclaw.lang.ReplyLanguageState.showChooser(this@LlmConfigActivity) {
                    setText(io.agents.pokeclaw.lang.ReplyLanguageState.choiceLabel())
                }
            }
        }

        btnSaveSarvam?.setOnClickListener {
            val key = etSarvamKey?.text?.toString()?.trim().orEmpty()
            val speaker = etSarvamSpeaker?.text?.toString()?.trim().orEmpty().ifBlank { "shubh" }

            KVUtils.setSarvamApiKey(key)
            KVUtils.setSarvamSpeaker(speaker)

            Toast.makeText(this, "Sarvam Voice Engine settings saved!", Toast.LENGTH_SHORT).show()
        }

        // Card 4: Mem0 AI Cloud Memory binding
        val etMem0Key = findViewById<EditText>(R.id.etMem0ApiKeyConfig)
        val btnSaveMem0 = findViewById<View>(R.id.btnSaveMem0Config)
        val tvMem0Status = findViewById<TextView>(R.id.tvMem0StatusHint)

        etMem0Key?.setText(KVUtils.getMem0ApiKey())
        val currentMem0Key = KVUtils.getMem0ApiKey()
        if (currentMem0Key.isNotBlank()) {
            tvMem0Status?.text = "● Status: Mem0 Cloud Active"
            tvMem0Status?.setTextColor(getColor(R.color.colorSuccessPrimary))
        } else {
            tvMem0Status?.text = "● Status: Using Local Markdown Vault (Fallback)"
            tvMem0Status?.setTextColor(Color.parseColor("#8b949e"))
        }

        btnSaveMem0?.setOnClickListener {
            val key = etMem0Key?.text?.toString()?.trim().orEmpty()
            KVUtils.setMem0ApiKey(key)
            HybridMemoryRepository.refreshActiveSource()
            if (key.isNotBlank()) {
                tvMem0Status?.text = "● Status: Mem0 Cloud Active"
                tvMem0Status?.setTextColor(getColor(R.color.colorSuccessPrimary))
                Toast.makeText(this, "Mem0 API Key saved!", Toast.LENGTH_SHORT).show()
            } else {
                tvMem0Status?.text = "● Status: Using Local Markdown Vault (Fallback)"
                tvMem0Status?.setTextColor(Color.parseColor("#8b949e"))
                Toast.makeText(this, "Mem0 API Key cleared; using local vault", Toast.LENGTH_SHORT).show()
            }
        }

        // Card 2: Interaction Model Name update
        val tvInteractionModel = findViewById<TextView>(R.id.tvVoiceInteractionModelName)
        val interactionModelId = io.agents.pokeclaw.agent.llm.LlmSessionManager.resolveInteractionModel()
        val isOpenRouterRoute = KVUtils.isRouteViaOpenRouter() || interactionModelId.contains("/")
        val interactionRouteTag = if (isOpenRouterRoute) " (OpenRouter)" else " (Gemini API)"
        tvInteractionModel?.text = "$interactionModelId$interactionRouteTag"

        // Card 5: OpenRouter Global Fallback Switch
        val switchOpenRouter = findViewById<SwitchCompat>(R.id.switchOpenRouterRouting)
        switchOpenRouter?.isChecked = KVUtils.isRouteViaOpenRouter()
        switchOpenRouter?.setOnCheckedChangeListener { _, isChecked ->
            KVUtils.setRouteViaOpenRouter(isChecked)
            Toast.makeText(
                this,
                if (isChecked) "OpenRouter Global Fallback Routing Enabled" else "OpenRouter Global Fallback Routing Disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Apply theme to active model card text
        activeModelName.setTextColor(tc.aiText)
        activeModelMeta.setTextColor(Color.parseColor("#8b949e"))
        defaultLocalName.setTextColor(tc.aiText)
        defaultLocalMeta.setTextColor(Color.parseColor("#8b949e"))
        defaultCloudName.setTextColor(tc.aiText)
        defaultCloudMeta.setTextColor(Color.parseColor("#8b949e"))

        // Apply theme to all CardViews in XML layout
        val scrollContent = findViewById<LinearLayout>(R.id.layoutModelList)?.parent as? LinearLayout
        if (scrollContent != null) {
            for (i in 0 until scrollContent.childCount) {
                val child = scrollContent.getChildAt(i)
                if (child is TextView && child.id == View.NO_ID) {
                    // Section headers ("Active Model", "Available Models", "Cloud LLM")
                    child.setTextColor(Color.parseColor("#8b949e"))
                }
                if (child is CardView) {
                    child.setCardBackgroundColor(tc.toolbarBg)
                }
            }
        }

        // Active model — show what is ACTUALLY active based on provider
        if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) {
            val activeState = LocalModelManager.resolveActiveModelState(this, resolvedConfig.local)
            activeModelName.text = activeState.displayName
            activeModelMeta.text = activeState.metaText
            activeModelStatus.text = activeState.statusText
            activeModelStatus.setTextColor(
                when (activeState.statusKind) {
                    LocalModelManager.StatusKind.READY -> getColor(R.color.colorSuccessPrimary)
                    LocalModelManager.StatusKind.WARNING -> getColor(R.color.colorWarningPrimary)
                    LocalModelManager.StatusKind.NEUTRAL -> Color.parseColor("#8b949e")
                }
            )
        } else {
            val cloudModel = resolvedConfig.activeCloud.modelName
            if (cloudModel.isNotEmpty()) {
                val isOpenRouter = KVUtils.isRouteViaOpenRouter() || resolvedConfig.activeCloud.provider == CloudProvider.OPENROUTER
                val routeTag = if (isOpenRouter) " (OpenRouter)" else ""
                activeModelName.text = "$cloudModel$routeTag"
                val providerName = if (isOpenRouter) "OpenRouter" else resolvedConfig.activeCloud.provider.displayName
                activeModelMeta.text = "$providerName · Cloud"
                activeModelStatus.text = "● Connected"
                activeModelStatus.setTextColor(getColor(R.color.colorSuccessPrimary))
            } else {
                activeModelName.text = "No model selected"
                activeModelMeta.text = "Configure a cloud model below"
                activeModelStatus.text = "● Not configured"
                activeModelStatus.setTextColor(Color.parseColor("#8b949e"))
            }
        }

        val defaultLocalState = LocalModelManager.resolveActiveModelState(this, resolvedConfig.local)
        defaultLocalName.text = defaultLocalState.displayName
        defaultLocalMeta.text = defaultLocalState.metaText
        defaultLocalStatus.text = defaultLocalState.statusText
        defaultLocalStatus.setTextColor(
            when (defaultLocalState.statusKind) {
                LocalModelManager.StatusKind.READY -> getColor(R.color.colorSuccessPrimary)
                LocalModelManager.StatusKind.WARNING -> getColor(R.color.colorWarningPrimary)
                LocalModelManager.StatusKind.NEUTRAL -> Color.parseColor("#8b949e")
            }
        )

        if (resolvedConfig.defaultCloud.isConfigured) {
            defaultCloudName.text = resolvedConfig.defaultCloud.modelName
            defaultCloudMeta.text = "${resolvedConfig.defaultCloud.provider.displayName} · Cloud"
            defaultCloudStatus.text = "● Ready"
            defaultCloudStatus.setTextColor(getColor(R.color.colorSuccessPrimary))
        } else {
            defaultCloudName.text = "No default cloud model"
            defaultCloudMeta.text = "Configure a cloud model below"
            defaultCloudStatus.text = "● Not configured"
            defaultCloudStatus.setTextColor(Color.parseColor("#8b949e"))
        }

        val activeLocalModelId = if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) resolvedConfig.local.modelId else ""
        val defaultLocalModelId = resolvedConfig.local.modelId
        val configuredBuiltInLocal = LocalModelManager.configuredBuiltInModel(resolvedConfig.local)

        // Build model list
        models.forEach { model ->
            val modelEntry = catalog[model.id]
            val availability = LocalModelManager.availabilityForModel(this, model, resolvedConfig.local)
            val downloaded = availability.isAvailable
            val isActive = model.id == activeLocalModelId
            val isDefaultLocal = model.id == defaultLocalModelId
            val supportedOnDevice = modelEntry?.isSupported == true
            val resolvedLocalPath = when (availability.source) {
                LocalModelManager.AvailabilitySource.MANAGED_DOWNLOAD -> LocalModelManager.getModelPath(this, model)
                LocalModelManager.AvailabilitySource.LINKED_FILE ->
                    if (configuredBuiltInLocal?.id == model.id) resolvedConfig.local.modelPath else null
                LocalModelManager.AvailabilitySource.MISSING -> null
            }

            val card = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                radius = dp(12).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(tc.toolbarBg)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            // Model info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTV = TextView(this).apply {
                text = model.displayName
                textSize = 14f
                setTextColor(tc.aiText)
                if (isActive) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            info.addView(nameTV)

            val descTV = TextView(this).apply {
                val baseText = "${model.sizeBytes / 1_000_000} MB · ${model.minRamGb}GB+ RAM"
                text = if (supportedOnDevice) baseText else "$baseText · This phone reports ${deviceSupport.deviceRamGb}GB"
                textSize = 12f
                setTextColor(if (supportedOnDevice) Color.parseColor("#8b949e") else getColor(R.color.colorWarningPrimary))
            }
            info.addView(descTV)

            row.addView(info)

            // Action button
            if (downloaded) {
                if (isActive) {
                    val check = TextView(this).apply {
                        text = if (supportedOnDevice) "✓ Active" else "⚠ Active"
                        textSize = 12f
                        setTextColor(if (supportedOnDevice) getColor(R.color.colorSuccessPrimary) else getColor(R.color.colorWarningPrimary))
                    }
                    row.addView(check)
                } else {
                    if (isDefaultLocal) {
                        row.addView(TextView(this).apply {
                            text = "✓ Default"
                            textSize = 12f
                            setTextColor(getColor(R.color.colorSuccessPrimary))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        })
                    }
                    if (supportedOnDevice) {
                        val useBtn = TextView(this).apply {
                            text = "Use"
                            textSize = 13f
                            setTextColor(getColor(R.color.colorBrandPrimary))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                            setOnClickListener {
                                val path = resolvedLocalPath
                                if (path != null) {
                                    // Save as default local model (independent of cloud config)
                                    // Only switch active provider if currently on local tab
                                    val shouldActivateLocal = ModelConfigRepository.isLocalActive() || !KVUtils.hasDefaultCloudModel()
                                    ModelConfigRepository.saveLocalDefault(path, model.id, shouldActivateLocal)
                                    ClawApplication.appViewModelInstance.updateAgentConfig()
                                    ClawApplication.appViewModelInstance.initAgent()
                                    Toast.makeText(this@LlmConfigActivity, "Set default local: ${model.displayName}", Toast.LENGTH_SHORT).show()
                                    recreate()
                                } else {
                                    Toast.makeText(this@LlmConfigActivity, "Model file not found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        row.addView(useBtn)
                    } else {
                        row.addView(TextView(this).apply {
                            text = "Needs ${model.minRamGb}GB+"
                            textSize = 12f
                            setTextColor(getColor(R.color.colorWarningPrimary))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        })
                    }

                    if (availability.source == LocalModelManager.AvailabilitySource.MANAGED_DOWNLOAD) {
                        val delBtn = TextView(this).apply {
                            text = "🗑"
                            textSize = 16f
                            setPadding(dp(8), dp(4), dp(4), dp(4))
                            alpha = 0.4f
                            setOnClickListener {
                                android.app.AlertDialog.Builder(this@LlmConfigActivity)
                                    .setTitle("Delete ${model.displayName}?")
                                    .setMessage("The model file is removed from this phone. You will need to download it again to use it.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        LocalModelManager.deleteModel(this@LlmConfigActivity, model)
                                        Toast.makeText(this@LlmConfigActivity, "Deleted ${model.displayName}", Toast.LENGTH_SHORT).show()
                                        recreate()
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                        row.addView(delBtn)
                    }
                }
            } else {
                if (supportedOnDevice) {
                    val dlBtn = TextView(this).apply {
                        // A download started earlier (this screen was left and reopened) can be stopped here.
                        text = if (LocalModelManager.isDownloading(model.id)) "Downloading · Stop" else "↓ Download"
                        textSize = 13f
                        setTextColor(getColor(R.color.colorInfoPrimary))
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener {
                            if (LocalModelManager.isDownloading(model.id)) {
                                // Second tap stops it; the partial file is deleted.
                                LocalModelManager.cancelDownload(this@LlmConfigActivity, model)
                                text = "Stopping..."
                                isEnabled = false
                                return@setOnClickListener
                            }
                            if (isDownloading || !downloadsInFlight.add(model.id)) {
                                Toast.makeText(this@LlmConfigActivity, "Already downloading", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            isDownloading = true
                            text = "Downloading · Stop"

                            // Runs in ModelDownloadService so it keeps going after this screen closes or the screen goes off.
                            run {
                                ModelDownloadService.start(this@LlmConfigActivity, model, object : LocalModelManager.DownloadCallback {
                                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                                        runOnUiThread { if (isEnabled) text = "$pct% · Stop" }
                                    }
                                    override fun onComplete(modelPath: String) {
                                        // The service already saved it as the local default.
                                        runOnUiThread {
                                            downloadsInFlight.remove(model.id)
                                            isDownloading = false
                                            if (isFinishing || isDestroyed) return@runOnUiThread
                                            Toast.makeText(this@LlmConfigActivity, "Downloaded!", Toast.LENGTH_SHORT).show()
                                            recreate()
                                        }
                                    }
                                    override fun onError(error: String) {
                                        downloadsInFlight.remove(model.id)
                                        runOnUiThread {
                                            isDownloading = false
                                            if (isFinishing || isDestroyed) return@runOnUiThread
                                            text = "↓ Download"
                                            isEnabled = true
                                            Toast.makeText(this@LlmConfigActivity, error, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    override fun onCancelled() {
                                        downloadsInFlight.remove(model.id)
                                        runOnUiThread {
                                            isDownloading = false
                                            if (isFinishing || isDestroyed) return@runOnUiThread
                                            text = "↓ Download"
                                            isEnabled = true
                                            Toast.makeText(this@LlmConfigActivity, "Download stopped", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            }
                        }
                    }
                    row.addView(dlBtn)
                } else {
                    row.addView(TextView(this).apply {
                        text = "Needs ${model.minRamGb}GB+"
                        textSize = 12f
                        setTextColor(getColor(R.color.colorWarningPrimary))
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    })
                }
            }

            card.addView(row)
            modelList.addView(card)
        }

        // Local engine choice (Auto / NPU / GPU / CPU) + import of a .litertlm file from storage
        addLocalEngineCard(modelList, tc)

        // Storage info
        updateStorageInfo()

        // Cloud LLM — Provider tabs + model cards
        setupCloudLlm(tc)
    }

    /**
     * Card under the local model list: which backend the local model runs on (stored as the local backend
     * preference that EngineHolder/NpuGate read: "" = Auto, "NPU", "GPU", "CPU"), and an import button for
     * a .litertlm file (e.g. an NPU build for this phone's chip) picked with the system file picker.
     */
    private fun addLocalEngineCard(modelList: LinearLayout, tc: ThemeManager.ChatColors) {
        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(tc.toolbarBg)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        column.addView(TextView(this).apply {
            text = "Run local model on"
            textSize = 14f
            setTextColor(tc.aiText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val choices = listOf("" to "Auto", "NPU" to "NPU", "GPU" to "GPU", "CPU" to "CPU")
        val current = KVUtils.getLocalBackendPreference().uppercase().let { pref ->
            if (choices.any { it.first == pref }) pref else ""
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        choices.forEach { (value, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 13f
                setTextColor(tc.aiText)
                tag = value
                isChecked = value == current
                setPadding(dp(2), 0, dp(10), 0)
            })
        }

        val hint = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8b949e"))
        }
        fun renderHint() {
            val pref = KVUtils.getLocalBackendPreference().uppercase()
            val running = io.agents.pokeclaw.agent.llm.LocalModelRuntime.currentBackendLabel(null)
            val npu = io.agents.pokeclaw.agent.llm.EngineHolder.lastNpuDecision
            hint.text = buildString {
                append(
                    when (pref) {
                        "NPU" -> "Use NPU: tries the phone's Qualcomm NPU first, then GPU, then CPU."
                        "GPU" -> "GPU first, then CPU. The NPU is not used."
                        "CPU" -> "CPU only: slowest, but works on every phone."
                        else -> "Auto: NPU for NPU model files, else GPU, then CPU."
                    }
                )
                if (running != null) append("\nNow running on: $running")
                if (npu != null) append("\nNPU check: ${npu.reason}")
                append("\nA change applies the next time the model loads.")
            }
        }
        renderHint()
        group.setOnCheckedChangeListener { g, checkedId ->
            val value = g.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            if (value == KVUtils.getLocalBackendPreference().uppercase()) return@setOnCheckedChangeListener
            if (value == "NPU" || value == "GPU") {
                // An explicit accelerator choice lifts the automatic CPU-safe quarantine for this phone.
                KVUtils.clearLocalCpuSafeMode()
            }
            KVUtils.setLocalBackendPreference(value)
            renderHint()
            val label = choices.first { it.first == value }.second
            Toast.makeText(this, "Local model: $label", Toast.LENGTH_SHORT).show()
        }
        column.addView(group)
        column.addView(hint)

        val importBtn = TextView(this).apply {
            text = if (importInFlight) "Importing · Stop" else "⤓ Import .litertlm file"
            textSize = 13f
            setTextColor(getColor(R.color.colorInfoPrimary))
            setPadding(0, dp(12), dp(12), dp(4))
            setOnClickListener {
                if (importInFlight) {
                    importCancelled = true
                    text = "Stopping..."
                    return@setOnClickListener
                }
                try {
                    importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                } catch (e: Exception) {
                    Toast.makeText(this@LlmConfigActivity, "No file picker available", Toast.LENGTH_LONG).show()
                }
            }
        }
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8b949e"))
            text = "Copies a model file from this phone's storage and uses it as the local model."
        }
        importButton = importBtn
        importStatus = status
        column.addView(importBtn)
        column.addView(status)

        card.addView(column)
        modelList.addView(card)
    }

    private fun startModelImport(uri: android.net.Uri) {
        if (importInFlight) {
            Toast.makeText(this, "Already importing a model", Toast.LENGTH_SHORT).show()
            return
        }
        if (ClawApplication.appViewModelInstance.isTaskRunning()) {
            Toast.makeText(this, "Wait for the running task to finish, then import", Toast.LENGTH_LONG).show()
            return
        }
        importInFlight = true
        importCancelled = false
        importButton?.text = "Importing · Stop"
        importStatus?.text = "Copying..."
        val appContext = applicationContext
        importExecutor.submit {
            try {
                val file = LocalModelManager.importModelFile(
                    appContext,
                    uri,
                    progress = { copied, total ->
                        val line = if (total > 0) {
                            "Copying ${copied / 1_000_000} of ${total / 1_000_000} MB (${copied * 100 / total}%)"
                        } else {
                            "Copying ${copied / 1_000_000} MB"
                        }
                        runOnUiThread { if (!isFinishing && !isDestroyed) importStatus?.text = line }
                    },
                    isCancelled = { importCancelled },
                )
                runOnUiThread {
                    importInFlight = false
                    // Select it as the local model (switch to local only when local is already the mode
                    // or no cloud default exists, the same rule as "Use").
                    val builtIn = LocalModelManager.AVAILABLE_MODELS.find { it.fileName.equals(file.name, ignoreCase = true) }
                    val modelId = builtIn?.id ?: file.nameWithoutExtension
                    val activate = ModelConfigRepository.isLocalActive() || !KVUtils.hasDefaultCloudModel()
                    ModelConfigRepository.saveLocalDefault(file.absolutePath, modelId, activate)
                    try {
                        ClawApplication.appViewModelInstance.updateAgentConfig()
                        ClawApplication.appViewModelInstance.initAgent()
                    } catch (e: Exception) {
                        io.agents.pokeclaw.utils.XLog.w("LlmConfigActivity", "agent reconfigure after import failed", e)
                    }
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, "Imported ${file.name}", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            } catch (e: java.util.concurrent.CancellationException) {
                runOnUiThread {
                    importInFlight = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    importButton?.text = "⤓ Import .litertlm file"
                    importStatus?.text = "Import stopped."
                }
            } catch (e: Throwable) {
                io.agents.pokeclaw.utils.XLog.e("LlmConfigActivity", "Model import failed", e)
                runOnUiThread {
                    importInFlight = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    importButton?.text = "⤓ Import .litertlm file"
                    importStatus?.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
                    Toast.makeText(this, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateStorageInfo() {
        val models = LocalModelManager.AVAILABLE_MODELS
        var totalSize = 0L
        var count = 0
        models.forEach { model ->
            if (LocalModelManager.isModelDownloaded(this, model)) {
                totalSize += model.sizeBytes
                count++
            }
        }
        val mbUsed = totalSize / 1_000_000
        val allocated = 4000L // 4GB rough estimate
        val pct = (mbUsed * 100 / allocated).toInt().coerceAtMost(100)

        findViewById<TextView>(R.id.tvStorageInfo).text = "$count model${if (count != 1) "s" else ""} · ${mbUsed} MB"
        findViewById<ProgressBar>(R.id.progressStorage).progress = pct
        findViewById<TextView>(R.id.tvStorageDetail).text = "${mbUsed} MB of ${allocated} MB allocated"
    }

    private fun setupCloudLlm(tc: ThemeManager.ChatColors) {
        val tabLayout = findViewById<LinearLayout>(R.id.layoutProviderTabs)
        val modelListLayout = findViewById<LinearLayout>(R.id.layoutCloudModels)
        val layoutBaseUrl = findViewById<View>(R.id.layoutBaseUrl)
        val tvCustomHint = findViewById<TextView>(R.id.tvCustomHint)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val tvStatus = findViewById<TextView>(R.id.tvConnectionStatus)
        val btnTest = findViewById<TextView>(R.id.btnTestConnection)
        val btnSave = findViewById<KButton>(R.id.btnSaveCloud)
        val btnClear = findViewById<TextView>(R.id.btnClearApiKey)
        val scrollView = findViewById<ScrollView>(R.id.scrollContent)
        val configSnapshot = ModelConfigRepository.snapshot()
        val cloudSeed = configSnapshot.defaultCloud.let {
            if (it.modelName.isNotEmpty() || it.apiKey.isNotEmpty() || it.baseUrl.isNotEmpty()) it else configSnapshot.activeCloud
        }

        installKeyboardAwareScrolling(scrollView, listOf(etApiKey, etBaseUrl, etModelName))

        // Clear API key button
        btnClear.setOnClickListener {
            etApiKey.setText("")
            KVUtils.setApiKeyForProvider(selectedProvider.name, "")
            KVUtils.setLlmApiKey("")
            Toast.makeText(this, "API key cleared", Toast.LENGTH_SHORT).show()
        }

        // Determine current provider from saved config
        selectedProvider = CloudProvider.fromName(cloudSeed.providerName)
        selectedModelId = cloudSeed.modelName
        etApiKey.setText(cloudSeed.apiKey)

        // Build provider tabs
        val tabViews = mutableMapOf<CloudProvider, TextView>()
        CloudProvider.entries.forEach { provider ->
            val tab = TextView(this).apply {
                text = provider.displayName
                textSize = 13f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                // Click handler set below after renderModels is defined
            }
            tabViews[provider] = tab
            tabLayout.addView(tab)
        }

        fun updateTabStyles() {
            tabViews.forEach { (provider, tab) ->
                if (provider == selectedProvider) {
                    tab.setTextColor(Color.WHITE)
                    tab.background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(getColor(R.color.colorBrandPrimary))
                    }
                } else {
                    tab.setTextColor(Color.parseColor("#8b949e"))
                    tab.background = null
                }
            }
        }
        updateTabStyles()

        // Render model cards for current provider
        fun renderModels() {
            modelListLayout.removeAllViews()
            val isCustom = selectedProvider == CloudProvider.CUSTOM
            layoutBaseUrl.visibility = if (isCustom) View.VISIBLE else View.GONE
            tvCustomHint?.visibility = if (isCustom) View.VISIBLE else View.GONE

            if (isCustom) {
                etBaseUrl.setText(cloudSeed.baseUrl)
                etModelName.setText(selectedModelId)
                return
            }

            selectedProvider.models.forEach { model ->
                val isSelected = model.id == selectedModelId
                val card = CardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    radius = dp(10).toFloat()
                    cardElevation = dp(1).toFloat()
                    setCardBackgroundColor(if (isSelected) Color.parseColor("#2A1F1A") else tc.toolbarBg)
                    if (isSelected) {
                        // Orange border for selected
                        setContentPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    setOnClickListener {
                        selectedModelId = model.id
                        renderModels()
                    }
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }

                // Radio dot
                val dot = TextView(this).apply {
                    text = if (isSelected) "◉" else "○"
                    textSize = 16f
                    setTextColor(if (isSelected) getColor(R.color.colorBrandPrimary) else Color.parseColor("#8b949e"))
                    setPadding(0, 0, dp(10), 0)
                }
                row.addView(dot)

                // Model info
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                nameRow.addView(TextView(this).apply {
                    text = model.displayName
                    textSize = 14f
                    setTextColor(tc.aiText)
                    if (isSelected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                // Recommended badge
                if (model.recommended) {
                    nameRow.addView(TextView(this).apply {
                        text = " ⚡"
                        textSize = 12f
                    })
                }
                info.addView(nameRow)

                // Price + tier
                info.addView(TextView(this).apply {
                    text = "${model.tier.stars} ${model.tier.label} · \$${model.inputPricePerM} / \$${model.outputPricePerM} per 1M"
                    textSize = 11f
                    setTextColor(Color.parseColor("#8b949e"))
                })
                row.addView(info)

                card.addView(row)
                modelListLayout.addView(card)
            }
        }
        renderModels()

        // Provider tab switch — load per-provider saved API key
        fun switchProvider(provider: CloudProvider, colors: ThemeManager.ChatColors) {
            selectedProvider = provider
            selectedModelId = when {
                provider == CloudProvider.CUSTOM -> cloudSeed.modelName
                provider == cloudSeed.provider && provider.models.any { it.id == cloudSeed.modelName } -> cloudSeed.modelName
                // Recommended model first (OpenRouter: GLM 5.3 Flash), else the first listed.
                else -> (provider.models.firstOrNull { it.recommended } ?: provider.models.firstOrNull())?.id ?: ""
            }
            updateTabStyles()
            renderModels()
            tvStatus.visibility = View.GONE
            val savedKey = KVUtils.getApiKeyForProvider(provider.name)
            etApiKey.setText(savedKey)
        }
        // Re-assign click listeners with the inner function
        tabViews.forEach { (provider, tab) ->
            tab.setOnClickListener { switchProvider(provider, tc) }
        }

        // Test Connection
        btnTest.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Enter API Key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.visibility = View.VISIBLE
            // Fields are read here on the main thread. This only checks the format; it does not
            // contact the provider, so the label says exactly that.
            val modelId = if (selectedProvider == CloudProvider.CUSTOM) etModelName.text.toString().trim()
                else selectedModelId
            val problem = when {
                apiKey.length < 10 -> "API key too short"
                modelId.isEmpty() -> "No model selected"
                else -> null
            }
            if (problem == null) {
                tvStatus.text = "✓ Key format looks OK (not checked online)"
                tvStatus.setTextColor(getColor(R.color.colorSuccessPrimary))
            } else {
                tvStatus.text = "✗ $problem"
                tvStatus.setTextColor(getColor(R.color.colorErrorPrimary))
            }
        }

        // Save & Activate
        btnSave.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Enter API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isCustom = selectedProvider == CloudProvider.CUSTOM
            val baseUrl = if (isCustom) etBaseUrl.text.toString().trim() else selectedProvider.defaultBaseUrl
            val modelId = if (isCustom) etModelName.text.toString().trim() else selectedModelId

            if (modelId.isEmpty()) {
                Toast.makeText(this, "Select a model", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save as default cloud model (independent of local config)
            ModelConfigRepository.saveCloudDefault(
                providerName = selectedProvider.name,
                modelId = modelId,
                baseUrl = baseUrl,
                apiKey = apiKey,
                activateNow = !ModelConfigRepository.isLocalActive()
            )
            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, "Saved cloud default: $modelId", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Active model card is already set in onCreate based on actual provider
    }

    /**
     * Keep focused fields visible above the IME on edge-to-edge layouts.
     * `adjustResize` alone is not reliable here because the ScrollView content
     * still needs extra bottom inset + explicit scroll after the keyboard opens.
     */
    private fun installKeyboardAwareScrolling(scrollView: ScrollView, fields: List<EditText>) {
        val baseBottomPadding = scrollView.paddingBottom

        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                scrollView.postDelayed({ scrollFieldIntoView(scrollView, view) }, 180)
            }
        }
        fields.forEach { it.onFocusChangeListener = focusListener }

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = baseBottomPadding + max(imeInsets.bottom, systemInsets.bottom))

            if (imeInsets.bottom > 0) {
                val focused = currentFocus
                if (focused is EditText && fields.contains(focused)) {
                    view.post { scrollFieldIntoView(scrollView, focused) }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(scrollView)
    }

    private fun scrollFieldIntoView(scrollView: ScrollView, field: View) {
        val rect = Rect()
        field.getDrawingRect(rect)
        scrollView.offsetDescendantRectToMyCoords(field, rect)

        val margin = dp(16)
        val visibleTop = scrollView.scrollY + margin
        val visibleBottom = scrollView.scrollY + scrollView.height - scrollView.paddingBottom - margin

        when {
            rect.top < visibleTop -> {
                scrollView.smoothScrollTo(0, (rect.top - margin).coerceAtLeast(0))
            }
            rect.bottom > visibleBottom -> {
                val targetScroll = scrollView.scrollY + (rect.bottom - visibleBottom) + margin
                scrollView.smoothScrollTo(0, targetScroll.coerceAtLeast(0))
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

enum class ChecklistStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

data class ChecklistItem(
    val id: String,
    val description: String,
    var status: ChecklistStatus = ChecklistStatus.NOT_STARTED
)

/**
 * Single-source-of-truth manager for task execution state, checklist memory,
 * and structured task CoroutineScope cancellation.
 */
class TaskExecutionState private constructor() {

    private val _currentState = MutableStateFlow<AgentTaskState>(AgentTaskState.Idle)
    val currentState: StateFlow<AgentTaskState> = _currentState.asStateFlow()

    @Volatile
    private var taskScope: CoroutineScope? = null

    var taskGoal: String = ""
        private set
    var activePackageName: String = ""
        private set
    var activeFunctionName: String = "IDLE"
        private set
    var isAbandoned: Boolean = false
        private set
    var abandonReason: String = ""
        private set
    var stepCount: Int = 0
        private set

    private val _checklist = CopyOnWriteArrayList<ChecklistItem>()

    val checklist: List<ChecklistItem>
        get() = _checklist

    companion object {
        private const val TAG = "TaskExecutionState"
        val instance: TaskExecutionState by lazy { TaskExecutionState() }
    }

    @Synchronized
    fun setState(newState: AgentTaskState) {
        val oldState = _currentState.value
        if (oldState == newState) return

        XLog.i(TAG, "State transition: ${oldState.javaClass.simpleName} -> ${newState.javaClass.simpleName} ($newState)")
        _currentState.value = newState

        // Explicitly cancel task scope on terminal/abort/call states
        when (newState) {
            is AgentTaskState.CallInProgress,
            is AgentTaskState.Completed,
            is AgentTaskState.Failed,
            is AgentTaskState.Aborted,
            is AgentTaskState.Idle -> {
                cancelTaskScope("State changed to ${newState.javaClass.simpleName}")
            }
            else -> {
                // Ensure active task scope for active states
                if (taskScope == null) {
                    getOrCreateTaskScope()
                }
            }
        }
    }

    @Synchronized
    fun getOrCreateTaskScope(): CoroutineScope {
        var scope = taskScope
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            taskScope = scope
            XLog.d(TAG, "Created new task CoroutineScope: $scope")
        }
        return scope
    }

    @Synchronized
    fun cancelTaskScope(reason: String) {
        taskScope?.let { scope ->
            XLog.i(TAG, "Cancelling task CoroutineScope ($reason)")
            scope.cancel(reason)
            taskScope = null
        }
    }

    @Synchronized
    fun startTask(goal: String, defaultChecklist: List<String> = emptyList()) {
        reset()
        this.taskGoal = goal
        this.isAbandoned = false
        this.abandonReason = ""
        this.stepCount = 0
        this.activeFunctionName = "INITIALIZING"

        getOrCreateTaskScope()
        setState(AgentTaskState.Planning(goal))

        if (defaultChecklist.isNotEmpty()) {
            defaultChecklist.forEachIndexed { index, item ->
                _checklist.add(ChecklistItem("step_$index", item, ChecklistStatus.NOT_STARTED))
            }
        } else {
            generateChecklistFromGoal(goal)
        }
        XLog.i(TAG, "Task execution state started for goal: '$goal' (${_checklist.size} steps)")
    }

    private fun generateChecklistFromGoal(goal: String) {
        val lower = goal.lowercase()
        when {
            lower.contains("order") || lower.contains("buy") || lower.contains("add ") || lower.contains("blinkit") || lower.contains("amazon") || lower.contains("flipkart") -> {
                _checklist.add(ChecklistItem("open_app", "Open target store app", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("find_search", "Locate search bar", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("type_search", "Search for requested product", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("add_cart", "Add product to cart", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("verify", "Verify item added to cart", ChecklistStatus.NOT_STARTED))
            }
            lower.contains("message") || lower.contains("send ") || lower.contains("whatsapp") || lower.contains("chat") -> {
                _checklist.add(ChecklistItem("open_app", "Open messaging app", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("find_contact", "Locate contact/chat", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("type_message", "Type and send message", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("confirm", "Confirm message sent", ChecklistStatus.NOT_STARTED))
            }
            else -> {
                _checklist.add(ChecklistItem("plan", "Identify required screen/app", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("execute", "Execute task action", ChecklistStatus.NOT_STARTED))
                _checklist.add(ChecklistItem("finish", "Verify and complete task", ChecklistStatus.NOT_STARTED))
            }
        }
    }

    @Synchronized
    fun setActiveContext(packageName: String, functionName: String) {
        this.activePackageName = packageName
        this.activeFunctionName = functionName
    }

    @Synchronized
    fun incrementStep() {
        stepCount++
        val plan = _checklist.map { it.description }
        setState(AgentTaskState.Executing(taskGoal, stepCount, plan))
    }

    @Synchronized
    fun updateChecklistStatus(id: String, status: ChecklistStatus) {
        val item = _checklist.find { it.id == id || it.description.contains(id, ignoreCase = true) }
        if (item != null) {
            item.status = status
            XLog.i(TAG, "Updated checklist item '${item.description}' to $status")
        }
    }

    @Synchronized
    fun markStepInProgress(index: Int) {
        if (index in _checklist.indices) {
            _checklist[index].status = ChecklistStatus.IN_PROGRESS
        }
    }

    @Synchronized
    fun markStepCompleted(index: Int) {
        if (index in _checklist.indices) {
            _checklist[index].status = ChecklistStatus.COMPLETED
        }
    }

    @Synchronized
    fun abandonTask(reason: String) {
        this.isAbandoned = true
        this.abandonReason = reason
        this.activeFunctionName = "ABANDONED"
        _checklist.forEach { item ->
            if (item.status == ChecklistStatus.IN_PROGRESS || item.status == ChecklistStatus.NOT_STARTED) {
                item.status = ChecklistStatus.FAILED
            }
        }
        setState(AgentTaskState.Aborted(reason))
        XLog.w(TAG, "Task ABANDONED: $reason")
    }

    @Synchronized
    fun reset() {
        taskGoal = ""
        activePackageName = ""
        activeFunctionName = "IDLE"
        isAbandoned = false
        abandonReason = ""
        stepCount = 0
        _checklist.clear()
        setState(AgentTaskState.Idle)
        XLog.i(TAG, "Task execution state reset")
    }

    @Synchronized
    fun toPromptSection(): String {
        if (taskGoal.isBlank()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## Task Execution Memory & Checklist\n")
        sb.append("- Goal: ").append(taskGoal).append("\n")
        if (activePackageName.isNotBlank()) sb.append("- Active App: ").append(activePackageName).append("\n")
        sb.append("- Current Action State: ").append(activeFunctionName).append("\n")
        sb.append("- Steps Executed: ").append(stepCount).append("\n")

        if (_checklist.isNotEmpty()) {
            sb.append("\n### Checklist Progress:\n")
            _checklist.forEach { item ->
                val mark = when (item.status) {
                    ChecklistStatus.COMPLETED -> "[x]"
                    ChecklistStatus.IN_PROGRESS -> "[/]"
                    ChecklistStatus.FAILED -> "[!]"
                    ChecklistStatus.NOT_STARTED -> "[ ]"
                }
                sb.append("- ").append(mark).append(" ").append(item.description).append("\n")
            }
        }
        return sb.toString()
    }
}

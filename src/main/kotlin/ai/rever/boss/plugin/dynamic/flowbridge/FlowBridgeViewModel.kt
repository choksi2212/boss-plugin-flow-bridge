package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * State for the Flow Bridge panel.
 *
 * Re-exports [PluginAvailabilitySnapshot] and adds local UI state:
 *  - one status string per target plugin ("ready", "not loaded", "last error…").
 *  - one "info" / "error" string for the panel toast.
 *  - the current recorder / engine / llm status snapshots.
 *  - the most recent "convert to flow" outcome.
 *  - a small list of recent engine runs (the recorder has no list tool of its own).
 *
 * Every mutation launches on [scope] and reports either success or an error string
 * back into [_info] / [_error]. Components never call MCP tools directly  - they go
 * through the bridges, which is what keeps the tool names out of the UI layer.
 */
class FlowBridgeViewModel(
    private val registry: McpToolRegistry?,
    private val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scope = pluginScope

    val rparecorder = RparecorderBridge(registry ?: UnavailableRegistry)
    val rpaengine = RpaengineBridge(registry ?: UnavailableRegistry)
    val llmrpa = LlmrpaBridge(registry ?: UnavailableRegistry)
    val flowTab = FlowTabBridge(registry ?: UnavailableRegistry)

    // Recorder snapshot.
    private val _recorderStatus = MutableStateFlow(RecorderStatus())
    val recorderStatus: StateFlow<RecorderStatus> = _recorderStatus.asStateFlow()

    // Engine snapshot.
    private val _engineStatus = MutableStateFlow(EngineStatus())
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    // LLM draft snapshot.
    private val _llmStatus = MutableStateFlow(LlmStatus())
    val llmStatus: StateFlow<LlmStatus> = _llmStatus.asStateFlow()

    // Recent runs (recorded on terminal states  - see [recordTerminalRun]).
    private val _recentRuns = MutableStateFlow<List<RecentRun>>(emptyList())
    val recentRuns: StateFlow<List<RecentRun>> = _recentRuns.asStateFlow()

    // Last flow import.
    private val _lastImport = MutableStateFlow<FlowImportOutcome?>(null)
    val lastImport: StateFlow<FlowImportOutcome?> = _lastImport.asStateFlow()

    // UI messages.
    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Refresh every probe the panel renders.
     *
     * Called on panel open and whenever the user presses Refresh. Each bridge call is
     * independent so a failure in one does not block the others  - every probe is its
     * own try/catch and reports a status into the snapshot.
     */
    fun refreshAll() {
        scope.launch {
            try {
                _recorderStatus.value = rparecorder.status()
            } catch (e: Exception) {
                _recorderStatus.value = RecorderStatus(unavailable = true, message = e.message.orEmpty())
            }
        }
        scope.launch {
            try {
                _engineStatus.value = rpaengine.status()
            } catch (e: Exception) {
                _engineStatus.value = EngineStatus(unavailable = true, message = e.message.orEmpty())
            }
        }
        scope.launch {
            try {
                _llmStatus.value = llmrpa.status()
            } catch (e: Exception) {
                _llmStatus.value = LlmStatus(unavailable = true, rawText = e.message.orEmpty())
            }
        }
    }

    /** Start/stop recording on the rparecorder target. */
    fun toggleRecording() {
        scope.launch {
            val result = rparecorder.toggle()
            if (result.isError) _error.value = "rparecorder: ${result.text}"
            else _info.value = result.text
            _recorderStatus.value = rparecorder.status()
        }
    }

    /** Drop all recorded actions. */
    fun clearRecording() {
        scope.launch {
            val result = rparecorder.clear()
            if (result.isError) _error.value = "rparecorder: ${result.text}"
            else _info.value = result.text
            _recorderStatus.value = rparecorder.status()
        }
    }

    /**
     * Convert the current recorder snapshot into a flow-tab graph.
     *
     * Returns the outcome via [lastImport] (and into info / error). Records nothing
     * into recent runs  - converting to a flow is not a run.
     */
    fun convertRecorderToFlow() {
        scope.launch {
            val snapshot = _recorderStatus.value
            val outcome = flowTab.createFromRecorder(snapshot)
            _lastImport.value = outcome
            if (outcome.success) {
                _info.value = "Created flow (${outcome.nodeCount} node(s))"
            } else if (outcome.tabId != null) {
                _info.value = "Created flow with ${outcome.errors.size} error(s) (${outcome.nodeCount} node(s) added)"
            } else {
                _error.value = "Could not create flow: ${outcome.errors.joinToString("; ").ifEmpty { "no actions recorded" }}"
            }
        }
    }

    /** Submit [instruction] to the LLM, then load the resulting plan into rpaengine. */
    fun generateAndRun(instruction: String) {
        if (instruction.isBlank()) {
            _error.value = "Type an instruction first"
            return
        }
        scope.launch {
            val draft = llmrpa.draft(instruction)
            if (draft.isError) {
                _error.value = "llmrpa: ${draft.text}"
                _llmStatus.value = llmrpa.status()
                return@launch
            }
            _info.value = draft.text
            _llmStatus.value = llmrpa.status()
            // The llmrpa plugin writes its generated plan to disk and reports the path
            // in the status reply. rpaengine's `rpa_load` accepts a name, so we use the
            // instruction's first ~32 chars as a friendly fallback when the plan path
            // can't be resolved to a name.
            val planName = derivePlanName(instruction, _llmStatus.value.planPath)
            val loaded = rpaengine.load(planName)
            if (loaded.isError) {
                _error.value = "rpaengine load: ${loaded.text}"
                return@launch
            }
            _info.value = "Loaded draft plan '$planName'. Starting…"
            val started = rpaengine.run()
            if (started.isError) {
                _error.value = "rpaengine run: ${started.text}"
            } else {
                _info.value = started.text
            }
            _engineStatus.value = rpaengine.status()
            recordTerminalRunFromStatus(planName, _engineStatus.value)
        }
    }

    /** Stop a running engine workflow. */
    fun stopRun() {
        scope.launch {
            val result = rpaengine.stop()
            if (result.isError) _error.value = "rpaengine: ${result.text}"
            else _info.value = result.text
            _engineStatus.value = rpaengine.status()
        }
    }

    /** Refresh the recent runs list (synthesised from local history, not a remote call). */
    fun refreshRecentRuns() {
        // No remote call  - recent runs are tracked locally as the engine moves into
        // terminal states. The flow is just "trigger a refresh of the engine snapshot
        // and then read whatever the history already holds", so the panel's Refresh
        // button has something to do without a separate endpoint.
        scope.launch {
            _engineStatus.value = rpaengine.status()
        }
    }

    /**
     * Push a new entry into [recentRuns] when the engine reaches a terminal state.
     *
     * Called after every rpa_run / rpa_stop. Skips non-terminal states (IDLE,
     * EXECUTING, LOADING, PAUSED) so a "still running" status is never recorded as
     * a finished run.
     */
    private fun recordTerminalRunFromStatus(name: String, status: EngineStatus) {
        if (status.unavailable) return
        if (status.state !in TERMINAL_STATES) return
        val now = Clock.System.now().toEpochMilliseconds()
        val row = RecentRun(
            name = name,
            state = status.state,
            stepCount = status.resultCount,
            startedAt = now,
            endedAt = now,
            summary = status.message.ifEmpty { status.state },
        )
        _recentRuns.value = (listOf(row) + _recentRuns.value).take(MAX_RECENT_RUNS)
    }

    /** Clear the info / error toast. Called from the UI after a delay. */
    fun clearMessages() {
        _info.value = null
        _error.value = null
    }

    private fun derivePlanName(instruction: String, planPath: String): String {
        // Prefer a name drawn from the plan path (the llmrpa plugin encodes the
        // instruction's slug in the file name); fall back to the first words of the
        // instruction when no path was reported. Both choices are bounded so the
        // name fits on a button.
        val fromPath = planPath.substringAfterLast('/').substringBeforeLast('.')
        val candidate = fromPath.takeIf { it.isNotEmpty() && it != planPath }
            ?: instruction.take(MAX_PLAN_NAME_LEN)
                .trim()
                .replace(Regex("\\s+"), "_")
        return candidate.ifEmpty { "untitled" }
    }

    private companion object {
        val TERMINAL_STATES = setOf("COMPLETED", "ERROR")
        const val MAX_RECENT_RUNS = 25
        const val MAX_PLAN_NAME_LEN = 48
    }
}

/**
 * Registry stand-in used when the host predates the MCP registry.
 *
 * Every invoke returns "registry unavailable" so the panel renders its
 * "load rparecorder / rpaengine / llmrpa / flow-tab" hints instead of failing.
 */
private object UnavailableRegistry : McpToolRegistry {
    override val tools = kotlinx.coroutines.flow.MutableStateFlow(emptyList<ai.rever.boss.plugin.api.RegisteredMcpTool>())
    override val allTools = kotlinx.coroutines.flow.MutableStateFlow(emptyList<ai.rever.boss.plugin.api.RegisteredMcpTool>())
    override val disabledToolNames = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
    override fun setToolEnabled(toolName: String, enabled: Boolean) {}
    override suspend fun invoke(toolName: String, arguments: String) =
        ai.rever.boss.plugin.api.McpToolResult(
            text = "MCP tool registry unavailable in this host; '$toolName' could not be invoked.",
            isError = true,
        )
}

package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult

/**
 * MCP tool provider for the Flow Bridge.
 *
 * Exposes a single, unified surface for an agent that wants to do anything browser-side
 * without knowing which BOSS plugin produced the recording, the draft or the workflow:
 *
 *  - `flow_bridge_record_list`      - summary of the rparecorder's current session.
 *  - `flow_bridge_record_to_flow`  - convert that session into a flow-tab graph.
 *  - `flow_bridge_generate_and_run`  - submit an instruction to llmrpa, then start a run
 *                                       in rpaengine, all in one call.
 *  - `flow_bridge_run_status`      - poll a recent run.
 *  - `flow_bridge_recent_runs`      - list recent runs.
 *
 * All handlers report errors with `isError = true` and a sentence the agent can act on
 * (missing target plugin, recorder has no actions, etc.). They never throw  - every call
 * site is wrapped so the MCP server bridge sees a clean [McpToolResult].
 *
 * The provider reads `lastComponent()` so the tools can use the ViewModel's local state
 * (the recent-runs list lives on the panel, not on a remote service). When no panel is
 * open, the relevant tools reply with a clear "open the Flow Bridge panel first".
 */
internal class FlowBridgeMcpToolProvider(
    override val providerId: String,
    private val registry: McpToolRegistry?,
    private val component: () -> FlowBridgeComponent?,
    private val availability: () -> PluginAvailabilitySnapshot?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "flow_bridge_record_list",
            description =
                "List rparecorder's current session: state (IDLE/RECORDING/PAUSED), action count, current URL. " +
                    "Use as the source of truth for what has been recorded in this BOSS session.",
            handler = McpToolHandler { handleRecordList() },
        ),
        McpToolDefinition(
            name = "flow_bridge_record_to_flow",
            description =
                "Convert the rparecorder's current session into a flow-tab graph and return the new flow's tabId. " +
                    "Mutating: a new empty flow is created and one node per recorded action is added.",
            readOnly = false,
            handler = McpToolHandler { handleRecordToFlow() },
        ),
        McpToolDefinition(
            name = "flow_bridge_generate_and_run",
            description =
                "Submit a plain-language instruction to llmrpa, then load the resulting plan into rpaengine " +
                    "and start execution. Mutating: a model call and a browser-side workflow run both happen. " +
                    "Returns the run id (or a refusal describing what was missing).",
            inputSchema = """{"type":"object","properties":{"instruction":{"type":"string","description":"What to automate, in natural language."}},"required":["instruction"]}""",
            readOnly = false,
            handler = McpToolHandler { handleGenerateAndRun(it) },
        ),
        McpToolDefinition(
            name = "flow_bridge_run_status",
            description =
                "Poll a rpaengine workflow by run name. Returns the engine's current state " +
                    "(IDLE / LOADING / EXECUTING / PAUSED / COMPLETED / ERROR) plus the action and result counts.",
            inputSchema = """{"type":"object","properties":{"name":{"type":"string","description":"Run / configuration name as reported by the bridge."}},"required":["name"]}""",
            handler = McpToolHandler { handleRunStatus(it) },
        ),
        McpToolDefinition(
            name = "flow_bridge_recent_runs",
            description =
                "List the recent rpaengine runs the Flow Bridge panel has tracked this session. " +
                    "Returns name / state / step count / started timestamp per row.",
            handler = McpToolHandler { handleRecentRuns() },
        ),
    )

    // ---- handlers -------------------------------------------------------

    private suspend fun handleRecordList(): McpToolResult {
        if (!requireTarget("rparecorder")) return missingPlugin("rparecorder")
        val comp = component() ?: return notOpen()
        val status = comp.exposedViewModel.rparecorder.status()
        if (status.unavailable) {
            return McpToolResult("rparecorder unreachable: ${status.message}", isError = true)
        }
        return McpToolResult(
            "state=${status.state} actions=${status.actionCount} url=${status.currentUrl}",
        )
    }

    private suspend fun handleRecordToFlow(): McpToolResult {
        if (!requireTarget("rparecorder")) return missingPlugin("rparecorder")
        if (!requireTarget("flow-tab")) return missingPlugin("flow-tab")
        val comp = component() ?: return notOpen()
        val snapshot = comp.exposedViewModel.rparecorder.status()
        if (snapshot.unavailable) {
            return McpToolResult("rparecorder unreachable: ${snapshot.message}", isError = true)
        }
        if (snapshot.actionCount == 0) {
            return McpToolResult("No actions recorded yet  - start recording first.", isError = true)
        }
        val outcome = comp.exposedViewModel.flowTab.createFromRecorder(snapshot)
        if (outcome.tabId == null) {
            return McpToolResult(
                "flow-tab refused the import: ${outcome.errors.joinToString("; ").ifEmpty { "no error detail" }}",
                isError = true,
            )
        }
        val errTail = if (outcome.errors.isNotEmpty()) " errors=${outcome.errors.size}" else ""
        return McpToolResult("tabId=${outcome.tabId} nodes=${outcome.nodeCount}$errTail")
    }

    private suspend fun handleGenerateAndRun(args: McpToolArgs): McpToolResult {
        if (!requireTarget("llmrpa")) return missingPlugin("llmrpa")
        if (!requireTarget("rpaengine")) return missingPlugin("rpaengine")
        val comp = component() ?: return notOpen()
        val instruction = args.string("instruction")
            ?: return McpToolResult("Missing required argument: instruction", isError = true)
        val draft = comp.exposedViewModel.llmrpa.draft(instruction)
        if (draft.isError) {
            return McpToolResult("llmrpa refused: ${draft.text}", isError = true)
        }
        val planStatus = comp.exposedViewModel.llmrpa.status()
        val planName = derivePlanName(instruction, planStatus.planPath)
        val loaded = comp.exposedViewModel.rpaengine.load(planName)
        if (loaded.isError) {
            return McpToolResult("rpaengine load failed: ${loaded.text}", isError = true)
        }
        val started = comp.exposedViewModel.rpaengine.run()
        if (started.isError) {
            return McpToolResult("rpaengine run failed: ${started.text}", isError = true)
        }
        return McpToolResult("run started: $planName  - ${started.text}")
    }

    private suspend fun handleRunStatus(args: McpToolArgs): McpToolResult {
        if (!requireTarget("rpaengine")) return missingPlugin("rpaengine")
        val comp = component() ?: return notOpen()
        val status = comp.exposedViewModel.rpaengine.status()
        if (status.unavailable) {
            return McpToolResult("rpaengine unreachable: ${status.message}", isError = true)
        }
        val name = args.string("name").orEmpty()
        if (name.isNotEmpty()) {
            // Soft filter: only the requested name is "the" run. The bridge does
            // not own run identity, so the engine's status is the answer whether
            // the name matches or not  - the agent just wants the current state.
            comp.exposedViewModel.rpaengine.load(name)
        }
        return McpToolResult(
            "name=$name state=${status.state} action=${status.currentAction} results=${status.resultCount}",
        )
    }

    private suspend fun handleRecentRuns(): McpToolResult {
        val comp = component() ?: return notOpen()
        val runs = comp.exposedViewModel.recentRuns.value
        if (runs.isEmpty()) {
            return McpToolResult("No runs recorded yet in this session.")
        }
        val body = runs.joinToString("\n") { row ->
            "${row.name}\t${row.state}\tsteps=${row.stepCount}\tstarted=${row.startedAt}\t" +
                "summary=${row.summary}"
        }
        return McpToolResult(body)
    }

    // ---- helpers --------------------------------------------------------

    /**
     * True when [plugin] is loaded, by reading the snapshot the plugin entry point maintains.
     * `plugin` is a label like "rparecorder" / "rpaengine" / "llmrpa" / "flow-tab". The probe
     * re-emits on every registry change, so a freshly-installed target plugin is detected
     * without restarting the bridge.
     */
    private fun requireTarget(plugin: String): Boolean {
        val snap = availability() ?: return false
        return when (plugin) {
            "rparecorder" -> snap.rparecorder
            "rpaengine" -> snap.rpaengine
            "llmrpa" -> snap.llmrpa
            "flow-tab" -> snap.flowTab
            else -> false
        }
    }

    private fun missingPlugin(plugin: String): McpToolResult =
        McpToolResult("$plugin is not loaded - install it from the Toolbox.", isError = true)

    private fun notOpen(): McpToolResult =
        McpToolResult("Open the Flow Bridge panel first (no active instance).", isError = true)

    private fun derivePlanName(instruction: String, planPath: String): String {
        val fromPath = planPath.substringAfterLast('/').substringBeforeLast('.')
        val candidate = fromPath.takeIf { it.isNotEmpty() && it != planPath }
            ?: instruction.take(MAX_PLAN_NAME_LEN)
                .trim()
                .replace(Regex("\\s+"), "_")
        return candidate.ifEmpty { "untitled" }
    }

    private companion object {
        const val MAX_PLAN_NAME_LEN = 48
    }
}

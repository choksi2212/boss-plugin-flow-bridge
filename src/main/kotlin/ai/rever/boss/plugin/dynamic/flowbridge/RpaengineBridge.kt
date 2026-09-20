package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult

/**
 * Bridge to the rpaengine plugin.
 *
 * rpaengine exposes:
 *  - `rpa_status`   - `status=<IDLE|...> action=<index> results=<count>`.
 *  - `rpa_results`  - per-action outcome of the last run.
 *  - `rpa_load`     - load a saved configuration by name (mutating).
 *  - `rpa_run`      - start/resume the currently-loaded workflow (mutating).
 *  - `rpa_stop`     - stop the running workflow (mutating).
 *
 * The bridge adds two things the panel and the unified MCP tools need:
 *  - a "list recent runs" surface, since rpaengine has no such tool of its own. Runs
 *    are tracked in [RecentRuns] inside the panel component and surfaced via the
 *    bridge for the MCP `flow_bridge_recent_runs` tool.
 *  - a snapshot of the current execution status (parsed from `rpa_status` text).
 */
class RpaengineBridge(
    private val registry: McpToolRegistry,
) {
    /** Read the engine's current execution status as a snapshot. */
    suspend fun status(): EngineStatus {
        val raw = registry.invoke("rpa_status", "{}")
        return if (raw.isError) {
            EngineStatus(unavailable = true, message = raw.text)
        } else {
            parseStatus(raw.text)
        }
    }

    /** Read the per-action outcome of the last run. The engine truncates to a tail. */
    suspend fun results(): McpToolResult = registry.invoke("rpa_results", "{}")

    /** Load a saved configuration by name. The engine accepts substring matches. */
    suspend fun load(name: String): McpToolResult =
        registry.invoke("rpa_load", "{\"name\":\"${escape(name)}\"}")

    /** Start/resume execution. */
    suspend fun run(): McpToolResult = registry.invoke("rpa_run", "{}")

    /** Stop the running workflow. */
    suspend fun stop(): McpToolResult = registry.invoke("rpa_stop", "{}")

    private fun parseStatus(text: String): EngineStatus {
        var state = "UNKNOWN"
        var action = -1
        var results = 0
        for (chunk in text.split(' ')) {
            val eq = chunk.indexOf('=')
            if (eq <= 0) continue
            when (chunk.substring(0, eq)) {
                "status" -> state = chunk.substring(eq + 1)
                "action" -> action = chunk.substring(eq + 1).toIntOrNull() ?: -1
                "results" -> results = chunk.substring(eq + 1).toIntOrNull() ?: 0
            }
        }
        return EngineStatus(state = state, currentAction = action, resultCount = results)
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Engine state parsed from rpaengine's `rpa_status`.
 *
 * `unavailable` is true when the bridge could not read the status (target plugin
 * unloaded or call failed). `message` then carries whatever the engine said.
 */
data class EngineStatus(
    val state: String = "UNKNOWN",
    val currentAction: Int = -1,
    val resultCount: Int = 0,
    val unavailable: Boolean = false,
    val message: String = "",
)

/**
 * One row in the "Recent runs" panel section.
 *
 * Stored by [FlowBridgeComponent] every time the engine moves into a terminal state
 * (COMPLETED or ERROR) or stops. Surfaced through [RpaengineBridge.recentRuns] for the
 * MCP `flow_bridge_recent_runs` tool.
 */
data class RecentRun(
    val name: String,
    val state: String,
    val stepCount: Int,
    val startedAt: Long,
    val endedAt: Long,
    val summary: String,
)

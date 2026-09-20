package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult

/**
 * Bridge to the rparecorder plugin.
 *
 * rparecorder is loaded in a separate repo and exposes its MCP tools by these names:
 *  - `rpa_record_status`  - `state=<IDLE|RECORDING|PAUSED> actions=<count> url=<current>`
 *  - `rpa_record_toggle`  - start/stop recording.
 *  - `rpa_record_clear`   - drop all recorded actions.
 *
 * There is no `rpa_record_list` tool, so this bridge answers "what does the recorder
 * have right now?" by reading `rpa_record_status` and surfacing the action count and
 * current URL as the only visible artefact. A future rparecorder that ships a
 * `rpa_record_list` tool would slot in here without changing call sites.
 *
 * Every method takes [registry] freshly because the registry is host-owned and the
 * plugin's reference to it can be cleared on dispose  - never cache.
 */
class RparecorderBridge(
    private val registry: McpToolRegistry,
) {
    /**
     * Parse `rpa_record_status`'s "key=value" text into a [RecorderStatus].
     *
     * The format is documented but not stable across rparecorder versions, so a
     * missing or extra field falls back to defaults rather than failing  - the panel
     * surfaces whatever the recorder reports.
     */
    suspend fun status(): RecorderStatus {
        val raw = registry.invoke("rpa_record_status", "{}")
        return if (raw.isError) {
            RecorderStatus(unavailable = true, message = raw.text)
        } else {
            parseStatus(raw.text)
        }
    }

    /**
     * Start/stop the recording.
     *
     * The recorder's MCP tool returns "Toggled recording (now X)" which already
     * carries the new state; we pass that through. Errors from the recorder are
     * surfaced verbatim so the panel can show what the recorder said.
     */
    suspend fun toggle(): McpToolResult =
        registry.invoke("rpa_record_toggle", "{}")

    /** Drop all recorded actions. */
    suspend fun clear(): McpToolResult =
        registry.invoke("rpa_record_clear", "{}")

    private fun parseStatus(text: String): RecorderStatus {
        var state = "UNKNOWN"
        var actions = 0
        var url = ""
        for (chunk in text.split(' ')) {
            val eq = chunk.indexOf('=')
            if (eq <= 0) continue
            val key = chunk.substring(0, eq)
            val value = chunk.substring(eq + 1)
            when (key) {
                "state" -> state = value
                "actions" -> actions = value.toIntOrNull() ?: 0
                "url" -> url = value
            }
        }
        return RecorderStatus(state = state, actionCount = actions, currentUrl = url)
    }
}

/**
 * Recorder state read from rparecorder's `rpa_record_status`.
 *
 * `unavailable` is true when the bridge could not read the status  - the target plugin
 * was unloaded, the registry refused the call, or the result carried `isError`. The
 * `message` then carries whatever the recorder said, so the UI can surface it.
 */
data class RecorderStatus(
    val state: String = "UNKNOWN",
    val actionCount: Int = 0,
    val currentUrl: String = "",
    val unavailable: Boolean = false,
    val message: String = "",
)

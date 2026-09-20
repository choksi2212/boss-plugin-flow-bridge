package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult

/**
 * Bridge to the llmrpa plugin.
 *
 * llmrpa exposes:
 *  - `llmrpa_status`  - `generating=<bool> history=<n> last=<status> actions=<n> plan=<path>`.
 *  - `llmrpa_run`     - set a natural-language instruction and ask the model to draft
 *                      a runnable action list (mutating).
 *
 * The bridge sends an instruction through `llmrpa_run` and reads back via `llmrpa_status`.
 * The status text is parsed lightly into an [LlmStatus] so the panel can render counts
 * without re-implementing the format; an unparseable line keeps the raw text in [message].
 */
class LlmrpaBridge(
    private val registry: McpToolRegistry,
) {
    /** Read the current draft status. */
    suspend fun status(): LlmStatus {
        val raw = registry.invoke("llmrpa_status", "{}")
        return if (raw.isError) {
            LlmStatus(unavailable = true, message = raw.text)
        } else {
            parseStatus(raw.text)
        }
    }

    /**
     * Ask the model to draft actions for [instruction].
     *
     * Returns the raw MCP result verbatim  - `llmrpa_run` replies with "Generating..."
     * or a refusal string with `isError = true`. The caller (panel section) shows
     * both.
     */
    suspend fun draft(instruction: String): McpToolResult =
        registry.invoke("llmrpa_run", "{\"instruction\":\"${escape(instruction)}\"}")

    private fun parseStatus(text: String): LlmStatus {
        var generating = false
        var history = 0
        var lastStatus = "NONE"
        var actions = 0
        var plan = ""
        var message = ""
        var error = ""
        var instruction = ""

        // The status text is multi-line; values may contain spaces (e.g. the message body),
        // so we only parse the single-line `key=value` portion at the top.
        val head = text.lineSequence().firstOrNull().orEmpty()
        for (chunk in head.split(' ')) {
            val eq = chunk.indexOf('=')
            if (eq <= 0) continue
            when (chunk.substring(0, eq)) {
                "generating" -> generating = chunk.substring(eq + 1) == "true"
                "history" -> history = chunk.substring(eq + 1).toIntOrNull() ?: 0
                "last" -> lastStatus = chunk.substring(eq + 1)
                "actions" -> actions = chunk.substring(eq + 1).toIntOrNull() ?: 0
                "plan" -> plan = chunk.substring(eq + 1)
                "message" -> message = chunk.substring(eq + 1)
                "error" -> error = chunk.substring(eq + 1)
                "instruction" -> instruction = chunk.substring(eq + 1)
            }
        }

        return LlmStatus(
            generating = generating,
            historyCount = history,
            lastStatus = lastStatus,
            actionCount = actions,
            planPath = plan,
            message = message,
            error = error,
            instruction = instruction,
            rawText = text,
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Status read from llmrpa's `llmrpa_status`.
 *
 * Most fields are parsed from the single-line `key=value` prefix the plugin emits.
 * `rawText` keeps the full status text  - useful when the model returns a long message
 * line that the panel wants to render as-is.
 */
data class LlmStatus(
    val generating: Boolean = false,
    val historyCount: Int = 0,
    val lastStatus: String = "NONE",
    val actionCount: Int = 0,
    val planPath: String = "",
    val message: String = "",
    val error: String = "",
    val instruction: String = "",
    val rawText: String = "",
    val unavailable: Boolean = false,
)

package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Snapshot of which target plugins are loaded right now.
 *
 * Keyed on a stable id so a single map can drive every section of the panel and every
 * tool of the bridge without each consumer having to ask "is rparecorder here?".
 */
data class PluginAvailabilitySnapshot(
    val rparecorder: Boolean = false,
    val rpaengine: Boolean = false,
    val llmrpa: Boolean = false,
    val flowTab: Boolean = false,
) {
    val allLoaded: Boolean get() = rparecorder && rpaengine && llmrpa && flowTab
    val noneLoaded: Boolean get() = !rparecorder && !rpaengine && !llmrpa && !flowTab
}

/**
 * Live probe of the host's MCP tool registry, answering "is target plugin X loaded?".
 *
 * Each target plugin owns a known prefix on its tool names (rparecorder → `rpa_record_*`,
 * rpaengine → `rpa_*` except `rpa_record_*`, llmrpa → `llmrpa_*`, flow-tab → `flow_*` and
 * `prompt_*`). The bridge has no compile-time references to the four plugins, so this is
 * the only signal it can rely on. Re-emits on every register/unregister anywhere in the
 * registry  - exactly what `McpToolProvider.tools` documents is the recompute trigger.
 *
 * `rpa_record_*` and `rpa_*` overlap as prefixes, so we resolve by EXACT match on
 * `rpa_record_*` first, then by `rpa_*` minus that set, then by `llmrpa_*`, then by
 * `flow_*` / `prompt_*`. A tool name that lives in two plugins would be ambiguous; no
 * current BOSS plugin declares overlapping prefixes, so we keep it simple.
 */
class PluginAvailability(
    private val registry: McpToolRegistry,
) {
    private val _value = MutableStateFlow(PluginAvailabilitySnapshot())

    /** Current availability. Re-emits on every registry change. */
    val value: StateFlow<PluginAvailabilitySnapshot> = _value.asStateFlow()

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        // Seed once so the first composition renders the right state, then re-emit on every
        // registry change. Both observers are intentional  - `allTools` carries disabled and
        // permission-denied entries, which is the right answer for "is the plugin loaded".
        recompute(registry.allTools.value)
        scope.launch {
            registry.allTools.collect { tools -> recompute(tools) }
        }
    }

    private fun recompute(tools: List<RegisteredMcpTool>) {
        var rparecorder = false
        var rpaengine = false
        var llmrpa = false
        var flowTab = false

        for (tool in tools) {
            val name = tool.definition.name
            when {
                name.startsWith("rpa_record_") -> rparecorder = true
                name.startsWith("rpa_") -> rpaengine = true
                name.startsWith("llmrpa_") -> llmrpa = true
                name.startsWith("flow_") || name.startsWith("prompt_") -> flowTab = true
            }
        }

        val next = PluginAvailabilitySnapshot(
            rparecorder = rparecorder,
            rpaengine = rpaengine,
            llmrpa = llmrpa,
            flowTab = flowTab,
        )
        // Skip emission on no-change so the UI doesn't recompose on every other plugin's churn.
        if (next != _value.value) {
            _value.value = next
        }
    }
}

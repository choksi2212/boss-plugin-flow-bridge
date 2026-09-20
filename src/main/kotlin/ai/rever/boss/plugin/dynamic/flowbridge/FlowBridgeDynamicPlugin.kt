package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

/**
 * Flow Bridge dynamic plugin - Loaded from external JAR.
 *
 * Wires four independent BOSS plugins together so a user can record a browser
 * session, draft an instruction in plain language, and replay either one against
 * a live tab - from a single panel and a single MCP surface.
 *
 * The four target plugins (rparecorder, rpaengine, llmrpa, flow-tab) live in
 * separate repos. This plugin has no compile-time references to them, so the
 * bridge reaches them exclusively through MCP tool names registered in the
 * host's [ai.rever.boss.plugin.api.McpToolRegistry]. Each section of the panel
 * gracefully reports a missing target plugin rather than crashing.
 *
 * Two extensions are registered here:
 *  - a sidebar panel (`FlowBridgeInfo`) listing the three bridge surfaces.
 *  - an MCP tool provider exposing `flow_bridge_*` tools, removed automatically
 *    when this plugin is disabled or unloaded.
 */
class FlowBridgeDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.flowbridge"
    override val displayName: String = "Flow Bridge (Dynamic)"
    override val version: String = manifestVersion()
    override val description: String =
        "Bridge between rparecorder, rpaengine, llmrpa and flow-tab - recordings become graphs, drafts become runs."
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-flow-bridge"

    // Last opened panel, so MCP tools can drive it (the four target plugins follow the same
    // shape: MCP tools drive the per-panel component, which owns the runtime state).
    @Volatile
    private var lastComponent: FlowBridgeComponent? = null

    // Plugin-side availability probe  - `PluginContext.mcpToolRegistry` is the only signal that
    // any of the four target plugins are loaded (each registers its tools into that registry).
    // Held so the panel and the MCP tools both subscribe to the same source.
    private var availability: PluginAvailability? = null

    override fun register(context: PluginContext) {
        val registry = context.mcpToolRegistry

        // The probe reads the registry once at registration and re-emits on every register/
        // unregister. Null when the host predates the registry  - every bridge section will
        // then read its target plugin as "not loaded" and offer Install / status lines.
        availability = registry?.let { PluginAvailability(it) }

        context.panelRegistry.registerPanel(FlowBridgeInfo) { ctx, panelInfo ->
            FlowBridgeComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                availability = availability,
                registry = registry,
                pluginScope = context.pluginScope,
            ).also { comp ->
                lastComponent = comp
                // Clear on panel close: a destroyed component's scope is cancelled, so
                // MCP tools driving it would silently no-op with false success.
                ctx.lifecycle.doOnDestroy {
                    if (lastComponent === comp) {
                        lastComponent = null
                    }
                }
            }
        }

        // Contribute flow_bridge_* MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(
            FlowBridgeMcpToolProvider(
                providerId = pluginId,
                registry = registry,
                component = { lastComponent },
                availability = { availability?.value?.value },
            )
        )
    }

    override fun dispose() {
        lastComponent = null
        availability = null
    }

    /**
     * The version from *this* plugin's manifest.
     *
     * Every BOSS plugin ships `/META-INF/boss-plugin/plugin.json` at the same resource path,
     * so a single `getResourceAsStream` returns whichever jar comes first if the host ever
     * loads plugins through a shared or parent-first classloader  - and this plugin would
     * report someone else's version. Only the one naming this plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}

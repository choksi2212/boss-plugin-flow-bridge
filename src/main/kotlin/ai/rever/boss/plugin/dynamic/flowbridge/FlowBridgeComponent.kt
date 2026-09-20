package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Flow Bridge panel component.
 *
 * The ViewModel is the single source of truth; the component subscribes to it for the
 * availability snapshot so the panel re-renders when a target plugin loads or unloads.
 *
 * `PluginAvailability` is held by the plugin entry point  - the component reads from it
 * rather than constructing its own, so a host with multiple Flow Bridge panels does not
 * maintain multiple probes. The probe re-emits on every registry change (see its KDoc),
 * which is the trigger the panel needs to redraw each "is X loaded?" section header.
 */
class FlowBridgeComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val availability: PluginAvailability?,
    private val registry: McpToolRegistry?,
    private val pluginScope: CoroutineScope,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = FlowBridgeViewModel(registry, pluginScope)

    /** Exposed so the MCP tool provider can drive the bridge from the registry. */
    val exposedViewModel: FlowBridgeViewModel get() = viewModel

    @Composable
    override fun Content() {
        // Observe availability on the composition so the section headers re-render when
        // a target plugin loads or unloads. The probe itself is owned by the plugin
        // entry point; the component only reads it.
        val snapshotState = availability?.value?.collectAsState()?.value
            ?: PluginAvailabilitySnapshot()
        FlowBridgeContent(viewModel = viewModel, availability = snapshotState)
    }
}

package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Shuffle

/**
 * Flow Bridge panel info.
 *
 * Lives in the right sidebar, top-of-right-top slot  - same neighbourhood as the four
 * plugins it wires together (rparecorder, rpaengine, llmrpa) and above them in priority
 * (21) so its panel is the first one users see when they enable the bridge.
 */
object FlowBridgeInfo : PanelInfo {
    override val id = PanelId("flow-bridge", 21)
    override val displayName = "Flow Bridge"
    override val icon = FeatherIcons.Shuffle
    override val defaultSlotPosition = right.top.top
}

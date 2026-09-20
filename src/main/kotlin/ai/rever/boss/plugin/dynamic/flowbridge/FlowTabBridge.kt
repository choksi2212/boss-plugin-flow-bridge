package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult

/**
 * Bridge to the flow-tab plugin.
 *
 * flow-tab exposes a fixed set of MCP tools the agent (and this bridge) reach by
 * name. We use the same surface the user would reach in a terminal:
 *  - `flow_create`  - create an empty flow (returns `{tabId}`).
 *  - `flow_add_node`  - add a node to a flow (returns `{nodeId}`).
 *  - `flow_connect`  - connect two nodes by id and port.
 *  - `flow_run`  - start a flow asynchronously (returns `{runId}`).
 *  - `flow_status` / `flow_result`  - poll a run.
 *  - `flow_list` / `flow_get`  - discover / inspect flows.
 *  - `prompt_upsert` / `prompt_get` / `prompt_list`  - manage composable prompts.
 *
 * "Convert a recorded session into a flow" reduces to: create an empty flow, walk the
 * recorder's actions, and for each one add a node of the corresponding kind. We don't
 * have a list-recorder tool, so the bridge accepts a recorder snapshot (state + actions
 * + URL) and produces a small starter graph: a `navigate` node to the starting URL,
 * one `click`/`input`/etc node per recorded action, and a single chain of connections.
 *
 * Every flow tool replies with JSON the host parses; the bridge forwards it verbatim
 * and only the create/add calls need a parsed `tabId` / `nodeId` to chain further
 * operations.
 */
class FlowTabBridge(
    private val registry: McpToolRegistry,
) {
    /** List every stored flow's tabId. The reply is `{flows:[...]}` JSON. */
    suspend fun listFlows(): McpToolResult = registry.invoke("flow_list", "{}")

    /**
     * Create an empty flow with optional metadata.
     *
     * Returns the raw result text (JSON with `tabId`)  - the bridge's
     * [createFromRecorder] parses `tabId` out of it for chaining. Failure is reported
     * by `isError` on the result.
     */
    suspend fun createFlow(name: String = "", description: String = ""): McpToolResult {
        val payload = buildString {
            append('{')
            if (name.isNotEmpty()) append("\"name\":\"${escape(name)}\"")
            if (description.isNotEmpty()) {
                if (name.isNotEmpty()) append(',')
                append("\"description\":\"${escape(description)}\"")
            }
            append('}')
        }
        return registry.invoke("flow_create", payload)
    }

    /**
     * Add a node to an existing flow.
     *
     * `kind` is the node's kind-id (e.g. "browser.click", "browser.input",
     * "browser.navigate"). `config` is the node's config object as a JSON string.
     * `tabId` is the flow to add to.
     */
    suspend fun addNode(tabId: String, kind: String, config: String): McpToolResult {
        val payload = "{\"tabId\":\"${escape(tabId)}\",\"kind\":\"${escape(kind)}\"," +
            "\"config\":${config.ifEmpty { "{}" }}}"
        return registry.invoke("flow_add_node", payload)
    }

    /**
     * Connect two nodes by id and optional port index.
     *
     * `fromPort` and `toPort` default to 0 in the flow-tab tool itself; we always
     * pass them so the bridge's wire shape is one thing.
     */
    suspend fun connect(
        tabId: String,
        from: String,
        to: String,
        fromPort: Int = 0,
        toPort: Int = 0,
    ): McpToolResult {
        val payload = "{\"tabId\":\"${escape(tabId)}\",\"from\":\"${escape(from)}\"," +
            "\"fromPort\":$fromPort,\"to\":\"${escape(to)}\",\"toPort\":$toPort}"
        return registry.invoke("flow_connect", payload)
    }

    /** Start a flow running asynchronously. Returns `{runId}`. */
    suspend fun runFlow(tabId: String): McpToolResult {
        val payload = "{\"tabId\":\"${escape(tabId)}\"}"
        return registry.invoke("flow_run", payload)
    }

    /** Pull the current state of a run. */
    suspend fun runStatus(runId: String): McpToolResult {
        val payload = "{\"runId\":\"${escape(runId)}\"}"
        return registry.invoke("flow_status", payload)
    }

    /**
     * Convert a recorder snapshot into a flow graph.
     *
     * Each `RecorderStatus.actionCount` action becomes one node of the corresponding
     * kind, connected in sequence with a leading `browser.navigate` to the recorder's
     * current URL. The kind ids used here are the flow-tab defaults for browser work;
     * if flow-tab renames them, this is the one place that needs updating.
     *
     * Returns the new flow's tabId, or null if any step failed. Errors are logged
     * into [FlowImportOutcome.errors] so the panel can show them and the caller can
     * decide whether to retry.
     */
    suspend fun createFromRecorder(
        snapshot: RecorderStatus,
        flowName: String = "Recorded: ${snapshot.currentUrl.ifEmpty { "session" }}",
    ): FlowImportOutcome {
        if (snapshot.unavailable || snapshot.actionCount == 0) {
            return FlowImportOutcome(
                tabId = null,
                nodeCount = 0,
                errors = if (snapshot.unavailable) listOf(snapshot.message) else emptyList(),
            )
        }

        val createResult = createFlow(name = flowName, description = "Imported recorder session")
        val tabId = parseStringField(createResult.text, "tabId")
        if (tabId == null || createResult.isError) {
            return FlowImportOutcome(
                tabId = null,
                nodeCount = 0,
                errors = listOfNotNull(
                    if (createResult.isError) "flow_create: ${createResult.text}" else null,
                    if (tabId == null) "flow_create returned no tabId" else null,
                ),
            )
        }

        val errors = mutableListOf<String>()
        var previousNodeId: String? = null
        var addedNodes = 0

        // Leading navigate node  - uses the recorder's current URL so the flow lands on
        // the page the recording started from. Without this, the chain begins with a
        // click on a page nobody navigated to.
        if (snapshot.currentUrl.isNotEmpty()) {
            val navKind = "browser.navigate"
            val navConfig = "{\"url\":\"${escape(snapshot.currentUrl)}\"}"
            val addResult = addNode(tabId, navKind, navConfig)
            val navId = parseStringField(addResult.text, "nodeId")
            if (navId != null && !addResult.isError) {
                previousNodeId = navId
                addedNodes++
            } else if (addResult.isError) {
                errors += "flow_add_node(navigate): ${addResult.text}"
            }
        }

        // One node per recorded action. We do not know each action's specific type
        // (the recorder MCP status is summary-only); this default kinds array maps the
        // most common browser verbs 1:1 with the recorder's action order. A future
        // rparecorder that exposes per-action verbs would let us vary the kind here.
        val kinds = defaultActionKinds(snapshot.actionCount)
        for (kind in kinds) {
            val addResult = addNode(tabId, kind, "{}")
            val nodeId = parseStringField(addResult.text, "nodeId")
            if (nodeId == null || addResult.isError) {
                if (addResult.isError) errors += "flow_add_node($kind): ${addResult.text}"
                continue
            }
            addedNodes++
            if (previousNodeId != null) {
                val connResult = connect(tabId, previousNodeId, nodeId)
                if (connResult.isError) errors += "flow_connect: ${connResult.text}"
            }
            previousNodeId = nodeId
        }

        return FlowImportOutcome(
            tabId = tabId,
            nodeCount = addedNodes,
            errors = errors,
        )
    }

    /**
     * One node per recorded action. We don't know per-action kinds, so we cycle through
     * the most likely sequence: click, input, click, ...  - a recording is usually
     * "navigate, click, type, click" and that is what this approximation produces.
     */
    private fun defaultActionKinds(count: Int): List<String> {
        val pattern = listOf("browser.click", "browser.input", "browser.click", "browser.input")
        return List(count) { pattern[it % pattern.size] }
    }

    private fun parseStringField(text: String, field: String): String? {
        // Tiny, parse-defensively extraction: find `"<field>":"<value>"` and read until
        // the next unescaped quote. Doesn't tolerate escaped quotes inside the value,
        // which is fine for tabId/nodeId (they're short identifiers).
        val key = "\"$field\":\""
        val start = text.indexOf(key)
        if (start < 0) return null
        val valueStart = start + key.length
        val end = text.indexOf('"', valueStart)
        if (end < 0) return null
        return text.substring(valueStart, end)
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Result of converting a recorder snapshot into a flow.
 *
 * `tabId` is the new flow when the create succeeded. `nodeCount` is the number of
 * nodes actually added (less than the recorder's action count is a partial success
 *  - usually because flow-tab refused one of the kinds). `errors` is whatever failed
 * along the way, surfaced to the panel verbatim.
 */
data class FlowImportOutcome(
    val tabId: String?,
    val nodeCount: Int,
    val errors: List<String>,
) {
    val success: Boolean get() = tabId != null && errors.isEmpty()
}

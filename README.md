# BOSS Flow Bridge

One panel and one MCP surface that wire four independent BOSS plugins together:

- **rparecorder**  - records browser interactions into a JSON session.
- **rpaengine**  - replays a recorded or generated action list against a live tab.
- **llmrpa**  - drafts an action list from a plain-language instruction.
- **flow-tab**  - turns the action list into a node graph an operator can edit.

Today each plugin stands alone: a recording does not become a graph, a generated plan
does not automatically run, and an agent has to know which plugin owns each tool.
Flow Bridge fixes that. It owns nothing of its own  - it reaches the four plugins
exclusively through MCP tool names registered in the host's `McpToolRegistry`, so it
compiles and runs without their sources on the classpath.

## What it does

- A single **Flow Bridge** panel in the right sidebar with three sections.
- A unified set of **`flow_bridge_*`** MCP tools an in-terminal agent can call without
  knowing which of the four target plugins it is reaching through.
- Graceful degradation: a missing target plugin renders a status banner instead of
  breaking the panel, and the corresponding `flow_bridge_*` tool returns a clear
  "plugin not loaded" error.

### Recorded sessions

Lists rparecorder's current session (state, action count, current URL) with three
buttons: **Start / Stop** (toggles recording), **Clear** (drops actions) and
**Convert to flow** (which calls `flow_create` then `flow_add_node` per recorded action
and chains them with `flow_connect`).

### Generated actions

A single-line instruction box and a **Generate and run** button. One click submits to
`llmrpa_run`, reads the generated plan path from `llmrpa_status`, loads it through
`rpa_load` and starts it through `rpa_run`. Failures are surfaced verbatim from the
underlying plugins.

### Recent runs

Tracks every rpaengine run that reaches a terminal state (COMPLETED or ERROR) and
keeps the last 25. The list is local to this session  - rpaengine has no list endpoint
of its own, so the bridge synthesises what it has seen.

## MCP tools

| Tool | Purpose |
|---|---|
| `flow_bridge_record_list` | Current rparecorder state, action count, URL. |
| `flow_bridge_record_to_flow` | Convert the recorder's session into a new flow-tab graph. Mutating. |
| `flow_bridge_generate_and_run` | Submit an instruction to llmrpa, load the plan into rpaengine and start it. Mutating. |
| `flow_bridge_run_status` | Poll rpaengine for current execution state. |
| `flow_bridge_recent_runs` | Recent runs tracked by the bridge this session. |

Every handler returns a `McpToolResult` with `isError = true` and a sentence the agent
can act on when a target plugin is missing or refuses a call. They never throw.

## Degradation

The bridge reads `context.mcpToolRegistry` and watches its `allTools` flow for the
prefixes that identify each target plugin (`rpa_record_*` for rparecorder, `rpa_*` for
rpaengine, `llmrpa_*` for llmrpa, `flow_*` / `prompt_*` for flow-tab). Sections for a
missing target render a status banner ("X not loaded - install from Toolbox") and
disable their actions; the MCP tool returns a clear "X is not loaded" error. On a host
that predates `McpToolRegistry` the bridge treats every section as missing and replies
the same way.

## Requirements

- BOSS >= 9.5.0, `boss-plugin-api` >= 1.0.93.
- The four target plugins are recommended but not required; absent ones are reported,
  not crashed on.

## Install

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-flow-bridge-0.1.0.jar ~/.boss/plugins/
```

Then enable Flow Bridge from the Toolbox and open its panel from the right sidebar.
Install the four target plugins to unlock the corresponding sections and tools.

## License

Proprietary - Risa Labs Inc.

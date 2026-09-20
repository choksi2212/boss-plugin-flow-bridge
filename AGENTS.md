# AGENTS.md

## Project Overview

**Flow Bridge** (`ai.rever.boss.plugin.dynamic.flowbridge`) is a dynamic plugin for the
BOSS desktop application.

A bridge between rparecorder, rpaengine, llmrpa and flow-tab - recordings become
graphs, drafts become runs.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.flowbridge`
- **Main Class**: `ai.rever.boss.plugin.dynamic.flowbridge.FlowBridgeDynamicPlugin`
- **API Version**: 1.0.93

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure

```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.flowbridge)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns

- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`.
- UI: `PanelComponentWithUI` with `@Composable Content()`.
- State: ViewModel pattern with `StateFlow`.
- The bridge has no compile-time references to the four target plugins; it reaches
  them through `PluginContext.mcpToolRegistry.invoke(name, args)`.
- `PluginAvailability` watches the registry's `allTools` flow and decides which
  target plugins are loaded by the prefixes on their tool names.
- MCP tools that drive the panel's local state read `lastComponent()`  - same shape
  the four target plugins use internally.

### Dependencies

- **boss-plugin-api**: compileOnly (provided by host app at runtime).
- **Compose Desktop**: UI framework.
- **Decompose**: Navigation and component lifecycle.
- **Coroutines**: Async operations.
- **kotlinx.serialization**: JSON for MCP argument payloads.

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at
build time. Never manually edit the version in `plugin.json`  - only change it in
`build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific).
- All Kotlin files must end with a newline.
- Handle null providers gracefully  - show fallback UI, never crash.

## CI/CD

Pushes to `main` trigger the release workflow which:

1. Builds the plugin JAR.
2. Creates a GitHub release.
3. Publishes to the BOSS Plugin Store.

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared
workflow in `risa-labs-inc/BossConsole-Releases`.

Pull requests run the test workflow at `.github/workflows/test.yml`, which builds the
JAR against the downloaded `boss-plugin-api` jar.

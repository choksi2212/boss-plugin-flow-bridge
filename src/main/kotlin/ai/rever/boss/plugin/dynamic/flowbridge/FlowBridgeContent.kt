package ai.rever.boss.plugin.dynamic.flowbridge

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The Flow Bridge panel.
 *
 * Three collapsible-ish sections stacked vertically:
 *  1. Recorded sessions  - rparecorder status + a "Convert to flow" button + a "Replay" button.
 *  2. Generated actions  - a single-line instruction box, "Generate and run" button, last plan info.
 *  3. Recent runs  - list of recent rpaengine runs (synthesised locally by the ViewModel).
 *
 * Each section has a header row showing whether its target plugin is loaded. A missing
 * plugin renders an inline status line ("rparecorder not loaded - install from Toolbox")
 * and disables the section's actions; the buttons a missing plugin gates are hidden, not
 * greyed out, so a user who never installed rparecorder doesn't see a control that does
 * nothing.
 *
 * Toast messages for success / failure ride on the ViewModel's `_info` / `_error`
 * flows; the same UI handles both.
 */
@Composable
fun FlowBridgeContent(
    viewModel: FlowBridgeViewModel,
    availability: PluginAvailabilitySnapshot,
) {
    BossTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderRow(onRefresh = { viewModel.refreshAll() })

                Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

                val info by viewModel.info.collectAsState()
                val error by viewModel.error.collectAsState()

                Toast(info = info, error = error, onDismiss = { viewModel.clearMessages() })

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        RecordedSessionsSection(
                            viewModel = viewModel,
                            rparecorderLoaded = availability.rparecorder,
                            rpaengineLoaded = availability.rpaengine,
                            flowTabLoaded = availability.flowTab,
                        )
                    }
                    item {
                        GeneratedActionsSection(
                            viewModel = viewModel,
                            llmrpaLoaded = availability.llmrpa,
                            rpaengineLoaded = availability.rpaengine,
                        )
                    }
                    item {
                        RecentRunsSection(
                            viewModel = viewModel,
                            rpaengineLoaded = availability.rpaengine,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Flow Bridge",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Toast(
    info: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    if (info == null && error == null) return
    LaunchedEffect(info, error) {
        delay(4000)
        onDismiss()
    }
    val message = error ?: info ?: return
    val isError = error != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.Error else Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = BossThemeColors.TextPrimary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = BossThemeColors.TextPrimary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, loaded: Boolean, hint: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (loaded) BossThemeColors.SuccessColor else BossThemeColors.WarningColor,
                    shape = RoundedCornerShape(4.dp),
                ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!loaded) {
            Text(
                text = "not loaded",
                fontSize = 10.sp,
                color = BossThemeColors.WarningColor,
            )
        } else if (hint != null) {
            Text(
                text = hint,
                fontSize = 10.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotLoadedBanner(plugin: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = BossThemeColors.WarningColor.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp),
            )
            .background(BossThemeColors.WarningColor.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = BossThemeColors.WarningColor,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$plugin not loaded - install from Toolbox",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

@Composable
private fun RecordedSessionsSection(
    viewModel: FlowBridgeViewModel,
    rparecorderLoaded: Boolean,
    rpaengineLoaded: Boolean,
    flowTabLoaded: Boolean,
) {
    val status by viewModel.recorderStatus.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
            )
            .background(MaterialTheme.colors.surface.copy(alpha = 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(
            title = "Recorded sessions",
            loaded = rparecorderLoaded,
            hint = if (rparecorderLoaded) "${status.actionCount} action(s)" else null,
        )

        if (!rparecorderLoaded) {
            NotLoadedBanner(plugin = "rparecorder")
        } else {
            RecorderStatusCard(
                status = status,
                rparecorderLoaded = rparecorderLoaded,
                rpaengineLoaded = rpaengineLoaded,
                flowTabLoaded = flowTabLoaded,
                onToggle = { viewModel.toggleRecording() },
                onClear = { viewModel.clearRecording() },
                onConvert = { viewModel.convertRecorderToFlow() },
            )
        }

        val lastImport by viewModel.lastImport.collectAsState()
        lastImport?.let { outcome ->
            ImportOutcomeCard(outcome)
        }
    }
}

@Composable
private fun RecorderStatusCard(
    status: RecorderStatus,
    rparecorderLoaded: Boolean,
    rpaengineLoaded: Boolean,
    flowTabLoaded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onConvert: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(recording = status.state == "RECORDING", paused = status.state == "PAUSED")
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "state: ${status.state}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${status.actionCount} action(s)",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            )
        }

        if (status.currentUrl.isNotEmpty()) {
            Text(
                text = status.currentUrl,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onToggle,
                modifier = Modifier.height(28.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (status.state == "RECORDING") BossThemeColors.WarningColor else BossThemeColors.SuccessColor,
                ),
            ) {
                Text(
                    text = if (status.state == "RECORDING") "Stop" else "Start",
                    fontSize = 11.sp,
                    color = BossThemeColors.TextPrimary,
                )
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.height(28.dp),
                enabled = status.actionCount > 0,
            ) {
                Text(text = "Clear", fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onConvert,
                modifier = Modifier.height(28.dp),
                enabled = status.actionCount > 0 && flowTabLoaded,
            ) {
                Text(
                    text = if (flowTabLoaded) "Convert to flow" else "Need flow-tab",
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(recording: Boolean, paused: Boolean) {
    val color = when {
        recording -> Color(0xFFEF5350)
        paused -> Color(0xFFFFB300)
        else -> Color(0xFFBDBDBD)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun ImportOutcomeCard(outcome: FlowImportOutcome) {
    val tone = if (outcome.success) BossThemeColors.SuccessColor else BossThemeColors.WarningColor
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = tone.copy(alpha = 0.4f),
                shape = RoundedCornerShape(4.dp),
            )
            .background(tone.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (outcome.success) {
                "Imported ${outcome.nodeCount} node(s) into new flow"
            } else if (outcome.tabId != null) {
                "Imported ${outcome.nodeCount} node(s); ${outcome.errors.size} error(s)"
            } else {
                "Import failed"
            },
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.weight(1f),
        )
        outcome.tabId?.let {
            Text(
                text = it.take(10),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }
    }
    if (outcome.errors.isNotEmpty()) {
        Text(
            text = outcome.errors.joinToString("\n") { "• $it" },
            fontSize = 10.sp,
            color = BossThemeColors.WarningColor,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun GeneratedActionsSection(
    viewModel: FlowBridgeViewModel,
    llmrpaLoaded: Boolean,
    rpaengineLoaded: Boolean,
) {
    val llm by viewModel.llmStatus.collectAsState()
    var instruction by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
            )
            .background(MaterialTheme.colors.surface.copy(alpha = 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(
            title = "Generated actions",
            loaded = llmrpaLoaded && rpaengineLoaded,
            hint = if (llmrpaLoaded) {
                if (llm.actionCount > 0) "${llm.actionCount} action(s) drafted" else "no draft yet"
            } else null,
        )

        if (!llmrpaLoaded) {
            NotLoadedBanner(plugin = "llmrpa")
        } else if (!rpaengineLoaded) {
            NotLoadedBanner(plugin = "rpaengine")
        } else {
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                placeholder = { Text("e.g. open Google and search for BOSS plugins", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                colors = TextFieldDefaults.outlinedTextFieldColors(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { viewModel.generateAndRun(instruction.trim()) },
                    modifier = Modifier.height(28.dp),
                    enabled = instruction.isNotBlank() && !llm.generating,
                ) {
                    if (llm.generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = BossThemeColors.TextPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = BossThemeColors.TextPrimary,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Generate and run", fontSize = 11.sp, color = BossThemeColors.TextPrimary)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (llm.planPath.isNotEmpty()) {
                    Text(
                        text = llm.planPath,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (llm.lastStatus != "NONE") {
                Text(
                    text = "last status: ${llm.lastStatus}" +
                        if (llm.error.isNotEmpty() && llm.error != "none") " (${llm.error})" else "",
                    fontSize = 10.sp,
                    color = if (llm.lastStatus == "ERROR") BossThemeColors.WarningColor
                        else MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun RecentRunsSection(
    viewModel: FlowBridgeViewModel,
    rpaengineLoaded: Boolean,
) {
    val engine by viewModel.engineStatus.collectAsState()
    val runs by viewModel.recentRuns.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
            )
            .background(MaterialTheme.colors.surface.copy(alpha = 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(
            title = "Recent runs",
            loaded = rpaengineLoaded,
            hint = if (rpaengineLoaded) {
                if (engine.state != "UNKNOWN") "engine: ${engine.state}" else null
            } else null,
        )

        if (!rpaengineLoaded) {
            NotLoadedBanner(plugin = "rpaengine")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${runs.size} run(s) tracked this session",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { viewModel.stopRun() },
                    modifier = Modifier.height(24.dp),
                    enabled = engine.state == "EXECUTING" || engine.state == "PAUSED",
                ) {
                    Text(text = "Stop", fontSize = 11.sp)
                }
            }

            if (runs.isEmpty()) {
                Text(
                    text = "Runs will appear here once they reach a terminal state.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                )
            } else {
                runs.forEach { row ->
                    RunRow(row)
                }
            }
        }
    }
}

@Composable
private fun RunRow(row: RecentRun) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = if (row.state == "COMPLETED") BossThemeColors.SuccessColor else BossThemeColors.ErrorColor,
                    shape = RoundedCornerShape(3.dp),
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.state} - ${row.stepCount} step(s)",
                fontSize = 10.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

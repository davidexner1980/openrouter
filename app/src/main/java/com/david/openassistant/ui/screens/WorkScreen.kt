package com.david.openassistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.agent.confidenceExplanation
import com.david.openassistant.agent.descriptiveSourceLabel
import com.david.openassistant.agent.descriptiveUrlLabel
import com.david.openassistant.agent.researchActivitySummary
import com.david.openassistant.agent.sourceRoleLabel
import com.david.openassistant.agent.normalizeAgentFailureMessage
import com.david.openassistant.ui.animations.scanningLine
import com.david.openassistant.ui.components.ErrorCard
import com.david.openassistant.ui.components.MarkdownContent
import java.text.DateFormat
import java.util.Date

@Composable
fun AgentWorkScreen(
    state: OpenAssistantUiState,
    onRefresh: () -> Unit,
    onSelectGoal: (String) -> Unit,
    onPauseGoal: (String) -> Unit,
    onResumeGoal: (String) -> Unit,
    onCancelGoal: (String) -> Unit,
    onDeleteGoal: (String) -> Unit,
    onRefineGoal: (String, String) -> Unit,
    onExportReport: (String) -> Unit,
) {
    val selectedGoal = state.selectedAgentGoal
    var showCapabilityPolicy by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Research Missions", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.agentGoals.size} total missions • ${state.agentGoals.count { it.status == AgentGoalStatus.RUNNING }} active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isPlanningAgentGoal) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("A new research plan is being generated and validated...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            state.agentError?.let { item { ErrorCard(it) } }

            if (state.agentGoals.isEmpty()) {
                item { EmptyWorkState() }
            } else {
                selectedGoal?.let { goal ->
                    item {
                        AgentGoalDetailCard(goal, onPauseGoal, onResumeGoal, onCancelGoal, onDeleteGoal, onRefineGoal, onExportReport)
                    }
                }

                item {
                    Text("Recent Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                
                items(state.agentGoals, key = { it.id }) { goal ->
                    AgentGoalListCard(
                        goal = goal,
                        selected = goal.id == selectedGoal?.id,
                        onClick = { onSelectGoal(goal.id) },
                    )
                }
            }

            item {
                CapabilityPolicyCard(
                    expanded = showCapabilityPolicy,
                    onToggle = { showCapabilityPolicy = !showCapabilityPolicy }
                )
            }
        }
    }
}

@Composable
private fun EmptyWorkState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.Assignment,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(16.dp))
            Text("No active missions", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Submit a research query to see the autonomous agent in action.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AgentGoalListCard(
    goal: AgentGoal,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Mission: ${goal.title}. Status: ${goal.status.name}. Progress: ${(goal.denseProgressScore * 100).toInt()}%"
            }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    goal.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(goal.status)
            }
            
            LinearProgressIndicator(
                progress = { goal.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (goal.status == AgentGoalStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            goal.missionNextMove()?.let { nextMove ->
                Text(
                    nextMove,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${(goal.denseProgressScore * 100).toInt()}% dense progress • ${goal.completedTaskCount}/${goal.tasks.size} milestones",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "$${"%.3f".format(goal.totalCostUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun StatusBadge(status: AgentGoalStatus) {
    val (color, text) = when (status) {
        AgentGoalStatus.PLANNING -> MaterialTheme.colorScheme.tertiary to "Planning"
        AgentGoalStatus.QUEUED -> MaterialTheme.colorScheme.secondary to "Queued"
        AgentGoalStatus.RUNNING -> MaterialTheme.colorScheme.primary to "Running"
        AgentGoalStatus.VERIFYING -> MaterialTheme.colorScheme.primary to "Verifying"
        AgentGoalStatus.WAITING_FOR_CREDENTIAL -> MaterialTheme.colorScheme.error to "Key Needed"
        AgentGoalStatus.WAITING_FOR_NETWORK -> MaterialTheme.colorScheme.secondary to "Network Wait"
        AgentGoalStatus.COMPLETED -> com.david.openassistant.ui.theme.MissionSuccess to "Completed"
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE -> com.david.openassistant.ui.theme.MissionSuccess to "Verified: Strong"
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS -> com.david.openassistant.ui.theme.MissionSuccess to "Verified: Qual"
        AgentGoalStatus.FAILED -> MaterialTheme.colorScheme.error to "Failed"
        AgentGoalStatus.PAUSED -> MaterialTheme.colorScheme.outline to "Paused"
        AgentGoalStatus.CANCELLED -> MaterialTheme.colorScheme.outline to "Cancelled"
        AgentGoalStatus.CANCELLING -> MaterialTheme.colorScheme.primary to "Cancelling"
        AgentGoalStatus.BLOCKED -> MaterialTheme.colorScheme.error to "Blocked"
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE -> MaterialTheme.colorScheme.error to "Blocked (Partial)"
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA -> MaterialTheme.colorScheme.error to "Insufficient Data"
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES -> MaterialTheme.colorScheme.error to "Conflicting Sources"
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION -> MaterialTheme.colorScheme.tertiary to "Clarification Needed"
        AgentGoalStatus.FINALIZING -> MaterialTheme.colorScheme.primary to "Finalizing"
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION -> MaterialTheme.colorScheme.error to "Corrupt / Incomplete"
    }
    
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun AgentGoalDetailCard(
    goal: AgentGoal,
    onPauseGoal: (String) -> Unit,
    onResumeGoal: (String) -> Unit,
    onCancelGoal: (String) -> Unit,
    onDeleteGoal: (String) -> Unit,
    onRefineGoal: (String, String) -> Unit,
    onExportReport: (String) -> Unit,
) {
    var expandedClaims by rememberSaveable { mutableStateOf(false) }
    var expandedEvidence by rememberSaveable { mutableStateOf(false) }
    var expandedConcepts by rememberSaveable { mutableStateOf(false) }
    var expandedMilestones by rememberSaveable { mutableStateOf(true) }
    var expandedLogs by rememberSaveable { mutableStateOf(false) }
    var refinementText by remember { mutableStateOf("") }

    var previewEvidenceId by remember { mutableStateOf<String?>(null) }
    val previewEvidence = previewEvidenceId?.let { id -> goal.evidence.firstOrNull { it.id == id } }

    if (previewEvidence != null) {
        EvidencePreviewDialog(evidence = previewEvidence, onDismiss = { previewEvidenceId = null })
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(goal.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(goal.status)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(goal.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { onExportReport(goal.id) }) {
                    Icon(Icons.Default.Download, contentDescription = "Export Report")
                }
            }

            MissionCommandPanel(goal)

            ResearchAllocationPanel(goal)

            ResearchPulseDashboard(goal)

            if (goal.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                val remaining = ((goal.nextRetryAt ?: 0) - System.currentTimeMillis()) / 1000
                if (remaining > 0) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Retrying in ${remaining}s: ${goal.networkWaitReason ?: "Network connection required."}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            LinearProgressIndicator(
                progress = { goal.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatItem("Integrity", percentLabel(goal.integrityScore), Modifier.weight(1f))
                StatItem("Health", percentLabel(goal.graphHealthScore), Modifier.weight(1f))
                StatItem("Cost", "$${"%.3f".format(goal.totalCostUsd)}", Modifier.weight(1f))
            }

            InfoSection("Original Request", goal.userRequest)
            InfoSection("Primary Objective", goal.objective)

            CollapsibleSection("Execution Milestones", expandedMilestones, { expandedMilestones = it }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    goal.tasks.sortedBy { it.order }.forEach { task ->
                        TaskItem(task)
                    }
                }
            }

            goal.result?.takeIf { it.isNotBlank() }?.let {
                InfoSection("Final Verified Result", it, isMarkdown = true)
            }

            if (goal.claims.isNotEmpty()) {
                CollapsibleSection("Claim & Evidence Graph (${goal.claims.size})", expandedClaims, { expandedClaims = it }) {
                    val evidenceById = remember(goal.evidence) { goal.evidence.associateBy { it.id } }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        goal.claims.take(if (expandedClaims) Int.MAX_VALUE else 3).forEach { claim ->
                            ClaimItem(
                                claim = claim,
                                evidenceById = evidenceById,
                                onEvidenceClick = { previewEvidenceId = it },
                            )
                        }
                    }
                }
            }

            if (goal.conceptCandidates.isNotEmpty()) {
                CollapsibleSection("Discovered Research Concepts (${goal.conceptCandidates.size})", expandedConcepts, { expandedConcepts = it }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        goal.conceptCandidates.forEach { concept ->
                            ConceptItem(concept)
                        }
                    }
                }
            }

            if (goal.evidence.isNotEmpty()) {
                CollapsibleSection("Evidence Trail (${goal.evidence.size})", expandedEvidence, { expandedEvidence = it }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        goal.evidence.reversed().take(if (expandedEvidence) Int.MAX_VALUE else 3).forEach { evidence ->
                            EvidenceItem(evidence)
                        }
                    }
                }
            }
            
            if (goal.events.isNotEmpty()) {
                CollapsibleSection("Activity Ledger", expandedLogs, { expandedLogs = it }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        goal.events.reversed().take(10).forEach { event ->
                            Text(
                                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.createdAt))} • ${event.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = refinementText,
                onValueChange = { refinementText = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("Refine mission (add search direction)", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("e.g. focus on 2024 results...", style = MaterialTheme.typography.labelSmall) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            onRefineGoal(goal.id, refinementText)
                            refinementText = ""
                        },
                        enabled = refinementText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Refine", modifier = Modifier.size(20.dp))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (refinementText.isNotBlank()) {
                        onRefineGoal(goal.id, refinementText)
                        refinementText = ""
                    }
                })
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val actions = com.david.openassistant.agent.MissionUiLogic.getAvailableActions(goal)
                
                if (actions.contains(com.david.openassistant.agent.MissionUiAction.PAUSE)) {
                    Button(onClick = { onPauseGoal(goal.id) }) {
                        Icon(Icons.Default.Pause, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pause")
                    }
                }
                if (actions.contains(com.david.openassistant.agent.MissionUiAction.RESUME)) {
                    Button(onClick = { onResumeGoal(goal.id) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Resume")
                    }
                }
                if (actions.contains(com.david.openassistant.agent.MissionUiAction.STOP)) {
                    OutlinedButton(onClick = { onCancelGoal(goal.id) }) {
                        Text("Stop Mission")
                    }
                }
                if (actions.contains(com.david.openassistant.agent.MissionUiAction.DELETE)) {
                    TextButton(onClick = { onDeleteGoal(goal.id) }) {
                        Text("Delete Mission Record", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionCommandPanel(goal: AgentGoal) {
    val activeTask = remember(goal) {
        goal.tasks.firstOrNull { it.status == AgentTaskStatus.RUNNING }
            ?: goal.nextRunnableTask(skipCooldowns = false)
            ?: goal.nextRunnableTask(skipCooldowns = true)
    }
    val latestEvent = goal.events.lastOrNull()?.message
    val needsUser = goal.needsUserAction()
    val nextMove = goal.missionNextMove()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Mission command. ${goal.missionPhaseLabel()}. ${nextMove.orEmpty()}"
            },
        color = if (needsUser) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
        },
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Mission Command",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (needsUser) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        goal.missionPhaseLabel(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(
                    color = if (needsUser) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        if (needsUser) "ACTION" else "AUTO",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (needsUser) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            nextMove?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            activeTask?.let { task ->
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = MaterialTheme.shapes.small,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (task.status == AgentTaskStatus.RUNNING) Icons.Default.AutoAwesome else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                task.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { task.effectiveProgressScore.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        task.lastRecoveryStrategy?.takeIf { it.isNotBlank() }?.let { strategy ->
                            Text(
                                "Recovery strategy: $strategy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MissionSignalChip("Autonomy", if (needsUser) "Needs input" else "Self-running")
                MissionSignalChip("Tasks", "${goal.completedTaskCount}/${goal.tasks.size}")
                MissionSignalChip("Evidence", goal.evidence.size.toString())
                MissionSignalChip("Claims", goal.claims.size.toString())
            }

            latestEvent?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Latest: ${normalizeAgentFailureMessage(it, it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MissionSignalChip(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoSection(label: String, content: String, isMarkdown: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.padding(12.dp)) {
                if (isMarkdown) {
                    MarkdownContent(content)
                } else {
                    SelectionContainer {
                        Text(content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!expanded) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun TaskItem(task: com.david.openassistant.agent.AgentTask) {
    val isRunning = task.status == AgentTaskStatus.RUNNING
    val statusColor = when (task.status) {
        AgentTaskStatus.COMPLETED -> com.david.openassistant.ui.theme.MissionSuccess
        AgentTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        AgentTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Milestone ${task.order + 1}: ${task.title}. Status: ${task.status.name}. Progress: ${(task.effectiveProgressScore * 100).toInt()}%"
            }
            .scanningLine(color = MaterialTheme.colorScheme.primary, active = isRunning),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (task.status) {
                        AgentTaskStatus.COMPLETED -> Icons.Default.CheckCircle
                        AgentTaskStatus.FAILED -> Icons.Default.Error
                        else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${task.order + 1}. ${task.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (task.status == AgentTaskStatus.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            
            LinearProgressIndicator(
                progress = { task.effectiveProgressScore.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = statusColor
            )
            
            val taskIsTerminal = task.status in setOf(
                AgentTaskStatus.COMPLETED,
                AgentTaskStatus.CANCELLED,
                AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
            )
            if (!taskIsTerminal) {
                task.lastError?.let {
                    Text(
                        normalizeAgentFailureMessage(it, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (task.failureClass == "network_resolution" && task.waitReason != null) {
                    Text(
                        "Waiting: ${task.waitReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaimItem(
    claim: com.david.openassistant.agent.AgentClaim,
    evidenceById: Map<String, com.david.openassistant.agent.AgentEvidence>,
    onEvidenceClick: (String) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var showConfidenceDetails by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(claim.type.wireName.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { showConfidenceDetails = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(percentLabel(claim.confidence), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(claim.text, style = MaterialTheme.typography.bodySmall)

            if (claim.supportingEvidenceIds.isNotEmpty() || claim.sourceUrls.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    claim.supportingEvidenceIds.distinct().forEach { evidenceId ->
                        val evidence = evidenceById[evidenceId]
                        SuggestionChip(
                            onClick = { onEvidenceClick(evidenceId) },
                            label = {
                                Text(
                                    evidence?.title?.take(44)?.ifBlank { "Evidence record" } ?: "Evidence record",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            icon = { Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                    }
                    claim.sourceUrls
                        .mapNotNull { url -> com.david.openassistant.agent.canonicalPresentationUrl(url)?.let { it to url } }
                        .distinctBy { it.first }
                        .forEach { (_, url) ->
                            SuggestionChip(
                                onClick = { runCatching { uriHandler.openUri(url) } },
                                label = {
                                    Column {
                                        Text(
                                            descriptiveUrlLabel(url),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            sourceRoleLabel(url),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                },
                                icon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                }
            }
        }
    }

    if (showConfidenceDetails) {
        AlertDialog(
            onDismissRequest = { showConfidenceDetails = false },
            title = { Text("Why this confidence score?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    confidenceExplanation(claim, evidenceById).forEach { line ->
                        Text("• $line", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfidenceDetails = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun EvidenceItem(evidence: com.david.openassistant.agent.AgentEvidence) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(evidence.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(evidence.summary, style = MaterialTheme.typography.labelSmall)

            if (evidence.sources.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    evidence.sources
                        .distinctBy { com.david.openassistant.agent.canonicalPresentationUrl(it.url) ?: it.url }
                        .forEach { source ->
                            SuggestionChip(
                                onClick = { runCatching { uriHandler.openUri(source.url) } },
                                label = {
                                    Column {
                                        Text(
                                            descriptiveSourceLabel(source),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            sourceRoleLabel(source.url),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                },
                                icon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                }
            } else if (evidence.kind == com.david.openassistant.agent.AgentEvidenceKind.RESEARCH_HIT) {
                Text(
                    text = evidence.content,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { runCatching { uriHandler.openUri(evidence.content) } },
                    textDecoration = TextDecoration.Underline
                )
            }
        }
    }
}

@Composable
private fun ResearchAllocationPanel(goal: AgentGoal) {
    val snapshot = remember(goal) { goal.allocationSnapshot() }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Research Allocation",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        snapshot.estimatedEffortLabel.uppercase(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Text(
                snapshot.profile.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.remainingSourceGap > 0) {
                    AllocationGapChip("Source Gap: ${snapshot.remainingSourceGap}", Icons.Default.Explore)
                }
                if (snapshot.remainingDomainGap > 0) {
                    AllocationGapChip("Domain Gap: ${snapshot.remainingDomainGap}", Icons.Default.Route)
                }
                if (snapshot.remainingPrimarySourceGap) {
                    AllocationGapChip("Primary Required", Icons.AutoMirrored.Filled.MenuBook)
                }
                if (snapshot.remainingContradictionGap) {
                    AllocationGapChip("Contradiction Needed", Icons.Default.Search)
                }
            }
            
            if (snapshot.profile.synthesisModelStrength == com.david.openassistant.agent.ModelStrength.STRONG) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Allocated high-intelligence model for synthesis.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun AllocationGapChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraSmall,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResearchPulseDashboard(goal: AgentGoal) {
    val activity = remember(goal) { goal.researchActivitySummary() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Research Pulse: ${activity.searches} searches, ${activity.fetches} fetches, ${activity.uniqueSources} unique sources, ${activity.rabbitHoleBranches} rabbit-hole branches"
            },
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Research Pulse", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PulseItem("Searches", activity.searches.toString(), Icons.Default.Search, Modifier.weight(1f))
                PulseItem("Fetches", activity.fetches.toString(), Icons.AutoMirrored.Filled.MenuBook, Modifier.weight(1f))
                PulseItem("Sources", activity.uniqueSources.toString(), Icons.Default.Explore, Modifier.weight(1f))
                PulseItem("Branches", activity.rabbitHoleBranches.toString(), Icons.Default.Route, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PulseItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConceptItem(concept: com.david.openassistant.agent.AgentConceptCandidate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(concept.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            Text(concept.definition, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EvidencePreviewDialog(evidence: com.david.openassistant.agent.AgentEvidence, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(evidence.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(evidence.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(evidence.content, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CapabilityPolicyCard(expanded: Boolean, onToggle: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Autonomous Policy", fontWeight = FontWeight.Bold)
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                }
            }
            if (expanded) {
                Text(
                    "The agent operates under strict safety protocols. It cannot execute arbitrary code on your device or access personal data without explicit permission. All heavy computation runs in isolated environments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun AgentGoal.needsUserAction(): Boolean =
    status in setOf(
        AgentGoalStatus.WAITING_FOR_CREDENTIAL,
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
        AgentGoalStatus.BLOCKED,
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION,
    )

private fun AgentGoal.missionPhaseLabel(): String =
    when (status) {
        AgentGoalStatus.PLANNING -> "Designing the research plan"
        AgentGoalStatus.QUEUED -> "Queued for autonomous execution"
        AgentGoalStatus.RUNNING -> "Research agent is working"
        AgentGoalStatus.VERIFYING -> "Verifying claims and evidence"
        AgentGoalStatus.WAITING_FOR_CREDENTIAL -> "Waiting for OpenRouter access"
        AgentGoalStatus.WAITING_FOR_NETWORK -> "Waiting for network recovery"
        AgentGoalStatus.COMPLETED,
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
        -> "Mission complete"
        AgentGoalStatus.FAILED -> "Mission failed"
        AgentGoalStatus.PAUSED -> "Mission paused"
        AgentGoalStatus.CANCELLED -> "Mission cancelled"
        AgentGoalStatus.CANCELLING -> "Stopping mission safely"
        AgentGoalStatus.BLOCKED,
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
        -> "Blocked with partial progress"
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES -> "Conflicting source evidence found"
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA -> "Not enough current evidence"
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION -> "Clarification needed"
        AgentGoalStatus.FINALIZING -> "Finalizing report"
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION -> "Mission record needs repair"
    }

private fun AgentGoal.missionNextMove(): String? {
    val runningTask = tasks.firstOrNull { it.status == AgentTaskStatus.RUNNING }
    val readyTask = nextRunnableTask(skipCooldowns = false)
    val cooldownTask = nextRunnableTask(skipCooldowns = true)
    val remainingRetrySeconds = nextRetryAt
        ?.let { ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) }

    return when (status) {
        AgentGoalStatus.PLANNING -> "The assistant is converting the request into evidence-backed milestones."
        AgentGoalStatus.QUEUED -> readyTask
            ?.let { "Next: ${it.title}" }
            ?: "Waiting for the scheduler to claim the next safe milestone."
        AgentGoalStatus.RUNNING -> runningTask
            ?.let { "Working now: ${it.title}" }
            ?: readyTask?.let { "Next runnable task: ${it.title}" }
            ?: cooldownTask?.let { "Cooling down before retrying: ${it.title}" }
            ?: "The worker is reconciling progress and choosing the next milestone."
        AgentGoalStatus.VERIFYING -> "The assistant is checking claims against evidence before producing the final answer."
        AgentGoalStatus.WAITING_FOR_CREDENTIAL -> "Add a valid OpenRouter key and the mission will resume automatically."
        AgentGoalStatus.WAITING_FOR_NETWORK -> buildString {
            append(networkWaitReason ?: "Network access is temporarily unavailable.")
            if (remainingRetrySeconds != null && remainingRetrySeconds > 0L) {
                append(" Retrying in ${remainingRetrySeconds}s.")
            }
        }
        AgentGoalStatus.PAUSED -> "Resume when you want the autonomous worker to continue."
        AgentGoalStatus.BLOCKED,
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
        -> clarificationDetails
            ?: blockedReason
            ?: error
            ?: "Review the evidence gaps or add direction so the assistant can continue."
        AgentGoalStatus.FINALIZING,
        AgentGoalStatus.CANCELLING,
        -> "The app is settling in-flight work and preparing a durable report."
        AgentGoalStatus.COMPLETED,
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
        -> "Open or export the report to inspect the answer, evidence, and unresolved caveats."
        AgentGoalStatus.FAILED -> error ?: "The mission stopped before completion. Resume can retry from the saved state."
        AgentGoalStatus.CANCELLED -> "This mission has been stopped. The saved record is still available for review."
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION -> "The mission record is incomplete and should be exported or repaired before continuing."
    }
}

fun percentLabel(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100).toInt()}%"

package com.david.openassistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.agent.*
import com.david.openassistant.ui.animations.scanningLine
import com.david.openassistant.ui.components.*
import com.david.openassistant.ui.theme.MissionSuccess
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

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Research Missions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.agentGoals.size} recorded • ${state.agentGoals.count { it.status == AgentGoalStatus.RUNNING }} active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Missions", modifier = Modifier.size(20.dp))
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
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(16.dp))
                            Text("Formulating a new investigation plan...", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            state.agentError?.let { item { ErrorCard(it) } }
    state.agentMessage?.let { 
        item { 
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } 
    }

            if (state.agentGoals.isEmpty() && !state.isPlanningAgentGoal) {
                item {
                    EmptyState(
                        title = "No active missions",
                        message = "Start a research inquiry from the Research tab to see the autonomous agent in action.",
                        icon = Icons.AutoMirrored.Filled.Assignment
                    )
                }
            } else {
                selectedGoal?.let { goal ->
                    item {
                        val isWorkRunning = state.activeWorkRunningStates[goal.id] ?: false
                        AgentGoalDetailCard(goal, isWorkRunning, onPauseGoal, onResumeGoal, onCancelGoal, onDeleteGoal, onRefineGoal, onExportReport)
                    }
                }

                if (state.agentGoals.size > 1 || (selectedGoal == null && state.agentGoals.isNotEmpty())) {
                    item {
                        Text(
                            "Mission Archive",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    
                    items(state.agentGoals, key = { it.id }) { goal ->
                        AgentGoalListCard(
                            goal = goal,
                            selected = goal.id == selectedGoal?.id,
                            onClick = { onSelectGoal(goal.id) },
                        )
                    }
                }
            }

            item {
                CapabilityPolicyCard()
            }
        }
    }
}

@Composable
private fun AgentGoalListCard(
    goal: AgentGoal,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Mission: ${goal.title}. Status: ${goal.status.name}. Progress: ${(goal.denseProgressScore * 100).toInt()}%"
            }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(
                    goal.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(goal.status)
            }
            
            LinearProgressIndicator(
                progress = { goal.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (goal.status == AgentGoalStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${(goal.denseProgressScore * 100).toInt()}% Complete • ${goal.completedTaskCount}/${goal.tasks.size} Steps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "$${"%.3f".format(goal.totalCostUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
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
        AgentGoalStatus.RUNNING,
        AgentGoalStatus.RESEARCHING,
        AgentGoalStatus.RETRIEVING,
        AgentGoalStatus.EXTRACTING,
        AgentGoalStatus.VERIFYING,
        AgentGoalStatus.VALIDATING,
        AgentGoalStatus.SYNTHESIZING,
        AgentGoalStatus.RECOVERING -> MaterialTheme.colorScheme.primary to status.name.lowercase().replaceFirstChar(Char::uppercase)
        AgentGoalStatus.WAITING_FOR_CREDENTIAL -> MaterialTheme.colorScheme.error to "Key Needed"
        AgentGoalStatus.WAITING_FOR_NETWORK -> MaterialTheme.colorScheme.secondary to "Network"
        AgentGoalStatus.WAITING_FOR_USER,
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION -> MaterialTheme.colorScheme.tertiary to "User Action"
        AgentGoalStatus.COMPLETED,
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS -> MissionSuccess to "Success"
        AgentGoalStatus.FAILED,
        AgentGoalStatus.REJECTED,
        AgentGoalStatus.BLOCKED,
        AgentGoalStatus.BLOCKED_NEEDS_ACTION,
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
        AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION -> MaterialTheme.colorScheme.error to "Blocked"
        AgentGoalStatus.PAUSED,
        AgentGoalStatus.CANCELLED -> MaterialTheme.colorScheme.outline to status.name.lowercase().replaceFirstChar(Char::uppercase)
        AgentGoalStatus.CANCELLING,
        AgentGoalStatus.FINALIZING -> MaterialTheme.colorScheme.primary to status.name.lowercase().replaceFirstChar(Char::uppercase)
    }
    
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = color
        )
    }
}

@Composable
private fun AgentGoalDetailCard(
    goal: AgentGoal,
    isWorkRunning: Boolean,
    onPauseGoal: (String) -> Unit,
    onResumeGoal: (String) -> Unit,
    onCancelGoal: (String) -> Unit,
    onDeleteGoal: (String) -> Unit,
    onRefineGoal: (String, String) -> Unit,
    onExportReport: (String) -> Unit,
) {
    var expandedOverview by rememberSaveable { mutableStateOf(true) }
    var expandedCouncil by rememberSaveable { mutableStateOf(false) }
    var expandedMilestones by rememberSaveable { mutableStateOf(false) }
    var expandedEvidence by rememberSaveable { mutableStateOf(false) }
    var expandedClaims by rememberSaveable { mutableStateOf(false) }
    var expandedMetrics by rememberSaveable { mutableStateOf(false) }
    var expandedLogs by rememberSaveable { mutableStateOf(false) }
    
    var refinementText by remember { mutableStateOf("") }
    var previewEvidenceId by remember { mutableStateOf<String?>(null) }
    val previewEvidence = previewEvidenceId?.let { id -> goal.evidence.firstOrNull { it.id == id } }

    if (previewEvidence != null) {
        EvidencePreviewDialog(evidence = previewEvidence, onDismiss = { previewEvidenceId = null })
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(goal.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(goal.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = { onExportReport(goal.id) }) {
                        Icon(Icons.Default.Download, contentDescription = "Export Report")
                    }
                    IconButton(onClick = { onDeleteGoal(goal.id) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Mission", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            MissionCommandPanel(goal)

            // Dynamic Progress
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Progress", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(percentLabel(goal.progressFraction.toDouble()), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(
                    progress = { goal.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // Collapsible Sections
            CollapsibleSection("Overview", expandedOverview, { expandedOverview = it }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoSection("Objective", goal.objective)
                    InfoSection("Request", goal.userRequest)
                    
                    goal.result?.takeIf { it.isNotBlank() }?.let {
                        InfoSection("Verified Result", it, isMarkdown = true)
                    }
                }
            }

            if (goal.attempts.any { it.councilRole != null }) {
                CollapsibleSection("Research Council", expandedCouncil, { expandedCouncil = it }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        goal.attempts
                            .filter { it.councilRole != null }
                            .distinctBy { it.councilRole }
                            .forEach { attempt ->
                                CouncilItem(attempt)
                            }
                    }
                }
            }

            CollapsibleSection("Execution Steps (${goal.tasks.size})", expandedMilestones, { expandedMilestones = it }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    goal.tasks.sortedBy { it.order }.forEach { task ->
                        TaskItem(task)
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

            if (goal.claims.isNotEmpty()) {
                CollapsibleSection("Factual Claims (${goal.claims.size})", expandedClaims, { expandedClaims = it }) {
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

            CollapsibleSection("Advanced Metrics & Policy", expandedMetrics, { expandedMetrics = it }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ResearchAllocationPanel(goal)
                    ResearchPulseDashboard(goal)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatItem("Integrity", percentLabel(goal.integrityScore), Modifier.weight(1f))
                        StatItem("Health", percentLabel(goal.graphHealthScore), Modifier.weight(1f))
                        StatItem("Total Cost", "$${"%.3f".format(goal.totalCostUsd)}", Modifier.weight(1f))
                    }
                }
            }

            if (goal.events.isNotEmpty()) {
                CollapsibleSection("Activity Ledger", expandedLogs, { expandedLogs = it }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        goal.events.reversed().take(15).forEach { event ->
                            Text(
                                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.createdAt))} • ${event.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Input refinement
            OutlinedTextField(
                value = refinementText,
                onValueChange = { refinementText = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("Add research direction", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("e.g. Focus on technical whitepapers...", style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            onRefineGoal(goal.id, refinementText)
                            refinementText = ""
                        },
                        enabled = refinementText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
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

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hasUnsettledExchange = goal.requestAttempts.any { it.exchangeOutcome == com.david.openassistant.agent.ExchangeOutcome.ACTIVE }
                val actions = MissionUiLogic.getAvailableActions(goal, isWorkRunning, hasUnsettledExchange)
                
                if (actions.contains(MissionUiAction.PAUSE)) {
                    Button(onClick = { onPauseGoal(goal.id) }) {
                        Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pause")
                    }
                }
                if (actions.contains(MissionUiAction.RESUME)) {
                    Button(onClick = { onResumeGoal(goal.id) }) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Resume")
                    }
                }
                if (actions.contains(MissionUiAction.STOP)) {
                    OutlinedButton(onClick = { onCancelGoal(goal.id) }) {
                        Text("Stop Mission")
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
    val needsUser = goal.needsUserAction()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (needsUser) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (needsUser) MaterialTheme.colorScheme.error.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    goal.missionPhaseLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (needsUser) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                if (needsUser) {
                    Icon(Icons.Default.PriorityHigh, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }

            goal.missionNextMove()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }

            activeTask?.let { task ->
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (task.status == AgentTaskStatus.RUNNING) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(task.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoSection(label: String, content: String, isMarkdown: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.padding(10.dp)) {
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
        Surface(
            onClick = { onToggle(!expanded) },
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Box(Modifier.padding(bottom = 8.dp)) {
                content()
            }
        }
        HorizontalDivider(modifier = Modifier.alpha(0.5f))
    }
}

@Composable
private fun CouncilItem(attempt: AgentAttempt) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (attempt.councilRole) {
                    CouncilRole.EXPLORER -> Icons.Default.Explore
                    CouncilRole.SKEPTIC -> Icons.Default.Security
                    CouncilRole.VERIFIER -> Icons.Default.CheckCircle
                    CouncilRole.SYNTHESIZER -> Icons.Default.AutoAwesome
                    else -> Icons.Default.Person
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    attempt.councilRole?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Agent",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    attempt.resolvedModel ?: attempt.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (attempt.status == AgentAttemptStatus.RUNNING) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            } else if (attempt.status == AgentAttemptStatus.SUCCEEDED) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = MissionSuccess)
            }
        }
    }
}

@Composable
private fun TaskItem(task: AgentTask) {
    val isRunning = task.status == AgentTaskStatus.RUNNING
    val statusColor = when (task.status) {
        AgentTaskStatus.COMPLETED -> MissionSuccess
        AgentTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        AgentTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scanningLine(color = MaterialTheme.colorScheme.primary, active = isRunning),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            
            LinearProgressIndicator(
                progress = { task.effectiveProgressScore.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = statusColor,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            
            if (task.status !in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) {
                task.lastError?.let {
                    Text(
                        normalizeAgentFailureMessage(it, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaimItem(
    claim: AgentClaim,
    evidenceById: Map<String, AgentEvidence>,
    onEvidenceClick: (String) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var showConfidenceDetails by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(claim.type.wireName.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
                Surface(
                    onClick = { showConfidenceDetails = true },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(percentLabel(claim.confidence), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
            Text(claim.text, style = MaterialTheme.typography.bodySmall)

            if (claim.supportingEvidenceIds.isNotEmpty() || claim.sourceUrls.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    claim.supportingEvidenceIds.distinct().forEach { evidenceId ->
                        val evidence = evidenceById[evidenceId]
                        AssistChip(
                            onClick = { onEvidenceClick(evidenceId) },
                            label = { Text(evidence?.title?.take(32) ?: "Evidence", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Visibility, null, Modifier.size(14.dp)) },
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                    claim.sourceUrls
                        .mapNotNull { url -> canonicalPresentationUrl(url)?.let { it to url } }
                        .distinctBy { it.first }
                        .forEach { (_, url) ->
                            AssistChip(
                                onClick = { runCatching { uriHandler.openUri(url) } },
                                label = { Text(descriptiveUrlLabel(url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.Link, null, Modifier.size(14.dp)) },
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                }
            }
        }
    }

    if (showConfidenceDetails) {
        AlertDialog(
            onDismissRequest = { showConfidenceDetails = false },
            title = { Text("Confidence Audit") },
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
private fun EvidenceItem(evidence: AgentEvidence) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(evidence.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(evidence.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (evidence.sources.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    evidence.sources
                        .distinctBy { canonicalPresentationUrl(it.url) ?: it.url }
                        .forEach { source ->
                            AssistChip(
                                onClick = { runCatching { uriHandler.openUri(source.url) } },
                                label = { Text(descriptiveSourceLabel(source), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.Link, null, Modifier.size(14.dp)) },
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                }
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
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Research Strategy", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                StatusBadge(AgentGoalStatus.PLANNING) // Reusing look for effort label if possible or custom
            }

            Text(snapshot.profile.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.remainingSourceGap > 0) AllocationGapChip("Sources: ${snapshot.remainingSourceGap}", Icons.Default.Explore)
                if (snapshot.remainingDomainGap > 0) AllocationGapChip("Domains: ${snapshot.remainingDomainGap}", Icons.Default.Route)
                if (snapshot.remainingPrimarySourceGap) AllocationGapChip("Primary Source", Icons.AutoMirrored.Filled.MenuBook)
            }
        }
    }
}

@Composable
private fun AllocationGapChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ResearchPulseDashboard(goal: AgentGoal) {
    val activity = remember(goal) { goal.researchActivitySummary() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Research Pulse", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PulseItem("Searches", activity.searches.toString(), Icons.Default.Search, Modifier.weight(1f))
                PulseItem("Fetches", activity.fetches.toString(), Icons.AutoMirrored.Filled.MenuBook, Modifier.weight(1f))
                PulseItem("Sources", activity.uniqueSources.toString(), Icons.Default.Explore, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PulseItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EvidencePreviewDialog(evidence: AgentEvidence, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(evidence.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(evidence.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(evidence.content, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun CapabilityPolicyCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Autonomous Laboratory Protocol", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Missions run on a durable background scheduler. The agent investigates across multiple sources, performs primary verification, and reconciles contradictions before presenting a final verified result.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun AgentGoal.needsUserAction(): Boolean =
    status in setOf(
        AgentGoalStatus.WAITING_FOR_CREDENTIAL,
        AgentGoalStatus.WAITING_FOR_USER,
        AgentGoalStatus.BLOCKED_NEEDS_ACTION,
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
        AgentGoalStatus.BLOCKED,
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION,
    )

private fun AgentGoal.missionPhaseLabel(): String =
    when (status) {
        AgentGoalStatus.PLANNING -> "Planning Investigation"
        AgentGoalStatus.QUEUED -> "Awaiting Scheduler"
        AgentGoalStatus.RUNNING,
        AgentGoalStatus.RESEARCHING,
        AgentGoalStatus.RETRIEVING,
        AgentGoalStatus.EXTRACTING,
        AgentGoalStatus.RECOVERING -> "Research in Progress"
        AgentGoalStatus.VERIFYING,
        AgentGoalStatus.VALIDATING -> "Verifying Findings"
        AgentGoalStatus.SYNTHESIZING -> "Synthesizing Report"
        AgentGoalStatus.WAITING_FOR_CREDENTIAL -> "Credential Required"
        AgentGoalStatus.WAITING_FOR_NETWORK -> "Network Interruption"
        AgentGoalStatus.WAITING_FOR_USER,
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION -> "Awaiting Input"
        AgentGoalStatus.COMPLETED,
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS -> "Mission Complete"
        AgentGoalStatus.FAILED,
        AgentGoalStatus.REJECTED,
        AgentGoalStatus.BLOCKED,
        AgentGoalStatus.BLOCKED_NEEDS_ACTION,
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
        AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION -> "Mission Halted"
        AgentGoalStatus.PAUSED -> "Mission Paused"
        AgentGoalStatus.CANCELLED -> "Mission Stopped"
        AgentGoalStatus.CANCELLING,
        AgentGoalStatus.FINALIZING -> "Settling Records"
    }

private fun AgentGoal.missionNextMove(): String? {
    val runningTask = tasks.firstOrNull { it.status == AgentTaskStatus.RUNNING }
    val readyTask = nextRunnableTask(skipCooldowns = false)
    val cooldownTask = nextRunnableTask(skipCooldowns = true)

    return when (status) {
        AgentGoalStatus.PLANNING -> "Designing request-specific milestones."
        AgentGoalStatus.QUEUED -> readyTask?.let { "Next: ${it.title}" } ?: "Waiting for next available milestone."
        AgentGoalStatus.RUNNING,
        AgentGoalStatus.RESEARCHING,
        AgentGoalStatus.RETRIEVING,
        AgentGoalStatus.EXTRACTING,
        AgentGoalStatus.SYNTHESIZING,
        AgentGoalStatus.RECOVERING -> runningTask?.let { "Working on: ${it.title}" }
            ?: readyTask?.let { "Starting: ${it.title}" }
            ?: cooldownTask?.let { "Cooling down: ${it.title}" }
            ?: "Reconciling progress..."
        AgentGoalStatus.VERIFYING,
        AgentGoalStatus.VALIDATING -> "Triangulating claims against retrieved evidence."
        AgentGoalStatus.WAITING_FOR_CREDENTIAL -> "Mission will resume once valid credentials are provided."
        AgentGoalStatus.WAITING_FOR_NETWORK -> networkWaitReason ?: "Network connection required."
        AgentGoalStatus.PAUSED -> "Resume to continue autonomous work."
        AgentGoalStatus.COMPLETED,
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS -> "Verified report is available."
        AgentGoalStatus.FAILED -> error ?: "An unexpected error halted the mission."
        else -> blockedReason ?: clarificationDetails ?: "Review status and provide direction to continue."
    }
}

fun percentLabel(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100).toInt()}%"

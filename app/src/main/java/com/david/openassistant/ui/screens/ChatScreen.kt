package com.david.openassistant.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.ui.animations.scanningLine
import com.david.openassistant.ui.components.*

private const val STREAMING_SCROLL_BUCKET_CHARS = 160

@Composable
fun ChatScreen(
    state: OpenAssistantUiState,
    onOpenModels: () -> Unit,
    onOpenConversations: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onAttachImage: (Uri) -> Unit,
    onRemovePendingImage: () -> Unit,
    onStartResearchBriefing: (String?) -> Unit,
    onUpdateResearchBrief: (com.david.openassistant.agent.ResearchDraft) -> Unit,
    onCancelResearchBrief: () -> Unit,
    onStartResearchMission: (com.david.openassistant.agent.ResearchDraft) -> Unit,
    onAddDirectionToMission: (String, String) -> Unit,
    onOpenMission: (String) -> Unit,
    onOpenReport: (String) -> Unit,
    onRequestBriefEdit: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastMessage = state.messages.lastOrNull()
    val lastMessageId = lastMessage?.id
    val streamingScrollBucket = if (lastMessage?.isStreaming == true) {
        lastMessage.content.length / STREAMING_SCROLL_BUCKET_CHARS
    } else {
        -1
    }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(onAttachImage)
    }
    val pendingImage = state.pendingImageAttachment
    val canSendPendingImage = pendingImage == null || (state.selectedModel?.supportsVision == true)
    val hasSendableContent = draft.isNotBlank() || pendingImage != null

    val activeMission = remember(state.activeConversationId, state.agentGoals) {
        state.agentGoals.firstOrNull { it.conversationId == state.activeConversationId && it.status != AgentGoalStatus.COMPLETED && it.status != AgentGoalStatus.CANCELLED }
    }
    val isResearchRunning = activeMission?.status == AgentGoalStatus.RUNNING

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(lastMessageId, streamingScrollBucket) {
        if (lastMessage?.isStreaming == true && state.messages.isNotEmpty()) {
            val lastIndex = state.messages.lastIndex
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: lastIndex
            if (lastVisibleIndex >= lastIndex - 1) {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Lightweight Screen Toolbar
        Surface(tonalElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onOpenConversations) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("History", style = MaterialTheme.typography.labelLarge)
                }
                
                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                AssistChip(
                    onClick = onOpenModels,
                    label = { Text(state.selectedModelProfile.displayName) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                    border = null,
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )

                if (activeMission != null) {
                    IconButton(onClick = { onOpenMission(activeMission.id) }) {
                        if (isResearchRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Science, contentDescription = "Active Mission", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        state.modelError?.let { ErrorCard(it, modifier = Modifier.padding(12.dp)) }
        state.chatError?.let { ErrorCard(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        state.attachmentError?.let { ErrorCard(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        
        state.lastToolExecution?.let { evidence ->
            ToolEvidenceCard(
                displayName = evidence.displayName,
                summary = evidence.summary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (state.messages.isEmpty()) {
            EmptyState(
                title = "OpenAssistant Research Lab",
                message = "Ask a complex question to start an autonomous investigation. The runtime will search multiple sources, follow leads, and verify its findings.",
                icon = Icons.Default.Science,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onOpenMission = onOpenMission,
                        onOpenReport = onOpenReport,
                    )
                }

                if (activeMission != null && isResearchRunning) {
                    item {
                        ActiveInvestigationBubble(activeMission)
                    }
                }
            }
        }

        // Refined Input Area
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingImage?.let { attachment ->
                    PendingImageCard(
                        attachment = attachment,
                        onRemove = onRemovePendingImage,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !state.isGenerating && !state.isPlanningAgentGoal && !state.isImportingImage,
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Attach image")
                    }
                    
                    if (state.isImportingImage) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }

                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when {
                                    pendingImage != null -> "Ask about image..."
                                    activeMission != null -> "Ask assistant..."
                                    else -> "Enter research goal..."
                                },
                            )
                        },
                        maxLines = 5,
                        enabled = !state.isGenerating && !state.isPlanningAgentGoal,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions {
                            if (hasSendableContent && canSendPendingImage && state.selectedModelId != null) {
                                onSendMessage(draft)
                                draft = ""
                            }
                        },
                    )

                    if (state.isGenerating) {
                        FilledIconButton(
                            onClick = onStopGeneration,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    } else if (state.isPlanningAgentGoal) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp).padding(8.dp), strokeWidth = 3.dp)
                    } else {
                        FilledIconButton(
                            onClick = {
                                onSendMessage(draft)
                                draft = ""
                            },
                            enabled = hasSendableContent && canSendPendingImage && state.selectedModelId != null && !state.isImportingImage,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }

                // Explicit Research Action
                if (!state.isGenerating && !state.isPlanningAgentGoal) {
                    if (activeMission == null) {
                        Button(
                            onClick = {
                                onStartResearchBriefing(draft)
                                draft = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isGeneratingBrief,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (state.isGeneratingBrief) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(12.dp))
                                Text("Analyzing Request...")
                            } else {
                                Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Launch Autonomous Investigation")
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onOpenMission(activeMission.id) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Assignment, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Mission Control")
                            }
                            Button(
                                onClick = { 
                                    onAddDirectionToMission(activeMission.id, draft)
                                    draft = ""
                                },
                                modifier = Modifier.weight(1f),
                                enabled = draft.isNotBlank(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Explore, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Add Direction")
                            }
                        }
                    }
                }
                
                if (pendingImage != null && !canSendPendingImage) {
                    Text(
                        "Vision model required for images",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }

    val researchDraft = state.researchDraft
    if (researchDraft != null && (state.isResearchBriefEditRequested || state.chatError != null)) {
        ResearchBriefSheet(
            draft = researchDraft,
            onUpdateDraft = onUpdateResearchBrief,
            onCancel = onCancelResearchBrief,
            onStartMission = onStartResearchMission,
            isStarting = state.isPlanningAgentGoal,
        )
    }
}

@Composable
private fun ActiveInvestigationBubble(mission: com.david.openassistant.agent.AgentGoal) {
    val currentTask = mission.nextRunnableTask ?: mission.tasks.lastOrNull { it.status == AgentTaskStatus.RUNNING }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .semantics(mergeDescendants = true) {
                contentDescription = "Investigation in progress. Current milestone: ${currentTask?.title ?: "Unknown"}"
            }
            .scanningLine(color = MaterialTheme.colorScheme.primary, active = true),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = "AUTONOMOUS RESEARCH",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            if (currentTask != null) {
                Text(
                    text = currentTask.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val hits = mission.evidence.filter { it.kind == com.david.openassistant.agent.AgentEvidenceKind.RESEARCH_HIT }.reversed().take(1)
            if (hits.isNotEmpty()) {
                hits.forEach { hit ->
                    Text(
                        text = "Hit: ${hit.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = TextDecoration.Underline
                    )
                }
            }
        }
    }
}

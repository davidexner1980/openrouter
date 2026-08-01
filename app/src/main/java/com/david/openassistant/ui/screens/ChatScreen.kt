package com.david.openassistant.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.ui.animations.scanningLine
import com.david.openassistant.ui.components.ErrorCard
import com.david.openassistant.ui.components.MessageBubble
import com.david.openassistant.ui.components.PendingImageCard
import com.david.openassistant.ui.components.SmallBadge
import com.david.openassistant.ui.components.ResearchBriefSheet
import com.david.openassistant.ui.components.ToolEvidenceCard

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
    onStartResearchBriefing: () -> Unit,
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
        // Simplified Header
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onOpenConversations) {
                    Icon(Icons.Default.History, contentDescription = "History")
                }
                
                AssistChip(
                    onClick = onOpenModels,
                    label = { Text(state.selectedModelProfile.displayName) },
                    modifier = Modifier.weight(1f)
                )

                if (activeMission != null) {
                    AssistChip(
                        onClick = { onOpenMission(activeMission.id) },
                        label = { Text(if (isResearchRunning) "Mission Running" else "Mission Active") },
                        leadingIcon = {
                            if (isResearchRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            } else {
                                Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = if (isResearchRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                if (state.isLoadingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        state.modelError?.let { ErrorCard(it, modifier = Modifier.padding(horizontal = 12.dp)) }
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
            EmptyChatState(modifier = Modifier.weight(1f))
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

        // Input Area
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !state.isGenerating &&
                            !state.isPlanningAgentGoal &&
                            !state.isImportingImage,
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
                                    pendingImage != null -> "Ask about this image..."
                                    activeMission != null -> "Ask the assistant..."
                                    else -> "Message OpenAssistant..."
                                },
                            )
                        },
                        minLines = 1,
                        maxLines = 6,
                        enabled = !state.isGenerating && !state.isPlanningAgentGoal,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions {
                            if (hasSendableContent && canSendPendingImage && state.selectedModelId != null) {
                                onSendMessage(draft)
                                draft = ""
                            }
                        },
                    )

                    if (state.isGenerating) {
                        IconButton(
                            onClick = onStopGeneration,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    } else if (state.isPlanningAgentGoal) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = {
                                onSendMessage(draft)
                                draft = ""
                            },
                            enabled = hasSendableContent && canSendPendingImage && state.selectedModelId != null && !state.isImportingImage,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }

                // Explicit Research Controls
                if (!state.isGenerating && !state.isPlanningAgentGoal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeMission != null) {
                            Button(
                                onClick = { onOpenMission(activeMission.id) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open Mission", style = MaterialTheme.typography.labelLarge)
                            }

                            Button(
                                onClick = { 
                                    onAddDirectionToMission(activeMission.id, draft)
                                    draft = ""
                                },
                                modifier = Modifier.weight(1f),
                                enabled = draft.isNotBlank()
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Add Direction", style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Button(
                                onClick = onStartResearchBriefing,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isGeneratingBrief,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                if (state.isGeneratingBrief) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Start Deep Research", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                
                if (pendingImage != null && !canSendPendingImage) {
                    Text(
                        "Vision model required for images",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp)
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
            .fillMaxWidth(0.92f)
            .semantics(mergeDescendants = true) {
                contentDescription = "Investigation in progress. Current milestone: ${currentTask?.title ?: "Unknown"}"
            }
            .scanningLine(color = MaterialTheme.colorScheme.primary, active = true),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 2.dp, bottomEnd = 18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "Investigation in progress...",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            if (currentTask != null) {
                Text(
                    text = "Current Milestone: ${currentTask.title}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            val hits = mission.evidence.filter { it.kind == com.david.openassistant.agent.AgentEvidenceKind.RESEARCH_HIT }.reversed().take(2)
            if (hits.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    hits.forEach { hit ->
                        Text(
                            text = "found: ${hit.title}",
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
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "OpenAssistant Research Lab. Ask a complex question to start an autonomous investigation."
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Science,
                contentDescription = "Research Lab Icon",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "OpenAssistant Research Lab",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask a complex question to start an autonomous investigation. The runtime will search multiple sources, follow leads, and verify its findings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

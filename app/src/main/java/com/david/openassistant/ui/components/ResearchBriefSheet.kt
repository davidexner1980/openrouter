package com.david.openassistant.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.david.openassistant.agent.ResearchDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchBriefSheet(
    draft: ResearchDraft,
    onUpdateDraft: (ResearchDraft) -> Unit,
    onCancel: () -> Unit,
    onStartMission: (ResearchDraft) -> Unit,
    isStarting: Boolean,
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Research Brief",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }

            BriefField(
                label = "Working Title",
                value = draft.title,
                onValueChange = { onUpdateDraft(draft.copy(title = it)) }
            )

            BriefField(
                label = "Central Research Question",
                value = draft.question,
                onValueChange = { onUpdateDraft(draft.copy(question = it)) }
            )

            BriefField(
                label = "Objective",
                value = draft.objective,
                onValueChange = { onUpdateDraft(draft.copy(objective = it)) }
            )

            BriefListField(
                label = "Confirmed Constraints",
                items = draft.confirmedConstraints,
                onUpdate = { onUpdateDraft(draft.copy(confirmedConstraints = it)) }
            )

            BriefListField(
                label = "Inferred Preferences",
                items = draft.inferredPreferences,
                onUpdate = { onUpdateDraft(draft.copy(inferredPreferences = it)) }
            )

            BriefListField(
                label = "Unresolved Questions",
                items = draft.unresolvedQuestions,
                onUpdate = { onUpdateDraft(draft.copy(unresolvedQuestions = it)) }
            )

            BriefListField(
                label = "Evidence Requirements",
                items = draft.evidenceRequirements,
                onUpdate = { onUpdateDraft(draft.copy(evidenceRequirements = it)) }
            )

            BriefListField(
                label = "Preferred Source Types",
                items = draft.preferredSourceTypes,
                onUpdate = { onUpdateDraft(draft.copy(preferredSourceTypes = it)) }
            )

            BriefField(
                label = "Freshness Requirement",
                value = draft.freshnessRequirement ?: "",
                onValueChange = { onUpdateDraft(draft.copy(freshnessRequirement = it.takeIf { it.isNotBlank() })) }
            )

            BriefListField(
                label = "Exclusions",
                items = draft.exclusions,
                onUpdate = { onUpdateDraft(draft.copy(exclusions = it)) }
            )

            BriefField(
                label = "Desired Deliverable",
                value = draft.desiredDeliverable,
                onValueChange = { onUpdateDraft(draft.copy(desiredDeliverable = it)) }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onStartMission(draft) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isStarting && draft.question.isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isStarting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Done, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Mission")
                }
            }
        }
    }
}

@Composable
private fun BriefField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun BriefListField(
    label: String,
    items: List<String>,
    onUpdate: (List<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        items.forEachIndexed { index, item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                OutlinedTextField(
                    value = item,
                    onValueChange = { newValue ->
                        val newList = items.toMutableList()
                        newList[index] = newValue
                        onUpdate(newList)
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                IconButton(
                    onClick = {
                        val newList = items.toMutableList()
                        newList.removeAt(index)
                        onUpdate(newList)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                }
            }
        }
        TextButton(
            onClick = { onUpdate(items + "") },
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add Item", style = MaterialTheme.typography.labelMedium)
        }
    }
}

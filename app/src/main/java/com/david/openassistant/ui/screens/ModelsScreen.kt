package com.david.openassistant.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.ModelProfile
import com.david.openassistant.ui.components.SmallBadge

private enum class ModelFilter {
    ALL,
    FREE,
    VISION,
    TOOLS,
}

@Composable
fun ModelsScreen(
    state: OpenAssistantUiState,
    onRefresh: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectModelProfile: (ModelProfile) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ModelFilter.ALL) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val visibleModels = remember(state.models, search, filter) {
        state.models.filter { model ->
            val matchesSearch = search.isBlank() ||
                model.name.contains(search, ignoreCase = true) ||
                model.id.contains(search, ignoreCase = true) ||
                model.provider.contains(search, ignoreCase = true)
            val matchesFilter = when (filter) {
                ModelFilter.ALL -> true
                ModelFilter.FREE -> model.isFree
                ModelFilter.VISION -> model.supportsVision
                ModelFilter.TOOLS -> model.supportsTools
            }
            matchesSearch && matchesFilter && model.id != "openrouter/auto" && model.id != "openrouter/auto-lite"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Research Profiles",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ModelProfile.entries.forEach { profile ->
                        val selected = state.selectedModelProfile == profile
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectModelProfile(profile) },
                            label = { Text(profile.displayName, style = MaterialTheme.typography.labelLarge) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                            } else null,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = state.selectedModelProfile.description,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Advanced Selection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    IconButton(onClick = { showAdvanced = !showAdvanced }) {
                        Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                }
            }

            if (showAdvanced || search.isNotBlank()) {
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Filter model catalog...") },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModelFilter.entries.forEach { item ->
                            FilterChip(
                                selected = filter == item,
                                onClick = { filter = item },
                                label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            )
                        }
                    }
                }

                if (state.isLoadingModels && state.models.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 3.dp)
                        }
                    }
                } else if (visibleModels.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No models match your search.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    items(visibleModels, key = { it.id }) { model ->
                        ModelCard(
                            model = model,
                            selected = model.id == state.selectedModelId,
                            selectable = model.supportsTextChat && model.id != "openrouter/bodybuilder",
                            onClick = { onSelectModel(model.id) },
                        )
                    }
                }
            } else {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { showAdvanced = true }
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Advanced Model Selection", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text("Browse the full catalog and select specific providers.", style = MaterialTheme.typography.labelSmall)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoadingModels
                ) {
                    if (state.isLoadingModels) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Syncing Catalog...")
                    } else {
                        Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh Catalog from OpenRouter")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: OpenRouterModel,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    
    Surface(
        onClick = if (selectable) onClick else ({}),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        model.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(model.provider)
                    if (model.contextLength > 0) append(" • ${formatContext(model.contextLength)} context")
                    append(" • ${model.priceSummary()}")
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            
            if (model.supportsVision || model.supportsTools || model.isFree || model.id == "openrouter/bodybuilder" || !selectable) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.id == "openrouter/bodybuilder") SmallBadge("Utility")
                    if (model.isFree) SmallBadge("Free")
                    if (model.supportsVision) SmallBadge("Vision")
                    if (model.supportsTools) SmallBadge("Tools")
                    if (!selectable) Text("Non-text", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(java.util.Locale.US, "%.0fK", tokens / 1_000.0)
    else -> tokens.toString()
}

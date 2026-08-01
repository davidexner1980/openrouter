package com.david.openassistant.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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

    val visibleModels = remember(state.models, search, filter) {
        val routers = setOf("openrouter/auto-beta", "openrouter/free", "openrouter/bodybuilder")
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
            // Prominently expose only the 3 routers when not searching
            val shouldShow = if (search.isBlank() && filter == ModelFilter.ALL) {
                 model.id in routers
            } else {
                 matchesSearch && matchesFilter && model.id != "openrouter/auto" && model.id != "openrouter/auto-lite"
            }
            shouldShow
        }.sortedByDescending { it.id in routers }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Research Profiles",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModelProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = state.selectedModelProfile == profile,
                            onClick = { onSelectModelProfile(profile) },
                            label = { Text(profile.displayName) },
                        )
                    }
                }
                Text(
                    text = state.selectedModelProfile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search model catalog...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            
            Spacer(Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.FilterList, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${visibleModels.size} models available",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh, enabled = !state.isLoadingModels) {
                    if (state.isLoadingModels) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Refresh Catalog")
                }
            }
        }

        if (state.isLoadingModels && state.models.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (visibleModels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching models found.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visibleModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        selected = model.id == state.selectedModelId,
                        selectable = model.supportsTextChat && model.id != "openrouter/bodybuilder",
                        onClick = { onSelectModel(model.id) },
                    )
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
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable, onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()
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
            if (model.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            
            if (model.supportsVision || model.supportsTools || model.isFree || model.id == "openrouter/bodybuilder" || !selectable) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.id == "openrouter/bodybuilder") SmallBadge("Utility")
                    if (model.isFree) SmallBadge("Free")
                    if (model.supportsVision) SmallBadge("Vision")
                    if (model.supportsTools) SmallBadge("Tools")
                    if (!selectable) Text("Non-text", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
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

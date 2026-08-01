package com.david.openassistant.ui.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.david.openassistant.ui.AppSection

@Composable
fun AppNavigationBar(
    selected: AppSection,
    onSelected: (AppSection) -> Unit,
) {
    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
        NavigationBarItem(
            selected = selected == AppSection.CHAT,
            onClick = { onSelected(AppSection.CHAT) },
            icon = { Icon(Icons.Default.Science, contentDescription = "Research") },
            label = { Text("Research") },
        )
        NavigationBarItem(
            selected = selected == AppSection.WORK,
            onClick = { onSelected(AppSection.WORK) },
            icon = { Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = "Missions") },
            label = { Text("Missions") },
        )
        NavigationBarItem(
            selected = selected == AppSection.CONVERSATIONS,
            onClick = { onSelected(AppSection.CONVERSATIONS) },
            icon = { Icon(Icons.Default.Archive, contentDescription = "Archive") },
            label = { Text("Archive") },
        )
        NavigationBarItem(
            selected = selected == AppSection.MODELS,
            onClick = { onSelected(AppSection.MODELS) },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Models") },
            label = { Text("Models") },
        )
        NavigationBarItem(
            selected = selected == AppSection.SETTINGS,
            onClick = { onSelected(AppSection.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
        )
    }
}

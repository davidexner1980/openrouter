package com.david.openassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.data.security.maskCredentialLabel
import com.david.openassistant.domain.tools.AdvancedToolCatalog
import com.david.openassistant.domain.tools.HostedSandboxToolCatalog
import com.david.openassistant.domain.tools.PublicWebToolCatalog
import com.david.openassistant.domain.tools.RuntimeDiagnosticToolCatalog
import com.david.openassistant.domain.tools.SafeToolCatalog
import com.david.openassistant.domain.tools.WorkspaceToolCatalog
import com.david.openassistant.ui.components.DiagnosticsCard
import com.david.openassistant.ui.components.ErrorCard
import com.david.openassistant.ui.components.SettingLine
import com.david.openassistant.ui.components.SettingsCard
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    state: OpenAssistantUiState,
    onRefreshModels: () -> Unit,
    onClearConversation: () -> Unit,
    onDeleteCredential: () -> Unit,
    onStartResearchMonitor: () -> Unit,
    onRefreshResearchMonitor: () -> Unit,
    onCreateResearchMonitorSnapshot: () -> Unit,
    onStopResearchMonitor: () -> Unit,
    onRetryPublicExport: () -> Unit,
    onOpenExportedReport: (android.content.Context) -> Unit,
    onShareExportedReport: (android.content.Context) -> Unit,
    onCreateOverseerRuntimePacket: () -> Unit,
    onRuntimePacketConsumed: () -> Unit,
    onSearxngBaseUrlChange: (String) -> Unit,
    onSaveResearchWebSettings: () -> Unit,
) {
    var confirmDeleteKey by rememberSaveable { mutableStateOf(false) }
    var confirmClearChat by rememberSaveable { mutableStateOf(false) }
    var showToolCatalog by rememberSaveable { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val packetResult = state.runtimePacketReady
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && packetResult != null) {
            context.contentResolver.openOutputStream(uri)?.use { 
                it.write(packetResult.zipData)
            }
            onRuntimePacketConsumed()
        }
    }

    LaunchedEffect(packetResult) {
        if (packetResult != null) {
            launcher.launch(packetResult.fileName)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            onRefreshResearchMonitor()
            delay(2.seconds)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.connectionError?.let { ErrorCard(it) }

        ResearchMonitorPanel(
            state = state,
            onStartResearchMonitor = onStartResearchMonitor,
            onCreateResearchMonitorSnapshot = onCreateResearchMonitorSnapshot,
            onStopResearchMonitor = onStopResearchMonitor,
            onRetryPublicExport = onRetryPublicExport,
            onOpenExportedReport = onOpenExportedReport,
            onShareExportedReport = onShareExportedReport,
            onCreateOverseerRuntimePacket = onCreateOverseerRuntimePacket,
        )

        SettingsCard(title = "OpenRouter Integration") {
            val keyInfo = state.keyInfo
            SettingLine("Connection", if (state.isConnecting) "Checking Status..." else "Active")
            SettingLine("API Key", maskCredentialLabel(keyInfo?.label))
            keyInfo?.let { SettingLine("Account Tier", if (it.isFreeTier) "Free" else "Paid/Professional") }
            keyInfo?.usage?.let { SettingLine("Cumulative Usage", "$${"%.4f".format(it)}") }
            keyInfo?.limitRemaining?.let { SettingLine("Credits Remaining", "$${"%.4f".format(it)}") }
            
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRefreshModels,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoadingModels,
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.isLoadingModels) "Updating Catalog..." else "Refresh Model Catalog")
            }
        }

        SettingsCard(title = "Network Strategy") {
            Text(
                "OpenAssistant uses a hybrid search strategy. Primary research is routed through SearXNG or DuckDuckGo. Direct fetches are performed via high-integrity HTTPS requests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.searxngBaseUrlInput,
                onValueChange = onSearxngBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Custom SearXNG Instance") },
                placeholder = { Text("https://search.example.org") },
                supportingText = {
                    Text("Optional. Queries go to this instance first for increased privacy.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            state.researchWebSettingsMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("saved")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSaveResearchWebSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply Network Settings")
            }
        }

        SettingsCard(title = "Autonomous Tool Registry") {
            val deterministicToolCount = SafeToolCatalog.definitions.size + AdvancedToolCatalog.definitions.size
            val staticToolCount = deterministicToolCount + WorkspaceToolCatalog.definitions.size + RuntimeDiagnosticToolCatalog.definitions.size + HostedSandboxToolCatalog.definitions.size + PublicWebToolCatalog.definitions.size
            
            SettingLine("Total Available Tools", (staticToolCount + state.activeToolRecipeCount).toString())
            SettingLine("Research Server Tools", "6")
            SettingLine("User-Created Recipes", state.activeToolRecipeCount.toString())
            
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showToolCatalog = !showToolCatalog },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(if (showToolCatalog) "Hide Details" else "View Detailed Catalog")
                Icon(if (showToolCatalog) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
            
            if (showToolCatalog) {
                ToolCategoryList("Core Research Primitives", SafeToolCatalog.definitions.map { it.displayName })
                ToolCategoryList("Advanced Analysis", AdvancedToolCatalog.definitions.map { it.displayName })
                ToolCategoryList("Secure Workspace", WorkspaceToolCatalog.definitions.map { it.displayName })
                ToolCategoryList("Sandbox Environment", HostedSandboxToolCatalog.definitions.map { it.displayName })
            }
        }

        DiagnosticsCard(state.diagnostics, state.diagnosticLogPath)

        SettingsCard(title = "Data Management") {
            SettingLine("Active Records", state.messages.count { it.content.isNotBlank() }.toString())
            SettingLine("Archived Investigations", state.conversations.size.toString())
            
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmClearChat = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                enabled = state.messages.isNotEmpty(),
            ) {
                Icon(Icons.Default.DeleteForever, null)
                Spacer(Modifier.width(8.dp))
                Text("Purge Current Conversation")
            }
            
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmDeleteKey = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Remove API Credentials")
            }
        }
    }

    // Dialogs
    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { confirmDeleteKey = false },
            title = { Text("Delete API Key?") },
            text = { Text("This will permanently remove your OpenRouter key from this device's secure storage.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteKey = false; onDeleteCredential() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteKey = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmClearChat) {
        AlertDialog(
            onDismissRequest = { confirmClearChat = false },
            title = { Text("Clear Conversation?") },
            text = { Text("All messages and evidence in this conversation will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = { confirmClearChat = false; onClearConversation() }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearChat = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ToolCategoryList(title: String, tools: List<String>) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        tools.forEach { tool ->
            Text("• $tool", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResearchMonitorPanel(
    state: OpenAssistantUiState,
    onStartResearchMonitor: () -> Unit,
    onCreateResearchMonitorSnapshot: () -> Unit,
    onStopResearchMonitor: () -> Unit,
    onRetryPublicExport: () -> Unit,
    onOpenExportedReport: (android.content.Context) -> Unit,
    onShareExportedReport: (android.content.Context) -> Unit,
    onCreateOverseerRuntimePacket: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsCard(title = "Deep Research Recorder") {
        val monitor = state.researchMonitorStatus
        SettingLine("Recorder Status", if (monitor.active) "ACTIVE" else "Idle")
        SettingLine("Events Captured", monitor.eventCount.toString())
        SettingLine("Trace Volume", formatByteCount(monitor.traceBytes))
        
        Spacer(Modifier.height(12.dp))
        Text(
            "The monitor records the internal reasoning chain, searches, and tool executions. This forensic data is used for verification and debugging.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.lastExportResult?.let { result ->
            Spacer(Modifier.height(16.dp))
            ExportResultView(
                result = result,
                onRetry = onRetryPublicExport,
                onOpen = { onOpenExportedReport(context) },
                onShare = { onShareExportedReport(context) }
            )
        }
        
        Spacer(Modifier.height(16.dp))
        if (monitor.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCreateResearchMonitorSnapshot,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isPreparingResearchMonitorReport && !state.isPreparingRuntimePacket,
                ) {
                    Text(if (state.isPreparingResearchMonitorReport) "..." else "Snapshot")
                }
                Button(
                    onClick = onStopResearchMonitor,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isPreparingResearchMonitorReport && !state.isPreparingRuntimePacket,
                ) {
                    Text("Stop & Report")
                }
            }
        } else {
            Button(
                onClick = onStartResearchMonitor,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isPreparingResearchMonitorReport && !state.isPreparingRuntimePacket,
            ) {
                Text("Start Recording Session")
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCreateOverseerRuntimePacket,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isPreparingResearchMonitorReport && !state.isPreparingRuntimePacket,
        ) {
            Text(if (state.isPreparingRuntimePacket) "Preparing Packet..." else "Create Overseer Runtime Packet")
        }
    }
}

@Composable
private fun ExportResultView(
    result: com.david.openassistant.data.diagnostics.ExportResult,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val status = result.status
    val isSuccess = status == com.david.openassistant.data.diagnostics.ExportStatus.EXPORTED
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val message = when (status) {
            com.david.openassistant.data.diagnostics.ExportStatus.EXPORTED -> {
                val kind = if (result.reportKind == com.david.openassistant.data.diagnostics.ReportKind.FINAL) "Final report" else "Snapshot"
                "$kind saved to Downloads/OpenAssistant/${result.displayName}"
            }
            com.david.openassistant.data.diagnostics.ExportStatus.PERMISSION_REQUIRED -> "Storage permission required to save to Downloads."
            else -> "The report was created safely inside OpenAssistant, but it could not be copied to Downloads."
        }

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = if (isSuccess) FontWeight.Normal else FontWeight.Bold
        )

        if (isSuccess) {
            result.displayName?.let { SettingLine("File", it) }
            SettingLine("Size", formatByteCount(result.bytesWritten))
            result.verifiedSha256?.let { SettingLine("SHA-256", it.take(8)) }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text("Open")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text("Share")
                }
            }
        } else {
            result.failureMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (result.retryable) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry Save to Downloads")
                }
            }
        }
    }
}

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.2f MiB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

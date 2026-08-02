package com.david.openassistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.david.openassistant.BuildConfig
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.data.security.maskCredentialLabel
import com.david.openassistant.domain.tools.*
import com.david.openassistant.ui.components.DiagnosticsCard
import com.david.openassistant.ui.components.ErrorCard
import com.david.openassistant.ui.components.SettingLine
import com.david.openassistant.ui.components.SettingsCard
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
    onToggleDetailedContentCapture: (Boolean) -> Unit = {},
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
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        state.connectionError?.let { ErrorCard(it) }

        // Section: Connection & Account
        SettingsCard(title = "OpenRouter Connection") {
            val keyInfo = state.keyInfo
            SettingLine("Service Status", if (state.isConnecting) "Verifying..." else "Active")
            SettingLine("Current Key", maskCredentialLabel(keyInfo?.label))
            keyInfo?.let {
                SettingLine("Tier", if (it.isFreeTier) "Free" else "Paid / Professional")
                SettingLine("Usage", "$${"%.4f".format(it.usage ?: 0.0)}")
                SettingLine("Remaining", "$${"%.4f".format(it.limitRemaining ?: 0.0)}")
            }
            
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRefreshModels,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoadingModels,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (state.isLoadingModels) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Refresh Model Catalog")
            }
        }

        // Section: Network & Privacy
        SettingsCard(title = "Research Network") {
            Text(
                "Investigations use a hybrid search strategy via SearXNG/DuckDuckGo. Content extraction is performed directly via HTTPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.searxngBaseUrlInput,
                onValueChange = onSearxngBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Private SearXNG Instance") },
                placeholder = { Text("https://search.example.org") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            state.researchWebSettingsMessage?.let { message ->
                Text(
                    message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.contains("saved")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSaveResearchWebSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save Network Configuration")
            }
        }

        // Section: Capabilities
        SettingsCard(title = "Autonomous Tool Registry") {
            val deterministicToolCount = SafeToolCatalog.definitions.size + AdvancedToolCatalog.definitions.size
            val staticToolCount = deterministicToolCount + WorkspaceToolCatalog.definitions.size + RuntimeDiagnosticToolCatalog.definitions.size + HostedSandboxToolCatalog.definitions.size + PublicWebToolCatalog.definitions.size
            
            SettingLine("Active Tools", (staticToolCount + state.activeToolRecipeCount).toString())
            SettingLine("Custom Recipes", state.activeToolRecipeCount.toString())
            
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showToolCatalog = !showToolCatalog },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(if (showToolCatalog) "Hide Technical Details" else "View Full Capabilities")
                Icon(if (showToolCatalog) Icons.Default.ExpandLess else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
            
            if (showToolCatalog) {
                ToolCategoryList("Research Primitives", SafeToolCatalog.definitions.map { it.displayName })
                ToolCategoryList("Advanced Analysis", AdvancedToolCatalog.definitions.map { it.displayName })
                ToolCategoryList("Data & Workspace", WorkspaceToolCatalog.definitions.map { it.displayName })
                ToolCategoryList("Runtime Diagnostics", RuntimeDiagnosticToolCatalog.definitions.map { it.displayName })
            }
        }

        // Section: Data & Privacy
        SettingsCard(title = "Data Management") {
            SettingLine("Archived Investigations", state.conversations.size.toString())
            
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { confirmClearChat = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    enabled = state.messages.isNotEmpty(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear Chat")
                }
                
                Button(
                    onClick = { confirmDeleteKey = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Revoke Key")
                }
            }
        }

        // Section: Diagnostics (Moved to bottom)
        ResearchMonitorPanel(
            state = state,
            onStartResearchMonitor = onStartResearchMonitor,
            onCreateResearchMonitorSnapshot = onCreateResearchMonitorSnapshot,
            onStopResearchMonitor = onStopResearchMonitor,
            onRetryPublicExport = onRetryPublicExport,
            onOpenExportedReport = onOpenExportedReport,
            onShareExportedReport = onShareExportedReport,
            onCreateOverseerRuntimePacket = onCreateOverseerRuntimePacket,
            onToggleDetailedContentCapture = onToggleDetailedContentCapture,
        )

        DiagnosticsCard(state.diagnostics, state.diagnosticLogPath)

        // Footer: About
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "OpenAssistant Research Engine",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Build Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.alpha(0.6f)
            )
        }
    }

    // Dialogs
    if (confirmDeleteKey) {
        AlertDialog(
            onDismissRequest = { confirmDeleteKey = false },
            title = { Text("Revoke API Credentials?") },
            text = { Text("This will permanently remove your OpenRouter key from the device's secure Keystore. All background missions will be paused.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteKey = false; onDeleteCredential() }) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
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
            title = { Text("Clear Active Conversation?") },
            text = { Text("All messages and evidence in the current thread will be deleted. Persistent missions are not affected.") },
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
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(4.dp))
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
    onToggleDetailedContentCapture: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsCard(title = "Forensic Recorder") {
        val monitor = state.researchMonitorStatus
        SettingLine("Status", if (monitor.active) "RECORDING" else "Inactive")
        SettingLine("Session ID", monitor.sessionId ?: "none")
        SettingLine("Events Captured", monitor.eventCount.toString())
        
        Spacer(Modifier.height(12.dp))
        Text(
            "Captures internal reasoning chains and tool logs for verification. Data is stored locally and can be exported as a technical report.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Detailed Content Capture", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Excerpts mission content (redacted) to Logcat and debug packets. Expires after 60 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (monitor.detailedContentCaptureEnabled) {
                    val remaining = ((monitor.detailedContentCaptureExpiry ?: 0L) - System.currentTimeMillis()) / 1000 / 60
                    Text(
                        "Active: $remaining min remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Switch(
                checked = monitor.detailedContentCaptureEnabled,
                onCheckedChange = onToggleDetailedContentCapture
            )
        }

        if (monitor.detailedContentCaptureEnabled) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    "WARNING: Logcat and exported packets will contain sensitive research excerpts.",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

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
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (state.isPreparingResearchMonitorReport) "..." else "Snapshot")
                }
                Button(
                    onClick = onStopResearchMonitor,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isPreparingResearchMonitorReport && !state.isPreparingRuntimePacket,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Stop & Report")
                }
            }
        } else {
            Button(
                onClick = onStartResearchMonitor,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isPreparingResearchMonitorReport && !state.isPreparingRuntimePacket,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.FiberManualRecord, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Start Recording Session")
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCreateOverseerRuntimePacket,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isPreparingResearchMonitorReport && !state.isPreparingRuntimePacket,
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.BugReport, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.isPreparingRuntimePacket) "Assembling Packet..." else "Create Debug Packet")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        
        Text("System Identity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        SettingLine("Application ID", state.researchMonitorStatus.applicationId ?: "com.david.openassistant")
        SettingLine("Build Version", "${state.researchMonitorStatus.versionName} (${state.researchMonitorStatus.versionCode})")
        SettingLine("Git SHA", BuildConfig.GIT_SHA.take(8))
        SettingLine("Logcat Tag", com.david.openassistant.data.diagnostics.RuntimeDiagnostics.LOGCAT_TAG)
        SettingLine("Boot SID", com.david.openassistant.data.diagnostics.DiagnosticEvent.BOOT_SESSION_ID)
        SettingLine("Process SID", com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID)
        if (monitor.detailedContentCaptureEnabled) {
            SettingLine("Capture SID", monitor.captureSessionId ?: "none")
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
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isSuccess) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSuccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val message = when (status) {
                com.david.openassistant.data.diagnostics.ExportStatus.EXPORTED -> {
                    val kind = if (result.reportKind == com.david.openassistant.data.diagnostics.ReportKind.FINAL) "Final report" else "Snapshot"
                    "$kind ready in Downloads/OpenAssistant"
                }
                com.david.openassistant.data.diagnostics.ExportStatus.PERMISSION_REQUIRED -> "Storage permission required."
                else -> "Export failed to reach Downloads folder."
            }

            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )

            if (isSuccess) {
                result.displayName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Text("Open")
                    }
                    TextButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Text("Share")
                    }
                }
            } else {
                result.failureMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                if (result.retryable) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}


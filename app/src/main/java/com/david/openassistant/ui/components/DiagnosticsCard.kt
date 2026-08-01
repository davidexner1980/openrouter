package com.david.openassistant.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.david.openassistant.RequestDiagnostics
import java.text.DateFormat
import java.util.Date

@Composable
fun DiagnosticsCard(
    diagnostics: RequestDiagnostics,
    diagnosticLogPath: String,
) {
    SettingsCard(title = "Diagnostics and runtime evidence") {
        SettingLine("Operation", diagnostics.operation)
        SettingLine("Status", diagnostics.status.name.lowercase().replaceFirstChar(Char::uppercase))
        diagnostics.httpStatus?.let { SettingLine("HTTP", it.toString()) }
        diagnostics.modelId?.let { SettingLine("Requested model", it) }
        diagnostics.resolvedModel?.let { SettingLine("Resolved model", it) }
        diagnostics.responseId?.let { SettingLine("Response ID", it) }
        diagnostics.totalTokens?.let { SettingLine("Tokens", it.toString()) }
        diagnostics.cost?.let { SettingLine("Reported cost", "$${"%.6f".format(it)}") }
        diagnostics.durationMillis?.let { SettingLine("Duration", "$it ms") }
        diagnostics.finishedAt?.let {
            SettingLine("Finished", DateFormat.getDateTimeInstance().format(Date(it)))
        }
        diagnostics.note?.let { SettingLine("Note", it) }
        diagnostics.message?.let {
            Spacer(Modifier.height(8.dp))
            ErrorCard(it)
        }
        if (diagnosticLogPath.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Structured runtime log", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    diagnosticLogPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Filter Logcat with the tag OpenAssistant. This compact runtime ledger excludes prompts and response bodies; the opt-in passive research monitor preserves those details in its redacted report.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

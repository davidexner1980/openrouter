package com.david.openassistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.ui.components.ErrorCard

@Composable
fun SetupScreen(
    state: OpenAssistantUiState,
    onKeyInputChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 48.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.Science,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "OpenAssistant",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Professional Research Node",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "Connect an OpenRouter API key to enable autonomous multi-model investigations and primary source verification.",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
        }

        OutlinedTextField(
            value = state.keyInput,
            onValueChange = onKeyInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenRouter API Key") },
            placeholder = { Text("sk-or-...") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onToggleKeyVisibility) {
                    Text(if (state.keyVisible) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConnect() }),
            enabled = !state.isConnecting,
            supportingText = {
                Text("Your key is validated then encrypted using hardware-backed storage.")
            },
        )

        state.connectionError?.let { error ->
            ErrorCard(error)
        }

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = state.keyInput.isNotBlank() && !state.isConnecting,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(16.dp))
                Text("Validating Connection...")
            } else {
                Text("Initialize Assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        SecuritySummaryCard()
    }
}

@Composable
private fun SecuritySummaryCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Security Protocol", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Credentials are encrypted with AES-GCM and stored in the Android Keystore. Cloud backup is disabled to prevent leakage.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

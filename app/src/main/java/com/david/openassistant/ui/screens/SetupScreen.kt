package com.david.openassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.ui.animations.neuralGlow
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
            .neuralGlow(MaterialTheme.colorScheme.primary)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .imePadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "OpenAssistant",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your persistent autonomous research partner.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Connect one OpenRouter key to run durable multi-provider investigations with source-backed verification.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.keyInput,
            onValueChange = onKeyInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenRouter API key") },
            placeholder = { Text("sk-or-...") },
            singleLine = true,
            visualTransformation = if (state.keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = onToggleKeyVisibility) {
                    Text(if (state.keyVisible) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConnect() }),
            enabled = !state.isConnecting,
            supportingText = {
                Text("The key is tested before it is encrypted with Android Keystore.")
            },
        )

        state.connectionError?.let { error ->
            Spacer(Modifier.height(16.dp))
            ErrorCard(error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = state.keyInput.isNotBlank() && !state.isConnecting,
        ) {
            if (state.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("Connecting to OpenRouter...")
            } else {
                Text("Test and save key", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(32.dp))
        SecurityCard()
    }
}

@Composable
private fun SecurityCard() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Credential protection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "• The API key is never placed in source code or normal logs.\n" +
                    "• The stored value is encrypted with AES-GCM using a non-exportable Android Keystore key.\n" +
                    "• Android cloud backup is disabled for this app.\n" +
                    "• A compromised or rooted phone can still expose credentials while they are in use.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
        }
    }
}

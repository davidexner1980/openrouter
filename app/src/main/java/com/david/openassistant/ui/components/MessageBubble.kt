package com.david.openassistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole

@Composable
fun MessageBubble(
    message: ChatMessage,
    onOpenMission: ((String) -> Unit)? = null,
    onOpenReport: ((String) -> Unit)? = null,
) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 2.dp,
                bottomEnd = if (isUser) 2.dp else 18.dp,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = if (isUser) "You" else "Assistant",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(6.dp))
                message.attachments.forEach { attachment ->
                    MessageImageAttachment(attachment)
                    Spacer(Modifier.height(8.dp))
                }
                SelectionContainer {
                    if (isUser || message.isStreaming) {
                        Text(
                            text = message.content.ifBlank { "Thinking…" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        MarkdownContent(
                            content = message.content.ifBlank { "Thinking…" },
                            onOpenMission = onOpenMission,
                            onOpenReport = onOpenReport,
                        )
                    }
                }
                if (message.isStreaming) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Streaming…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun MessageImageAttachment(attachment: ChatAttachment) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AttachmentThumbnail(
            attachment = attachment,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )
        Text(
            text = "${attachment.displayName} • ${attachment.width} × ${attachment.height}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

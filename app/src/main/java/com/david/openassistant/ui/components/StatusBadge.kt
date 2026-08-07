package com.david.openassistant.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.ui.theme.MissionSuccess

@Composable
fun StatusBadge(status: AgentGoalStatus) {
    val (color, text) = when (status) {
        AgentGoalStatus.PLANNING -> MaterialTheme.colorScheme.tertiary to "Planning"
        AgentGoalStatus.QUEUED -> MaterialTheme.colorScheme.secondary to "Queued"
        AgentGoalStatus.RUNNING,
        AgentGoalStatus.RESEARCHING,
        AgentGoalStatus.RETRIEVING,
        AgentGoalStatus.EXTRACTING,
        AgentGoalStatus.VERIFYING,
        AgentGoalStatus.VALIDATING,
        AgentGoalStatus.SYNTHESIZING,
        AgentGoalStatus.RECOVERING -> MaterialTheme.colorScheme.primary to status.name.lowercase().replaceFirstChar(Char::uppercase)
        AgentGoalStatus.WAITING_FOR_CREDENTIAL -> MaterialTheme.colorScheme.error to "Key Needed"
        AgentGoalStatus.WAITING_FOR_NETWORK -> MaterialTheme.colorScheme.secondary to "Network"
        AgentGoalStatus.WAITING_FOR_USER,
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION -> MaterialTheme.colorScheme.tertiary to "User Action"
        AgentGoalStatus.COMPLETED,
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS -> MissionSuccess to "Success"
        AgentGoalStatus.FAILED,
        AgentGoalStatus.REJECTED,
        AgentGoalStatus.BLOCKED,
        AgentGoalStatus.BLOCKED_NEEDS_ACTION,
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
        AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION -> MaterialTheme.colorScheme.error to "Blocked"
        AgentGoalStatus.PAUSED,
        AgentGoalStatus.CANCELLED -> MaterialTheme.colorScheme.outline to status.name.lowercase().replaceFirstChar(Char::uppercase)
        AgentGoalStatus.CANCELLING,
        AgentGoalStatus.FINALIZING -> MaterialTheme.colorScheme.primary to status.name.lowercase().replaceFirstChar(Char::uppercase)
    }
    
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = color
        )
    }
}

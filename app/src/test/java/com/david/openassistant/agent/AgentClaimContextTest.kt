package com.david.openassistant.agent

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentClaimContextTest {

    @Test
    fun testBuildStructuredClaimsPrompt() {
        val client = AgentOpenRouterClient(
            client = OkHttpClient()
        )
        
        val claims = listOf(
            AgentClaim(
                id = "claim-1",
                taskId = "task-1",
                text = "The CEO is John Doe.",
                type = AgentClaimType.FACT,
                confidence = 0.9,
                support = AgentClaimSupport.CONTRADICTED
            )
        )
        
        val prompt = client.buildStructuredClaimsPrompt(claims)
        
        assertTrue("Prompt should contain claim text", prompt.contains("The CEO is John Doe."))
        assertTrue("Prompt should contain 'contradicted'", prompt.contains("contradicted"))
        assertTrue("Prompt should contain 'claim_id=claim-1'", prompt.contains("claim_id=claim-1"))
    }
}

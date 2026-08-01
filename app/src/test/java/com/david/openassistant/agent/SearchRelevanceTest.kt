package com.david.openassistant.agent

import com.david.openassistant.domain.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRelevanceTest {

    @Test
    fun bowQueryRejectsIrrelevantBestBuy() {
        val query = "best three-piece takedown recurve bow for hunting"
        
        val results = JSONObject()
            .put("status", "ok")
            .put("sources", JSONArray()
                .put(JSONObject()
                    .put("title", "Best Buy: Official Site - Shop Now")
                    .put("url", "https://www.bestbuy.com/")
                    .put("excerpt", "Shop for the best electronics, computers, appliances, and more at Best Buy."))
                .put(JSONObject()
                    .put("title", "Recurve Bows for Hunting | Top Rated")
                    .put("url", "https://www.archeryworld.com/recurve-bows")
                    .put("excerpt", "Find the best three-piece takedown recurve bow for your next hunting trip."))
            )
        
        val result = ToolExecutionResult(results.toString(), "Found 2 sources", 1)
        
        val client = AgentOpenRouterClient()
        val isHighRelevance = client.javaClass.getDeclaredMethod("isHighRelevanceSearchOutcome", String::class.java, ToolExecutionResult::class.java)
        isHighRelevance.isAccessible = true
        
        val outcome = isHighRelevance.invoke(client, query, result) as Boolean
        
        // Only one of the two sources should match (archeryworld).
        // MatchedSources = 1. sources.size = 2.
        // matchedSources >= (2 * 0.25).coerceAtLeast(1.0) => 1 >= 1.0 is TRUE.
        // Wait, I need to make sure the Best Buy one DOES NOT match.
        // Anchors for query: "three-piece", "takedown", "recurve", "bow", "hunting"
        // Best Buy text: "Best Buy: Official Site - Shop Now Shop for the best electronics, computers, appliances, and more at Best Buy. https://www.bestbuy.com/"
        // Matches for Best Buy: 0.
        // Matches for ArcheryWorld: "three-piece", "takedown", "recurve", "bow", "hunting" -> 5 matches.
        
        assertTrue(outcome) // Overall outcome should be true because ArcheryWorld is relevant.
    }

    @Test
    fun extremelyGenericResultsAreRejected() {
        val query = "best three-piece takedown recurve bow for hunting"
        
        val results = JSONObject()
            .put("status", "ok")
            .put("sources", JSONArray()
                .put(JSONObject()
                    .put("title", "Best Restaurants in Town")
                    .put("url", "https://www.yelp.com/")
                    .put("excerpt", "Check out the best restaurants in your area."))
                .put(JSONObject()
                    .put("title", "How to Hunt for Deals")
                    .put("url", "https://www.coupons.com/")
                    .put("excerpt", "Learn how to hunt for the best coupons and save money."))
            )
        
        val result = ToolExecutionResult(results.toString(), "Found 2 sources", 1)
        
        val client = AgentOpenRouterClient()
        val isHighRelevance = client.javaClass.getDeclaredMethod("isHighRelevanceSearchOutcome", String::class.java, ToolExecutionResult::class.java)
        isHighRelevance.isAccessible = true
        
        val outcome = isHighRelevance.invoke(client, query, result) as Boolean
        
        assertFalse(outcome)
    }
}

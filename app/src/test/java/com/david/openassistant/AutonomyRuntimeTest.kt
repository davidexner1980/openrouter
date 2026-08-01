package com.david.openassistant

import com.david.openassistant.agent.AgentAcceptanceCheck
import com.david.openassistant.agent.AgentAcceptanceCheckStatus
import com.david.openassistant.agent.AgentAcceptanceCriterion
import com.david.openassistant.agent.AgentApiSummary
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentClaim
import com.david.openassistant.agent.AgentClaimSupport
import com.david.openassistant.agent.AgentClaimType
import com.david.openassistant.agent.AgentEvidence
import com.david.openassistant.agent.AgentEvidenceKind
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentPlanDraft
import com.david.openassistant.agent.AgentPlanEnhancer
import com.david.openassistant.agent.AgentResearchDepth
import com.david.openassistant.agent.AgentResearchPolicy
import com.david.openassistant.agent.AgentSourceCitation
import com.david.openassistant.agent.AgentStepResult
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.AgentTaskDraft
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.agent.AutomationRoute
import com.david.openassistant.agent.AutomationRouter
import com.david.openassistant.agent.AutonomyPolicy
import com.david.openassistant.agent.adaptiveResearchStrategyAnchorsRequest
import com.david.openassistant.agent.capabilityScopedToolDefinitions
import com.david.openassistant.agent.parseAdaptiveResearchStrategy
import com.david.openassistant.agent.EvidenceContextSelector
import com.david.openassistant.agent.ResearchQualityGate
import com.david.openassistant.agent.ResearchPassRole
import com.david.openassistant.agent.researchPassRole
import com.david.openassistant.agent.researchQualityFindingCodes
import com.david.openassistant.agent.requestSpecificMaterialAnchorsRequest
import com.david.openassistant.domain.tools.AdvancedToolCatalog
import com.david.openassistant.domain.tools.HostedSandboxToolCatalog
import com.david.openassistant.domain.tools.RuntimeDiagnosticToolCatalog
import com.david.openassistant.domain.tools.PublicWebToolCatalog
import com.david.openassistant.domain.tools.SafeToolCatalog
import com.david.openassistant.domain.tools.ToolRecipe
import com.david.openassistant.domain.tools.ToolRecipeEngine
import com.david.openassistant.domain.tools.ToolRecipeOperation
import com.david.openassistant.domain.tools.ToolRecipeParameter
import com.david.openassistant.domain.tools.ToolRecipeStep
import com.david.openassistant.domain.tools.ToolRecipeTest
import com.david.openassistant.domain.tools.ToolRecipeValidator
import com.david.openassistant.domain.tools.WorkspaceToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyRuntimeTest {
    @Test
    fun autonomousDefaultsRequireNoModeSwitchAndEnforceDeepResearch() {
        val policy = AutonomyPolicy.DEFAULT

        assertTrue(policy.autopilotEnabled)
        assertTrue(policy.deepResearchByDefault)
        assertTrue(policy.autoExecuteLocalTools)
        assertTrue(policy.toolFoundryEnabled)
        assertEquals(4, policy.minimumResearchPasses)
        assertEquals(8, policy.minimumResearchSources)
        assertEquals(4, policy.minimumResearchDomains)
        assertEquals(3, policy.minimumSourcesPerResearchPass)
        assertEquals(3, policy.minimumSearchQueriesPerResearchPass)
        assertEquals(3, policy.targetFullSourceReadsPerResearchPass)
        assertEquals(4, policy.independentResearchAgents)
    }

    @Test
    fun casualConversationStaysImmediate() {
        val decision = AutomationRouter.decide("Hello", hasImage = false, modelSupportsTools = true)

        assertEquals(AutomationRoute.DIRECT_CHAT, decision.route)
        assertFalse(decision.researchPolicy.requiresResearch)
    }

    @Test
    fun deterministicLocalWorkUsesAutomaticToolsWithoutResearch() {
        val decision = AutomationRouter.decide(
            "Calculate 42 * 19 and format the result as JSON",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.TOOL_ASSISTED_CHAT, decision.route)
        assertEquals(AgentResearchDepth.NONE, decision.researchPolicy.depth)
    }

    @Test
    fun suppliedTextTransformationDoesNotWasteADeepResearchMission() {
        val decision = AutomationRouter.decide(
            "Rewrite the following paragraph so it sounds professional",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.DIRECT_CHAT, decision.route)
        assertEquals(AgentResearchDepth.NONE, decision.researchPolicy.depth)
    }

    @Test
    fun creativeWritingStaysImmediateUnlessItDependsOnExternalFacts() {
        val decision = AutomationRouter.decide(
            "Write a short fictional story about a lighthouse keeper",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.DIRECT_CHAT, decision.route)
        assertFalse(decision.researchPolicy.requiresResearch)
    }

    @Test
    fun substantiveWorkDefaultsToDeepAutonomousResearch() {
        val decision = AutomationRouter.decide(
            "Analyze the available approaches and recommend the strongest implementation plan",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.AUTONOMOUS_GOAL, decision.route)
        assertEquals(AgentResearchDepth.DEEP, decision.researchPolicy.depth)
    }

    @Test
    fun unfamiliarRecommendationUsesDeepResearchByDefault() {
        val decision = AutomationRouter.decide(
            "Recommend the most defensible archival format for a century-long municipal record set.",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.AUTONOMOUS_GOAL, decision.route)
        assertEquals(AgentResearchDepth.DEEP, decision.researchPolicy.depth)
    }

    @Test
    fun dentonElevationQuestionCannotFallThroughToQuickChat() {
        val decision = AutomationRouter.decide(
            "What's the highest elevation in Denton, Texas landmass?",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.AUTONOMOUS_GOAL, decision.route)
        assertEquals(AgentResearchDepth.DEEP, decision.researchPolicy.depth)
    }

    @Test
    fun unfamiliarFactQuestionStillDefaultsToDurableDeepResearch() {
        val decision = AutomationRouter.decide(
            "Why did this obscure measurement change between the two surveys?",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.AUTONOMOUS_GOAL, decision.route)
        assertEquals(AgentResearchDepth.DEEP, decision.researchPolicy.depth)
    }

    @Test
    fun adaptiveResearchStrategyPreservesRequestSpecificReasoningAndRejectsRepeatedQueries() {
        val strategyJson = """
            {
              "interpretation":"The question asks for the maximum natural bare-earth elevation inside the current City of Denton boundary, not a county-wide value or structure height.",
              "decision_target":"Identify the strongest defensible maximum ground elevation and the datum and resolution behind it.",
              "scope_ambiguities":["Current municipal polygon versus Denton County and extraterritorial jurisdiction"],
              "unknowns":[
                "Which polygon is the legally current incorporated boundary?",
                "Which bare-earth elevation surface and vertical datum cover that polygon?",
                "Do independent topographic records agree with the raster maximum?"
              ],
              "evidence_targets":[
                "City GIS boundary metadata and effective date",
                "USGS 3DEP bare-earth DEM metadata, resolution, and vertical datum",
                "Independent contour or benchmark evidence near candidate high cells"
              ],
              "falsifiers":[
                "The candidate cell lies outside the incorporated boundary",
                "The reported height represents a building, canopy, or incompatible datum"
              ],
              "follow_up_rule":"Extract dataset identifiers, datum names, tile names, benchmark IDs, and candidate coordinates from each source and use them to drive the next query.",
              "queries":[
                {
                  "query":"City of Denton Texas current municipal boundary GIS polygon effective date",
                  "purpose":"Establish the legally relevant spatial footprint before measuring a maximum.",
                  "expected_evidence":"Official city GIS layer metadata or municipal boundary download.",
                  "depends_on_discovery":null
                },
                {
                  "query":"USGS 3DEP bare earth DEM Denton Texas vertical datum resolution tile",
                  "purpose":"Identify a ground-only elevation surface and understand its measurement limits.",
                  "expected_evidence":"USGS dataset metadata naming resolution, acquisition, and vertical datum.",
                  "depends_on_discovery":"Use the city polygon's geographic extent to identify the covering tile."
                },
                {
                  "query":"Denton Texas topographic quadrangle spot elevations benchmarks inside city limits",
                  "purpose":"Cross-check the raster maximum against independent mapped or surveyed elevations.",
                  "expected_evidence":"USGS quadrangle, NGS benchmark, or official contour evidence near candidate cells.",
                  "depends_on_discovery":"Search candidate coordinates and named features extracted from the DEM analysis."
                }
              ]
            }
        """.trimIndent()

        val strategy = parseAdaptiveResearchStrategy(strategyJson, minimumQueries = 3)

        assertEquals(3, strategy.queries.size)
        assertTrue(strategy.interpretation.contains("bare-earth"))
        assertTrue(strategy.queries.any { it.query.contains("3DEP") })
        assertTrue(strategy.queries.any { it.dependsOnDiscovery?.contains("candidate coordinates") == true })
        assertTrue(
            adaptiveResearchStrategyAnchorsRequest(
                strategy,
                "What's the highest natural ground elevation inside the current Denton, Texas city boundary?",
            ),
        )
        assertFalse(
            "An unrelated canned investigation passed semantic anchoring",
            adaptiveResearchStrategyAnchorsRequest(
                strategy,
                "Why did the sodium-ion battery cycle-life result change between two published experiments?",
            ),
        )

        val namedEntityBranches = strategy.copy(
            interpretation = "Compare current hunting recurve bows and determine which purchase is most defensible.",
            decisionTarget = "Select a hunting recurve bow from verified candidate evidence.",
            queries = listOf(
                strategy.queries[0].copy(
                    query = "Bear Archery Grizzly measured arrow speed durability",
                    purpose = "Test the Grizzly's hunting performance with disclosed field measurements.",
                    expectedEvidence = "Instrumented measurements and long-term owner evidence for the named candidate.",
                ),
                strategy.queries[1].copy(
                    query = "Hoyt Satori limb construction independent test",
                    purpose = "Verify the Satori's hunting suitability and material durability.",
                    expectedEvidence = "Manufacturer specifications reconciled with an independent test.",
                ),
                strategy.queries[2].copy(
                    query = "Samick Sage current stock warranty",
                    purpose = "Confirm whether the named candidate can actually be bought and supported.",
                    expectedEvidence = "Current recurve availability, price, and warranty evidence.",
                ),
            ),
        )
        assertTrue(
            "Valid named-entity rabbit-hole branches were rejected for not repeating the full request wording",
            adaptiveResearchStrategyAnchorsRequest(
                namedEntityBranches,
                "What is the best recurve bow for hunting you can buy?",
            ),
        )

        val repeated = strategyJson.replace(
            "USGS 3DEP bare earth DEM Denton Texas vertical datum resolution tile",
            "City of Denton Texas current municipal boundary GIS polygon effective date",
        ).replace(
            "Denton Texas topographic quadrangle spot elevations benchmarks inside city limits",
            "City of Denton Texas current municipal boundary GIS polygon effective date",
        )
        assertTrue(runCatching { parseAdaptiveResearchStrategy(repeated, minimumQueries = 3) }.isFailure)
    }

    @Test
    fun americaAndUnitedStatesAreEquivalentRequestAnchors() {
        assertTrue(
            requestSpecificMaterialAnchorsRequest(
                request = "Tell me about the current president for America",
                material = "Verify the incumbent President of the United States and document the administration's record.",
            ),
        )
    }

    @Test
    fun explicitOfflineDirectionDisablesResearchButKeepsAutonomousExecution() {
        val decision = AutomationRouter.decide(
            "Analyze this architecture offline only and create an implementation plan",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.AUTONOMOUS_GOAL, decision.route)
        assertEquals(AgentResearchDepth.NONE, decision.researchPolicy.depth)
    }

    @Test
    fun deepPlanPreservesFourRequestSpecificEvidencePassesAndSynthesis() {
        val enhanced = AgentPlanEnhancer.enhance(
            draft = requestSpecificResearchDraft(researchPasses = 4),
            policy = AgentResearchPolicy(AgentResearchDepth.DEEP),
        )

        assertEquals(4, enhanced.tasks.count { it.capability == AgentCapability.DEEP_RESEARCH })
        assertEquals(AgentCapability.SYNTHESIZE, enhanced.tasks.last().capability)
        assertTrue(enhanced.tasks.any { it.id.contains("primary") })
        assertTrue(enhanced.tasks.any { it.id.contains("contradiction") })
        assertTrue(enhanced.tasks.any { it.id.contains("gap") })
        assertTrue(enhanced.tasks.any { it.instructions.contains("municipal boundary polygon") })
        assertTrue(enhanced.tasks.last().title.contains("Denton", ignoreCase = true))
        enhanced.tasks.drop(1).forEach { task ->
            assertTrue("Task ${task.id} should depend on an earlier task", task.dependsOn.isNotEmpty())
        }
    }

    @Test
    fun missingSemanticResearchPlanIsRejectedInsteadOfReceivingCannedTasks() {
        val incomplete = requestSpecificResearchDraft(researchPasses = 4).copy(
            tasks = requestSpecificResearchDraft(researchPasses = 4).tasks.filterNot {
                it.capability == AgentCapability.DEEP_RESEARCH
            },
        )

        val result = runCatching {
            AgentPlanEnhancer.enhance(
                draft = incomplete,
                policy = AgentResearchPolicy(AgentResearchDepth.DEEP),
            )
        }

        assertTrue("A generic research fallback was inserted", result.isFailure)
    }

    @Test
    fun standardResearchUsesDiscoveryPrimaryAndContradictionPassesInOrder() {
        val enhanced = AgentPlanEnhancer.enhance(
            draft = requestSpecificResearchDraft(researchPasses = 3),
            policy = AgentResearchPolicy(AgentResearchDepth.STANDARD),
        )

        val passes = enhanced.tasks.filter { it.capability == AgentCapability.DEEP_RESEARCH }
        assertEquals(3, passes.size)
        assertEquals(
            listOf(ResearchPassRole.DISCOVERY, ResearchPassRole.PRIMARY, ResearchPassRole.CONTRADICTION),
            passes.map(::researchPassRole),
        )
        assertTrue(passes[0].title.startsWith("Discovery:"))
        assertTrue(passes[1].title.startsWith("Primary verification:"))
        assertTrue(passes[2].title.startsWith("Adversarial review:"))
        assertFalse(enhanced.tasks.last().acceptanceCriteria.any { it.description.contains("gap-closure") })
    }

    @Test
    fun researchEvidencePrecedesPlannerSuggestedComputation() {
        val enhanced = AgentPlanEnhancer.enhance(
            draft = requestSpecificResearchDraft(
                researchPasses = 4,
                extraTasks = listOf(
                    AgentTaskDraft(
                        id = "compute_measurement",
                        title = "Compute Denton bare-earth raster maximum",
                        instructions = "Intersect the sourced municipal polygon with compatible bare-earth measurements and compare values only after their vertical datums are reconciled.",
                        capability = AgentCapability.TOOL_USE,
                        dependsOn = emptyList(),
                        weight = 1.0,
                        acceptanceCriteria = listOf(
                            com.david.openassistant.agent.AgentAcceptanceCriterion(
                                "denton_computation",
                                "The computation uses the verified Denton boundary and datum-compatible ground data.",
                                1.0,
                            ),
                        ),
                    ),
                ),
            ),
            policy = AgentResearchPolicy(AgentResearchDepth.DEEP),
        )

        val computationIndex = enhanced.tasks.indexOfFirst { it.id == "compute_measurement" }
        val researchIndices = enhanced.tasks.indices.filter {
            enhanced.tasks[it].capability == AgentCapability.DEEP_RESEARCH
        }
        assertTrue(researchIndices.isNotEmpty())
        assertTrue(computationIndex > researchIndices.max())
        assertEquals("research_gap_closure", enhanced.tasks[computationIndex].dependsOn.single())
        assertEquals(AgentCapability.SYNTHESIZE, enhanced.tasks.last().capability)
        assertEquals("compute_measurement", enhanced.tasks.last().dependsOn.single())
    }

    @Test
    fun relevanceSelectorRetainsOlderMaterialEvidence() {
        val relevant = evidence(
            id = "relevant",
            content = "Android WorkManager persistent autonomous execution and checkpoint recovery.",
            createdAt = 10L,
        )
        val irrelevant = evidence(
            id = "latest",
            content = "A completely unrelated cooking note.",
            createdAt = 20L,
        )
        val task = task(
            id = "implementation",
            title = "Design Android WorkManager recovery",
            instructions = "Use persistent checkpoints and recover interrupted autonomous work.",
        )
        val goal = goal(tasks = listOf(task), evidence = listOf(relevant, irrelevant))

        val selected = EvidenceContextSelector.select(goal, task, maxItems = 1, maxCharacters = 10_000).evidence

        assertEquals("relevant", selected.single().id)
    }

    @Test
    fun relevanceSelectorPrioritizesDirectDependencyEvidenceForRecovery() {
        val recoveryEvidence = evidence(
            id = "recovery-evidence",
            content = "Newly acquired source records from an alternate research angle.",
            createdAt = 10L,
        )
        val broadButLexicallyRelevant = evidence(
            id = "broad-evidence",
            content = "Final correction publication verification requirement citation source evidence.".repeat(20),
            createdAt = 20L,
        )
        val recovery = task(
            id = "recovery",
            title = "Focused recovery",
            instructions = "Acquire the missing evidence.",
            capability = AgentCapability.DEEP_RESEARCH,
        ).copy(
            status = AgentTaskStatus.COMPLETED,
            outputEvidenceId = recoveryEvidence.id,
        )
        val correction = task(
            id = "correction",
            title = "Final correction publication",
            instructions = "Resolve every verification requirement from citation source evidence.",
            capability = AgentCapability.CORRECT,
        ).copy(dependsOn = listOf(recovery.id))
        val goal = goal(
            tasks = listOf(recovery, correction),
            evidence = listOf(recoveryEvidence, broadButLexicallyRelevant),
        )

        val selected = EvidenceContextSelector.select(
            goal = goal,
            task = correction,
            maxItems = 1,
            maxCharacters = 10_000,
        ).evidence

        assertEquals("recovery-evidence", selected.single().id)
    }

    @Test
    fun deepResearchStepRejectsShallowSingleSourceOutput() {
        val task = task(
            id = "research_primary",
            title = "Primary source verification",
            instructions = "Verify with official primary sources.",
            capability = AgentCapability.DEEP_RESEARCH,
        )
        val result = AgentStepResult(
            content = "A short unsupported answer.",
            summary = AgentApiSummary(),
            sources = listOf(AgentSourceCitation("One source", "https://example.com/a")),
            claims = listOf(factClaim(task.id, "https://example.com/a")),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result)

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("at least 3") })
    }

    @Test
    fun synthesisStepRejectsAnEmptyProviderEscapeResponse() {
        val task = task(
            id = "synthesis",
            title = "Synthesize the verified result",
            instructions = "Reconcile the preserved evidence.",
            capability = AgentCapability.SYNTHESIZE,
        )
        val result = AgentStepResult(
            content = "No synthesis was produced.",
            summary = AgentApiSummary(),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result)

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("too little publication-ready analysis") })
        assertTrue(decision.reasons.any { it.contains("at least 3") })
        assertTrue(decision.reasons.any { it.contains("no grounded factual claim") })
    }

    @Test
    fun synthesisStepAcceptsSubstantiveGroundedClaims() {
        val task = task(
            id = "synthesis",
            title = "Synthesize the verified result",
            instructions = "Reconcile the preserved evidence.",
            capability = AgentCapability.SYNTHESIZE,
        )
        val evidenceId = "research-primary-evidence"
        val result = AgentStepResult(
            content = (
                "The final result reconciles the preserved primary-source findings, the contradiction review, " +
                    "and the gap audit. It explains the supported conclusion, distinguishes the remaining " +
                    "uncertainty, and connects each material statement to the evidence that established it. "
                ).repeat(4),
            summary = AgentApiSummary(),
            claims = listOf(
                AgentClaim(
                    id = "supported-fact",
                    taskId = task.id,
                    text = "The preserved primary evidence supports the central factual finding.",
                    type = AgentClaimType.FACT,
                    confidence = 0.9,
                    support = AgentClaimSupport.SUPPORTED,
                    supportingEvidenceIds = listOf(evidenceId),
                    sourceUrls = listOf("https://primary.example/finding"),
                ),
                AgentClaim(
                    id = "supported-inference",
                    taskId = task.id,
                    text = "The reconciled evidence supports a bounded conclusion rather than an absolute one.",
                    type = AgentClaimType.INFERENCE,
                    confidence = 0.8,
                    support = AgentClaimSupport.SUPPORTED,
                    supportingEvidenceIds = listOf(evidenceId),
                ),
                AgentClaim(
                    id = "supported-fact-2",
                    taskId = task.id,
                    text = "Another supporting detail found in the evidence.",
                    type = AgentClaimType.FACT,
                    confidence = 0.85,
                    support = AgentClaimSupport.SUPPORTED,
                    supportingEvidenceIds = listOf(evidenceId),
                    sourceUrls = listOf("https://secondary.example/detail"),
                ),
            ),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result)

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    @Test
    fun publicationStepRequiresEveryCriterionToPass() {
        val criterion = AgentAcceptanceCriterion(
            id = "recommendation",
            description = "The recommendation is complete and precisely sourced.",
        )
        val task = task(
            id = "synthesis",
            title = "Synthesize the verified result",
            instructions = "Reconcile the preserved evidence.",
            capability = AgentCapability.SYNTHESIZE,
        ).copy(acceptanceCriteria = listOf(criterion))
        val result = AgentStepResult(
            content = "A complete evidence-based publication with supported findings and bounded uncertainty. ".repeat(12),
            summary = AgentApiSummary(),
            acceptanceChecks = listOf(
                AgentAcceptanceCheck(
                    criterionId = criterion.id,
                    status = AgentAcceptanceCheckStatus.PARTIAL,
                    score = 0.9,
                    explanation = "One required part remains unresolved.",
                ),
            ),
            claims = factClaims(
                task.id,
                "https://one.example/finding",
                "https://two.example/finding",
            ),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result)

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("must pass every acceptance criterion") })
    }

    @Test
    fun correctionStepRejectsAMetaDescriptionInsteadOfAReplacementAnswer() {
        val task = task(
            id = "correction_1",
            title = "Correct verification failures",
            instructions = "Return a complete corrected publication.",
            capability = AgentCapability.CORRECT,
        )
        val result = AgentStepResult(
            content = "Corrected report addressing every finding with verified specifications and complete citations.",
            summary = AgentApiSummary(),
            claims = factClaims(
                task.id,
                "https://one.example/corrected",
                "https://two.example/corrected",
            ),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result)

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("too little publication-ready analysis") })
    }

    @Test
    fun landscapePassIgnoresSharedCounterevidenceProtocolWhenChoosingItsGate() {
        val task = task(
            id = "research_discovery",
            title = "Research pass 1: map the evidence landscape",
            instructions = "Map sources, then preserve limitations and counterevidence for later adversarial review.",
            capability = AgentCapability.DEEP_RESEARCH,
        )
        val content = "The landscape maps terminology, stakeholders, candidate sources, measurements, methods, provenance, and open research questions. ".repeat(10)
        val result = AgentStepResult(
            content = content,
            summary = AgentApiSummary(webSearchRequests = 3),
            sources = listOf(
                AgentSourceCitation("Source one", "https://one.example/evidence"),
                AgentSourceCitation("Source two", "https://two.example/evidence"),
                AgentSourceCitation("Source three", "https://three.example/evidence"),
            ),
            claims = factClaims(task.id, "https://one.example/evidence", "https://two.example/evidence", "https://three.example/evidence"),
            toolExecutions = fullSourceReadExecutions(),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result)

        assertEquals(ResearchPassRole.DISCOVERY, researchPassRole(task))
        assertTrue(decision.reasons.joinToString(), decision.passed)
        assertFalse(decision.reasons.any { it.contains("contradiction pass") })
    }

    @Test
    fun contradictionPassRecognizesConcreteUncertaintyLanguage() {
        val task = task(
            id = "research_contradictions",
            title = "Research pass 3: adversarial contradiction check",
            instructions = "Search for counterevidence.",
            capability = AgentCapability.DEEP_RESEARCH,
        )
        val result = AgentStepResult(
            content = (
                "The claimed coordinates remain unverified and were not disclosed. " +
                    "A regional result was a false-positive, and one methodology source should not be used as evidence for this location. "
                ).repeat(7),
            summary = AgentApiSummary(webSearchRequests = 3),
            sources = listOf(
                AgentSourceCitation("Challenge one", "https://one.example/challenge"),
                AgentSourceCitation("Challenge two", "https://two.example/challenge"),
                AgentSourceCitation("Challenge three", "https://three.example/challenge"),
            ),
            claims = factClaims(task.id, "https://one.example/challenge", "https://two.example/challenge", "https://three.example/challenge"),
            toolExecutions = fullSourceReadExecutions(),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result)

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    @Test
    fun deepResearchGoalRequiresPassCoverageAndSourceDiversity() {
        val tasks = listOf(
            task("research_discovery", "Evidence discovery landscape", "Map the evidence landscape.", AgentCapability.DEEP_RESEARCH),
            task("research_primary", "Primary source verification", "Use official first-party original sources.", AgentCapability.DEEP_RESEARCH),
            task("research_contradictions", "Adversarial contradiction check", "Search for counterevidence and disconfirmation.", AgentCapability.DEEP_RESEARCH),
            task("research_gap_closure", "Gap closure and freshness audit", "Close gaps and check stale claims.", AgentCapability.DEEP_RESEARCH),
        ).map { it.copy(status = AgentTaskStatus.COMPLETED, progressScore = 1.0) }
        val evidence = tasks.mapIndexed { index, currentTask ->
            AgentEvidence(
                id = "evidence-$index",
                taskId = currentTask.id,
                kind = AgentEvidenceKind.DEEP_RESEARCH,
                title = currentTask.title,
                summary = "Primary source and counterevidence audit.",
                content = buildString {
                    appendLine("Official documentation and original research were checked. Counterevidence, limitations, caveats, and alternative explanations were documented.")
                    appendLine()
                    appendLine("Autonomous local tool activity:")
                    appendLine("- [PASS] public_web_search: Found official documentation.")
                    appendLine("- [PASS] public_web_fetch: Read primary research paper from https://domain${index + 1}.example/primary.")
                },
                sources = listOf(
                    AgentSourceCitation("Source ${index * 2}", "https://domain${index + 1}.example/source-a"),
                    AgentSourceCitation("Source ${index * 2 + 1}", "https://domain${index + 1}.example/source-b"),
                ),
            )
        }
        val completeGoal = goal(tasks, evidence).copy(
            attempts = tasks.mapIndexed { index, currentTask ->
                com.david.openassistant.agent.AgentAttempt(
                    id = "attempt-$index",
                    taskId = currentTask.id,
                    status = com.david.openassistant.agent.AgentAttemptStatus.SUCCEEDED,
                    startedAt = 1L,
                    finishedAt = 2L,
                    modelId = "model",
                    webSearchRequests = 3,
                )
            },
        )

        val decision = ResearchQualityGate.evaluateGoal(completeGoal)

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }


    @Test
    fun aggregateDiversityAcceptsLaterEvidenceThatRepairsEarlierSourceOverlap() {
        val tasks = listOf(
            task("research_discovery", "Evidence discovery landscape", "Map the evidence landscape.", AgentCapability.DEEP_RESEARCH),
            task("research_primary", "Primary source verification", "Use official first-party original sources.", AgentCapability.DEEP_RESEARCH),
            task("research_contradictions", "Adversarial contradiction check", "Search for counterevidence and disconfirmation.", AgentCapability.DEEP_RESEARCH),
            task("research_gap_closure", "Gap closure and freshness audit", "Close gaps and check stale claims.", AgentCapability.DEEP_RESEARCH),
        ).mapIndexed { index, currentTask ->
            currentTask.copy(order = index, status = AgentTaskStatus.COMPLETED, progressScore = 1.0)
        }
        val sourceSets = listOf(
            listOf("https://alpha.example/a", "https://beta.example/b"),
            listOf("https://alpha.example/a", "https://beta.example/b"),
            listOf(
                "https://gamma.example/c",
                "https://delta.example/d",
                "https://epsilon.example/e",
                "https://zeta.example/f",
            ),
            listOf("https://eta.example/g", "https://theta.example/h"),
        )
        val toolActivity = buildString {
            appendLine()
            appendLine("Autonomous local tool activity:")
            appendLine("- [PASS] public_web_search: Discovered sources.")
            appendLine("- [PASS] public_web_fetch: Read primary research from https://official.example/primary.")
        }
        val evidence = tasks.mapIndexed { index, currentTask ->
            AgentEvidence(
                id = "repeat-evidence-$index",
                taskId = currentTask.id,
                kind = AgentEvidenceKind.DEEP_RESEARCH,
                title = currentTask.title,
                summary = "Primary source and counterevidence audit.",
                content = "Official documentation and original research were checked. Counterevidence, limitations, caveats, and alternative explanations were documented. $toolActivity",
                sources = sourceSets[index].mapIndexed { sourceIndex, url ->
                    AgentSourceCitation("Pass ${index + 1} source ${sourceIndex + 1}", url)
                },
            )
        }
        val decision = ResearchQualityGate.evaluateGoal(goal(tasks, evidence))

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    @Test
    fun aggregateDiversityStillRejectsAResearchGraphThatOnlyRepeatsTwoSources() {
        val tasks = listOf(
            task("research_discovery", "Evidence discovery landscape", "Map the evidence landscape.", AgentCapability.DEEP_RESEARCH),
            task("research_primary", "Primary source verification", "Use official first-party original sources.", AgentCapability.DEEP_RESEARCH),
            task("research_contradictions", "Adversarial contradiction check", "Search for counterevidence and disconfirmation.", AgentCapability.DEEP_RESEARCH),
            task("research_gap_closure", "Gap closure and freshness audit", "Close gaps and check stale claims.", AgentCapability.DEEP_RESEARCH),
        ).mapIndexed { index, currentTask ->
            currentTask.copy(order = index, status = AgentTaskStatus.COMPLETED, progressScore = 1.0)
        }
        val evidence = tasks.mapIndexed { index, currentTask ->
            AgentEvidence(
                id = "repeated-evidence-$index",
                taskId = currentTask.id,
                kind = AgentEvidenceKind.DEEP_RESEARCH,
                title = currentTask.title,
                summary = "Official source and counterevidence audit.",
                content = "Official primary documentation, counterevidence, limitations, caveats, and alternatives were evaluated.",
                sources = listOf(
                    AgentSourceCitation(
                        "Repeated A",
                        if (index % 2 == 0) "https://www.alpha.example/a/" else "https://alpha.example/a",
                    ),
                    AgentSourceCitation(
                        "Repeated B",
                        if (index % 2 == 0) "https://beta.example/b" else "https://www.beta.example/b/",
                    ),
                ),
            )
        }

        val g = goal(tasks, evidence).copy(
            userRequest = "Research and compare reliable Android autonomous execution patterns in great detail."
        )
        val decision = ResearchQualityGate.evaluateGoal(g)
        val codes = researchQualityFindingCodes(decision.reasons)

        assertFalse(decision.passed)
        assertTrue("insufficient_distinct_sources" in codes)
        assertTrue("insufficient_source_domains" in codes)
        assertFalse(codes.joinToString().contains("alpha.example"))
    }

    @Test
    fun laterRecoverySourcesCanClearHistoricalOverlapWithoutRewritingCompletedPasses() {
        val tasks = listOf(
            task("research_discovery", "Evidence discovery landscape", "Map the evidence landscape.", AgentCapability.DEEP_RESEARCH),
            task("research_primary", "Primary source verification", "Use official first-party original sources.", AgentCapability.DEEP_RESEARCH),
            task("research_contradictions", "Adversarial contradiction check", "Search for counterevidence and disconfirmation.", AgentCapability.DEEP_RESEARCH),
            task("research_gap_closure", "Gap closure and freshness audit", "Close gaps and check stale claims.", AgentCapability.DEEP_RESEARCH),
            task("verification_research_recovery_1", "Research source recovery", "Add genuinely new evidence.", AgentCapability.DEEP_RESEARCH),
        ).mapIndexed { index, currentTask ->
            currentTask.copy(order = index, status = AgentTaskStatus.COMPLETED, progressScore = 1.0)
        }
        val sourceSets = listOf(
            listOf("https://alpha.example/a", "https://beta.example/b"),
            listOf("https://alpha.example/a", "https://beta.example/b"),
            listOf("https://gamma.example/c", "https://delta.example/d"),
            listOf("https://epsilon.example/e", "https://zeta.example/f"),
            listOf("https://eta.example/g", "https://theta.example/h"),
        )
        val toolActivity = buildString {
            appendLine()
            appendLine("Autonomous local tool activity:")
            appendLine("- [PASS] public_web_search: Discovered sources.")
            appendLine("- [PASS] public_web_fetch: Read official source from https://gov.example/primary.")
        }
        val evidence = tasks.mapIndexed { index, currentTask ->
            AgentEvidence(
                id = "repairable-evidence-$index",
                taskId = currentTask.id,
                kind = AgentEvidenceKind.DEEP_RESEARCH,
                title = currentTask.title,
                summary = "Official source and counterevidence audit.",
                content = "Official primary documentation, counterevidence, limitations, caveats, and alternatives were evaluated. $toolActivity",
                sources = sourceSets[index].mapIndexed { sourceIndex, url ->
                    AgentSourceCitation("Pass ${index + 1} source ${sourceIndex + 1}", url)
                },
            )
        }

        val decision = ResearchQualityGate.evaluateGoal(goal(tasks, evidence))

        assertTrue(decision.reasons.joinToString(), decision.passed)
        assertTrue(researchQualityFindingCodes(decision.reasons).isEmpty())
    }

    @Test
    fun verificationRecoveryTasksDoNotEscalateAStandardGoalIntoADeepMovingTarget() {
        val tasks = listOf(
            task("research_discovery", "Evidence discovery landscape", "Map the evidence landscape.", AgentCapability.DEEP_RESEARCH),
            task("research_primary", "Primary source verification", "Use official first-party sources.", AgentCapability.DEEP_RESEARCH),
            task("research_contradictions", "Adversarial contradiction check", "Seek counterevidence.", AgentCapability.DEEP_RESEARCH),
            task("verification_research_recovery_1", "Research source recovery", "Close the remaining source deficit.", AgentCapability.DEEP_RESEARCH),
        ).mapIndexed { index, currentTask ->
            currentTask.copy(order = index, status = AgentTaskStatus.COMPLETED, progressScore = 1.0)
        }
        val toolActivity = buildString {
            appendLine()
            appendLine("Autonomous local tool activity:")
            appendLine("- [PASS] public_web_search: Discovered sources.")
            appendLine("- [PASS] public_web_fetch: Read first-party evidence from https://official.example/primary.")
        }
        val evidence = tasks.mapIndexed { index, currentTask ->
            AgentEvidence(
                id = "standard-recovery-evidence-$index",
                taskId = currentTask.id,
                kind = AgentEvidenceKind.DEEP_RESEARCH,
                title = currentTask.title,
                summary = "Official source and counterevidence review.",
                content = "Official first-party evidence was checked, with counterevidence, limitations, caveats, and uncertainty documented. $toolActivity",
                sources = listOf(
                    AgentSourceCitation("Source ${index + 1}A", "https://standard${index + 1}.example/a"),
                    AgentSourceCitation("Source ${index + 1}B", "https://standard${index + 1}.example/b"),
                ),
            )
        }
        val standardGoal = goal(tasks, evidence).copy(
            userRequest = "Analyze the supplied evidence offline only.",
        )

        val decision = ResearchQualityGate.evaluateGoal(
            standardGoal,
            AutonomyPolicy(deepResearchByDefault = false),
        )

        assertTrue(decision.reasons.joinToString(), decision.passed)
        assertFalse(decision.reasons.any { it.contains("gap and freshness", ignoreCase = true) })
    }

    @Test
    fun autonomousRuntimeExposesFortyNineUniqueStaticTools() {
        val catalogs = listOf(
            SafeToolCatalog.definitions,
            AdvancedToolCatalog.definitions,
            WorkspaceToolCatalog.definitions,
            RuntimeDiagnosticToolCatalog.definitions,
            PublicWebToolCatalog.definitions,
            HostedSandboxToolCatalog.definitions,
        )
        val definitions = catalogs.flatten()

        assertEquals(21, SafeToolCatalog.definitions.size)
        assertEquals(17, AdvancedToolCatalog.definitions.size)
        assertEquals(7, WorkspaceToolCatalog.definitions.size)
        assertEquals(1, RuntimeDiagnosticToolCatalog.definitions.size)
        assertEquals(2, PublicWebToolCatalog.definitions.size)
        assertEquals(1, HostedSandboxToolCatalog.definitions.size)
        assertEquals(49, definitions.size)
        assertEquals(definitions.size, definitions.map { it.name }.distinct().size)
        assertTrue(definitions.any { it.name == "sandbox_workbench" })
        assertTrue(definitions.any { it.name == "public_web_search" })
        assertTrue(definitions.any { it.name == "public_web_fetch" })
    }

    @Test
    fun researchReceivesOnlyPublicWebLocalTools() {
        val definitions = listOf(
            SafeToolCatalog.definitions,
            AdvancedToolCatalog.definitions,
            WorkspaceToolCatalog.definitions,
            RuntimeDiagnosticToolCatalog.definitions,
            PublicWebToolCatalog.definitions,
            HostedSandboxToolCatalog.definitions,
        ).flatten()

        val selected = capabilityScopedToolDefinitions(AgentCapability.DEEP_RESEARCH, definitions)

        assertEquals(setOf("public_web_search", "public_web_fetch"), selected.map { it.name }.toSet())
        assertEquals(2, selected.size)
    }

    @Test
    fun generatedRecipeCanComposeApprovedDeterministicBuiltins() {
        val recipe = ToolRecipe(
            toolName = "add_days_checked",
            displayName = "Add days checked",
            description = "Adds a caller-supplied number of days to an ISO date.",
            parameters = listOf(
                ToolRecipeParameter("date", "ISO date"),
                ToolRecipeParameter("days", "Signed day count"),
            ),
            steps = listOf(
                ToolRecipeStep(
                    id = "add",
                    operation = ToolRecipeOperation.INVOKE_BUILTIN,
                    arguments = mapOf(
                        "tool_name" to "date_add",
                        "date" to "\${input.date}",
                        "amount" to "\${input.days}",
                        "unit" to "days",
                    ),
                ),
                ToolRecipeStep(
                    id = "extract",
                    operation = ToolRecipeOperation.JSON_GET,
                    arguments = mapOf(
                        "json" to "\${step.add.output}",
                        "path" to "result_date",
                    ),
                ),
            ),
            outputTemplate = "\${step.extract.output}",
            tests = listOf(
                ToolRecipeTest(
                    inputs = mapOf("date" to "2026-07-16", "days" to "5"),
                    expectedOutput = "2026-07-21",
                ),
            ),
        )

        val validation = ToolRecipeValidator.validate(recipe)
        val tests = ToolRecipeEngine().runTests(recipe)

        assertTrue(validation.errors.joinToString(), validation.valid)
        assertEquals(1, tests.size)
        assertTrue(tests.single().message, tests.single().passed)
        assertEquals(
            "2026-07-21",
            ToolRecipeEngine().execute(recipe, mapOf("date" to "2026-07-16", "days" to "5")),
        )
    }

    @Test
    fun generatedRecipeCannotInvokeHostedSandboxOrWorkspaceMutation() {
        val sandboxRecipe = builtInRecipe("sandbox_workbench")
        val workspaceRecipe = builtInRecipe("workspace_write_text")

        val sandboxValidation = ToolRecipeValidator.validate(sandboxRecipe)
        val workspaceValidation = ToolRecipeValidator.validate(workspaceRecipe)

        assertFalse(sandboxValidation.valid)
        assertTrue(sandboxValidation.errors.any { it.contains("unapproved") })
        assertFalse(workspaceValidation.valid)
        assertTrue(workspaceValidation.errors.any { it.contains("unapproved") })
    }

    @Test
    fun generatedRecipeCannotSelectBuiltinDynamically() {
        val recipe = ToolRecipe(
            toolName = "dynamic_builtin",
            displayName = "Dynamic built-in",
            description = "Must be rejected because the tool name is caller controlled.",
            parameters = listOf(
                ToolRecipeParameter("tool_name", "Requested tool"),
                ToolRecipeParameter("text", "Input text"),
            ),
            steps = listOf(
                ToolRecipeStep(
                    id = "invoke",
                    operation = ToolRecipeOperation.INVOKE_BUILTIN,
                    arguments = mapOf(
                        "tool_name" to "\${input.tool_name}",
                        "text" to "\${input.text}",
                    ),
                ),
            ),
            outputTemplate = "\${step.invoke.output}",
            tests = listOf(
                ToolRecipeTest(
                    inputs = mapOf("tool_name" to "count_text", "text" to "hello"),
                    expectedContains = "characters",
                ),
            ),
        )

        val validation = ToolRecipeValidator.validate(recipe)

        assertFalse(validation.valid)
        assertTrue(validation.errors.any { it.contains("dynamic tool selection") })
    }

    @Test
    fun legacyStandardResearchMissionRemainsResumable() {
        val researchTask = task(
            id = "legacy",
            title = "Research",
            instructions = "Find a source.",
            capability = AgentCapability.WEB_RESEARCH,
        ).copy(status = AgentTaskStatus.COMPLETED, progressScore = 1.0)
        val legacyGoal = goal(
            tasks = listOf(researchTask),
            evidence = listOf(evidence("legacy-evidence", "Grounded content", 1L)),
        )

        val decision = ResearchQualityGate.evaluateGoal(legacyGoal)

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    private fun requestSpecificResearchDraft(
        researchPasses: Int,
        extraTasks: List<AgentTaskDraft> = emptyList(),
    ): AgentPlanDraft {
        val topics = listOf(
            Triple(
                "map_denton_question",
                "Define Denton's maximum-ground-elevation question",
                "Resolve whether the request means current incorporated City of Denton land, county land, extraterritorial jurisdiction, natural bare earth, or structures, and identify the municipal boundary polygon and measurement decision that must be made.",
            ),
            Triple(
                "verify_denton_surface",
                "Verify Denton boundary and elevation surfaces",
                "Identify the current municipal boundary polygon and the strongest available bare-earth elevation surfaces for Denton, including vertical datum, resolution, acquisition date, and the method needed to locate a maximum inside that polygon.",
            ),
            Triple(
                "challenge_denton_maximum",
                "Challenge candidate Denton high points",
                "Test whether candidate maximum cells fall outside Denton, represent buildings or canopy, use an incompatible datum, or conflict with official contours, benchmarks, annexation dates, or independent topographic evidence.",
            ),
            Triple(
                "close_denton_gaps",
                "Close Denton topography evidence gaps",
                "Follow the exact tile names, benchmark identifiers, coordinates, annexation records, and discrepancies discovered earlier until the strongest Denton maximum is supported or the remaining precision limit is explicitly bounded.",
            ),
        )
        val researchTasks = topics.take(researchPasses).mapIndexed { index, (id, title, instructions) ->
            AgentTaskDraft(
                id = id,
                title = title,
                instructions = instructions,
                capability = AgentCapability.DEEP_RESEARCH,
                dependsOn = emptyList(),
                weight = 1.5 + index * 0.1,
                acceptanceCriteria = listOf(
                    com.david.openassistant.agent.AgentAcceptanceCriterion(
                        "${id}_specific",
                        "The Denton-specific unknown and its strongest evidence target are resolved or explicitly bounded.",
                        1.0,
                    ),
                ),
            )
        }
        return AgentPlanDraft(
            title = "Determine Denton's highest natural ground elevation",
            objective = "Find the maximum bare-earth elevation inside current City of Denton boundaries with datum-aware evidence.",
            finalOutputDescription = "A sourced Denton maximum with boundary, datum, method, conflicts, and uncertainty.",
            acceptanceCriteria = listOf(
                com.david.openassistant.agent.AgentAcceptanceCriterion(
                    "denton_answer",
                    "The answer distinguishes city from county and natural ground from structures.",
                    1.0,
                ),
            ),
            tasks = listOf(
                AgentTaskDraft(
                    id = "interpret_denton",
                    title = "Interpret the Denton topography request",
                    instructions = "Operationalize current municipal boundary, natural ground, maximum elevation, vertical datum, raster resolution, and the uncertainty acceptable for the Denton conclusion.",
                    capability = AgentCapability.REASON,
                    dependsOn = emptyList(),
                    weight = 1.0,
                    acceptanceCriteria = listOf(
                        com.david.openassistant.agent.AgentAcceptanceCriterion(
                            "interpret_denton_scope",
                            "Denton scope, definitions, ambiguities, and answer-changing evidence are explicit.",
                            1.0,
                        ),
                    ),
                ),
            ) + researchTasks + extraTasks + AgentTaskDraft(
                id = "synthesize_denton",
                title = "Synthesize the verified Denton elevation result",
                instructions = "Answer the exact Denton question from the boundary-aware, datum-aware evidence and distinguish measured facts, spatial inference, conflicting values, and remaining resolution uncertainty.",
                capability = AgentCapability.SYNTHESIZE,
                dependsOn = emptyList(),
                weight = 2.5,
                acceptanceCriteria = listOf(
                    com.david.openassistant.agent.AgentAcceptanceCriterion(
                        "synthesize_denton_exactly",
                        "The result directly answers the Denton municipal bare-earth maximum question.",
                        1.0,
                    ),
                ),
            ),
        )
    }


    private fun builtInRecipe(toolName: String) = ToolRecipe(
        toolName = "blocked_${toolName}",
        displayName = "Blocked $toolName",
        description = "Validation fixture.",
        parameters = listOf(ToolRecipeParameter("input", "Input")),
        steps = listOf(
            ToolRecipeStep(
                id = "invoke",
                operation = ToolRecipeOperation.INVOKE_BUILTIN,
                arguments = mapOf(
                    "tool_name" to toolName,
                    "task" to "\${input.input}",
                    "path" to "fixture.txt",
                    "text" to "\${input.input}",
                ),
            ),
        ),
        outputTemplate = "\${step.invoke.output}",
        tests = listOf(ToolRecipeTest(inputs = mapOf("input" to "x"), expectedContains = "x")),
    )

    private fun task(
        id: String,
        title: String,
        instructions: String,
        capability: AgentCapability = AgentCapability.REASON,
    ) = AgentTask(
        id = id,
        order = 0,
        title = title,
        instructions = instructions,
        capability = capability,
        status = AgentTaskStatus.QUEUED,
    )

    private fun goal(
        tasks: List<AgentTask>,
        evidence: List<AgentEvidence>,
    ) = AgentGoal(
        conversationId = "conversation",
        userRequest = "Design reliable Android autonomous execution",
        title = "Autonomous execution",
        objective = "Use evidence to design persistent Android WorkManager recovery",
        finalOutputDescription = "Verified architecture",
        status = AgentGoalStatus.RUNNING,
        plannerModelId = "planner",
        executionModelId = "executor",
        tasks = tasks,
        evidence = evidence,
        attempts = tasks
            .filter { it.capability in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH) }
            .map { currentTask ->
                com.david.openassistant.agent.AgentAttempt(
                    id = "attempt-${currentTask.id}",
                    taskId = currentTask.id,
                    status = com.david.openassistant.agent.AgentAttemptStatus.SUCCEEDED,
                    startedAt = 1L,
                    finishedAt = 2L,
                    modelId = "model",
                    webSearchRequests = if (currentTask.capability == AgentCapability.DEEP_RESEARCH) 3 else 1,
                )
            },
    )

    private fun evidence(
        id: String,
        content: String,
        createdAt: Long,
    ) = AgentEvidence(
        id = id,
        kind = AgentEvidenceKind.WEB_RESEARCH,
        title = id,
        summary = content,
        content = content,
        sources = listOf(AgentSourceCitation("Official source", "https://example.com/$id")),
        createdAt = createdAt,
    )

    private fun factClaim(taskId: String, url: String) = AgentClaim(
        id = "$taskId-claim",
        taskId = taskId,
        text = "A factual claim.",
        type = AgentClaimType.FACT,
        confidence = 0.9,
        support = AgentClaimSupport.SUPPORTED,
        sourceUrls = listOf(url),
    )

    private fun factClaims(taskId: String, vararg urls: String): List<AgentClaim> = urls.mapIndexed { index, url ->
        factClaim(taskId, url).copy(
            id = "$taskId-claim-$index",
            text = "Factual finding ${index + 1}.",
        )
    }

    private fun fullSourceReadExecutions() = listOf(
        com.david.openassistant.agent.AgentToolExecution("public_web_fetch", "Read full source one.", true),
        com.david.openassistant.agent.AgentToolExecution("public_web_fetch", "Read full source two.", true),
        com.david.openassistant.agent.AgentToolExecution("public_web_fetch", "Read full source three.", true),
    )
    @Test
    fun ordinaryExternalRecommendationUsesVerifiedDeepResearch() {
        val decision = AutomationRouter.decide(
            "Recommend the strongest flood-risk dataset for evaluating a rural parcel",
            hasImage = false,
            modelSupportsTools = true,
        )

        assertEquals(AutomationRoute.AUTONOMOUS_GOAL, decision.route)
        assertEquals(AgentResearchDepth.DEEP, decision.researchPolicy.depth)
    }

}

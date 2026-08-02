package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class RequestSourceRole {
    BASE_REQUEST,
    ADDITIVE_REFINEMENT,
    CORRECTION,
    REPLACEMENT,
    EXCLUSION,
    CLARIFICATION_ANSWER,
}

data class ResearchRequestSource(
    val messageId: String,
    val conversationId: String,
    val sequence: Int,
    val literalText: String,
    val createdAt: Long,
    val role: RequestSourceRole,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("message_id", messageId)
        put("conversation_id", conversationId)
        put("sequence", sequence)
        put("literal_text", literalText)
        put("created_at", createdAt)
        put("role", role.name)
    }

    companion object {
        fun fromJson(json: JSONObject): ResearchRequestSource = ResearchRequestSource(
            messageId = json.optString("message_id", ""),
            conversationId = json.optString("conversation_id", ""),
            sequence = json.optInt("sequence", 0),
            literalText = json.optString("literal_text", ""),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
            role = try {
                RequestSourceRole.valueOf(json.optString("role", RequestSourceRole.BASE_REQUEST.name))
            } catch (e: Exception) {
                RequestSourceRole.BASE_REQUEST
            },
        )
    }
}

data class ResearchConstraint(
    val id: String,
    val text: String,
    val isMandatory: Boolean = true,
    val constraintType: String = "REQUIREMENT",
    val sourceMessageId: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("is_mandatory", isMandatory)
        put("constraint_type", constraintType)
        put("source_message_id", sourceMessageId)
    }

    companion object {
        fun fromJson(json: JSONObject): ResearchConstraint = ResearchConstraint(
            id = json.optString("id", ""),
            text = json.optString("text", ""),
            isMandatory = json.optBoolean("is_mandatory", true),
            constraintType = json.optString("constraint_type", "REQUIREMENT"),
            sourceMessageId = json.optString("source_message_id", ""),
        )
    }
}

data class ResolvedResearchRequest(
    val schemaVersion: Int = 2,
    val originalBaseRequest: String,
    val resolvedRequest: String,
    val latestLiteralUserMessage: String,
    val sourceMessageIds: List<String>,
    val sourceFragments: List<ResearchRequestSource>,
    val requiredConstraints: List<ResearchConstraint>,
    val exclusions: List<ResearchConstraint>,
    val unresolvedAmbiguities: List<String> = emptyList(),
    val resolutionMethod: String = "DETERMINISTIC_MULTI_TURN_MERGE",
    val contentHash: String = "",
    val canonicalSubject: String = "",
    val strongSubjectAnchors: List<String> = emptyList(),
    val subjectSourceMessageIds: List<String> = emptyList(),
    val subjectResolutionMethod: String = "HEURISTIC_NOUN_PHRASE",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("original_base_request", originalBaseRequest)
        put("resolved_request", resolvedRequest)
        put("latest_literal_user_message", latestLiteralUserMessage)
        put("source_message_ids", JSONArray(sourceMessageIds))
        put("source_fragments", JSONArray(sourceFragments.map { it.toJson() }))
        put("required_constraints", JSONArray(requiredConstraints.map { it.toJson() }))
        put("exclusions", JSONArray(exclusions.map { it.toJson() }))
        put("unresolved_ambiguities", JSONArray(unresolvedAmbiguities))
        put("resolution_method", resolutionMethod)
        put("content_hash", contentHash)
        put("canonical_subject", canonicalSubject)
        put("strong_subject_anchors", JSONArray(strongSubjectAnchors))
        put("subject_source_message_ids", JSONArray(subjectSourceMessageIds))
        put("subject_resolution_method", subjectResolutionMethod)
    }

    companion object {
        fun fromJson(json: JSONObject?): ResolvedResearchRequest? {
            if (json == null) return null
            val sourceFragmentsJson = json.optJSONArray("source_fragments") ?: JSONArray()
            val sourceFragments = (0 until sourceFragmentsJson.length()).mapNotNull { i ->
                sourceFragmentsJson.optJSONObject(i)?.let { ResearchRequestSource.fromJson(it) }
            }

            val requiredJson = json.optJSONArray("required_constraints") ?: JSONArray()
            val requiredConstraints = (0 until requiredJson.length()).mapNotNull { i ->
                requiredJson.optJSONObject(i)?.let { ResearchConstraint.fromJson(it) }
            }

            val exclusionsJson = json.optJSONArray("exclusions") ?: JSONArray()
            val exclusions = (0 until exclusionsJson.length()).mapNotNull { i ->
                exclusionsJson.optJSONObject(i)?.let { ResearchConstraint.fromJson(it) }
            }

            val messageIdsJson = json.optJSONArray("source_message_ids") ?: JSONArray()
            val sourceMessageIds = (0 until messageIdsJson.length()).map { i ->
                messageIdsJson.optString(i, "")
            }.filter { it.isNotBlank() }

            val ambiguitiesJson = json.optJSONArray("unresolved_ambiguities") ?: JSONArray()
            val unresolvedAmbiguities = (0 until ambiguitiesJson.length()).map { i ->
                ambiguitiesJson.optString(i, "")
            }.filter { it.isNotBlank() }

            val storedVersion = json.optInt("schema_version", 1)
            val storedBaseRequest = json.optString("original_base_request", "")
            val storedResolvedRequest = json.optString("resolved_request", "")

            var canonicalSubject = json.optString("canonical_subject", "")
            var strongAnchors = json.optJSONArray("strong_subject_anchors").toStringList()
            var subjectMsgIds = json.optJSONArray("subject_source_message_ids").toStringList()
            var subResMethod = json.optString("subject_resolution_method", "HEURISTIC_NOUN_PHRASE")

            if (storedVersion < 2 && canonicalSubject.isBlank()) {
                canonicalSubject = deriveFallbackSubject(storedBaseRequest)
                strongAnchors = listOf(canonicalSubject).filter { it.isNotBlank() }
                subResMethod = "LEGACY_DETERMINISTIC_DERIVATION"
            }

            return ResolvedResearchRequest(
                schemaVersion = 2,
                originalBaseRequest = storedBaseRequest,
                resolvedRequest = storedResolvedRequest,
                latestLiteralUserMessage = json.optString("latest_literal_user_message", ""),
                sourceMessageIds = sourceMessageIds,
                sourceFragments = sourceFragments,
                requiredConstraints = requiredConstraints,
                exclusions = exclusions,
                unresolvedAmbiguities = unresolvedAmbiguities,
                resolutionMethod = json.optString("resolution_method", "DETERMINISTIC_MULTI_TURN_MERGE"),
                contentHash = json.optString("content_hash", ""),
                canonicalSubject = canonicalSubject,
                strongSubjectAnchors = strongAnchors,
                subjectSourceMessageIds = subjectMsgIds,
                subjectResolutionMethod = subResMethod,
            )
        }

        fun createFallbackSingleRequest(userRequest: String): ResolvedResearchRequest {
            val subject = deriveFallbackSubject(userRequest)
            val hash = computeHash(userRequest, listOf(userRequest), subject)
            return ResolvedResearchRequest(
                originalBaseRequest = userRequest,
                resolvedRequest = userRequest,
                latestLiteralUserMessage = userRequest,
                sourceMessageIds = emptyList(),
                sourceFragments = emptyList(),
                requiredConstraints = listOf(ResearchConstraint(id = "c-0", text = userRequest)),
                exclusions = emptyList(),
                contentHash = hash,
                canonicalSubject = subject,
                strongSubjectAnchors = listOf(subject).filter { it.isNotBlank() },
                subjectResolutionMethod = "INITIAL_CREATION_HEURISTIC"
            )
        }

        fun resolveFromHistory(
            messages: List<ChatMessage>,
            conversationId: String,
        ): ResolvedResearchRequest {
            val userMessages = messages.filter { it.role == ChatRole.USER && it.content.isNotBlank() }
            if (userMessages.isEmpty()) {
                return createFallbackSingleRequest("")
            }

            val baseUserMessage = userMessages.first()
            val latestUserMessage = userMessages.last()

            val sourceFragments = mutableListOf<ResearchRequestSource>()
            val requiredConstraints = mutableListOf<ResearchConstraint>()
            val exclusions = mutableListOf<ResearchConstraint>()
            val ambiguities = mutableListOf<String>()

            val now = System.currentTimeMillis()
            userMessages.forEachIndexed { index, msg ->
                val text = msg.content.trim()
                val role = determineSourceRole(index, text)
                val fragment = ResearchRequestSource(
                    messageId = msg.id,
                    conversationId = conversationId,
                    sequence = index + 1,
                    literalText = text,
                    createdAt = now + index,
                    role = role,
                )
                sourceFragments.add(fragment)

                when (role) {
                    RequestSourceRole.EXCLUSION -> {
                        exclusions.add(
                            ResearchConstraint(
                                id = "ex-$index",
                                text = text,
                                isMandatory = true,
                                constraintType = "EXCLUSION",
                                sourceMessageId = msg.id,
                            ),
                        )
                    }
                    else -> {
                        requiredConstraints.add(
                            ResearchConstraint(
                                id = "req-$index",
                                text = text,
                                isMandatory = true,
                                constraintType = "REQUIREMENT",
                                sourceMessageId = msg.id,
                            ),
                        )
                    }
                }
            }

            val resolvedText = buildResolvedRequestText(sourceFragments, userMessages)
            
            // Fix: Derive subject from the FIRST non-replacement message to maintain continuity
            val subjectSource = sourceFragments.firstOrNull { it.role == RequestSourceRole.BASE_REQUEST }
                ?: sourceFragments.firstOrNull()
            val subject = deriveFallbackSubject(subjectSource?.literalText ?: baseUserMessage.content)
            
            val hash = computeHash(resolvedText, requiredConstraints.map { it.text }, subject)

            // Collect all unique subject message IDs (usually just the base one)
            val subjectMsgIds = listOfNotNull(subjectSource?.messageId)

            return ResolvedResearchRequest(
                schemaVersion = 2,
                originalBaseRequest = baseUserMessage.content.trim(),
                resolvedRequest = resolvedText,
                latestLiteralUserMessage = latestUserMessage.content.trim(),
                sourceMessageIds = userMessages.map { it.id },
                sourceFragments = sourceFragments,
                requiredConstraints = requiredConstraints,
                exclusions = exclusions,
                unresolvedAmbiguities = ambiguities,
                resolutionMethod = "DETERMINISTIC_MULTI_TURN_MERGE",
                contentHash = hash,
                canonicalSubject = subject,
                strongSubjectAnchors = generateStrongAnchors(subject, resolvedText),
                subjectSourceMessageIds = subjectMsgIds,
                subjectResolutionMethod = "DETERMINISTIC_MULTI_TURN_MERGE"
            )
        }

        private fun generateStrongAnchors(subject: String, @Suppress("UNUSED_PARAMETER") resolvedText: String): List<String> {
            val anchors = mutableListOf<String>()
            if (subject.isNotBlank()) {
                anchors.add(subject)
                subject.split(" ").filter { it.length > 3 }.forEach { anchors.add(it) }
            }
            
            return anchors.map { it.lowercase(Locale.US) }.distinct()
        }

        private fun deriveFallbackSubject(request: String): String {
            val commandStopWords = setOf(
                "tell", "me", "everything", "you", "can", "own", "develop", "include", "your", "think", "might", "about", "provide", "show", "what", "is", "are", "was", "were", "the", "a", "an", "i", "want", "to", "research", "help", "decide", "look", "buy", "best", "take", "down", "actually", "more", "needs"
            )
            val words = request.split(Regex("[^\\p{L}\\p{N}]+"))
                .map { it.lowercase(java.util.Locale.US) }
                .filter { it.isNotBlank() && it !in commandStopWords }
            
            return words.take(2).joinToString(" ")
        }

        private fun determineSourceRole(index: Int, text: String): RequestSourceRole {
            if (index == 0) return RequestSourceRole.BASE_REQUEST
            val lower = text.lowercase()
            return when {
                lower.startsWith("not ") || lower.startsWith("ignore ") || lower.contains("except ") -> RequestSourceRole.EXCLUSION
                lower.startsWith("actually ") || lower.startsWith("instead ") || lower.startsWith("change ") -> RequestSourceRole.CORRECTION
                lower.startsWith("yes ") || lower.startsWith("no ") || lower == "yes" || lower == "no" -> RequestSourceRole.CLARIFICATION_ANSWER
                lower.startsWith("start over") || lower.startsWith("reset") -> RequestSourceRole.REPLACEMENT
                else -> RequestSourceRole.ADDITIVE_REFINEMENT
            }
        }

        private fun buildResolvedRequestText(
            fragments: List<ResearchRequestSource>,
            userMessages: List<ChatMessage>,
        ): String {
            if (userMessages.size == 1) return userMessages.first().content.trim()

            val baseText = userMessages.first().content.trim()
            val refinements = fragments.drop(1).map { fragment ->
                var text = fragment.literalText.trim()
                val lower = text.lowercase()
                if (lower.startsWith("more ")) {
                    text = text.substring(5).trim()
                } else if (lower.startsWith("needs to have ")) {
                    text = text.substring(14).trim()
                } else if (lower.startsWith("needs ")) {
                    text = text.substring(6).trim()
                }
                text
            }.filter { it.isNotBlank() }

            if (refinementIsReplacement(refinements)) {
                return userMessages.last().content.trim()
            }

            return "$baseText (${refinementSentence(refinements)})"
        }

        private fun refinementIsReplacement(refinements: List<String>): Boolean {
            return refinements.any { it.equals("start over", ignoreCase = true) || it.equals("reset", ignoreCase = true) }
        }

        private fun refinementSentence(refinements: List<String>): String {
            return "additional requirements: " + refinements.joinToString("; ")
        }

        private fun computeHash(resolvedText: String, constraints: List<String>, subject: String): String {
            val raw = resolvedText + "||" + constraints.sorted().joinToString("|") + "||" + subject
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

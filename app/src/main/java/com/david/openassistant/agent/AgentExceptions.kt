package com.david.openassistant.agent

/**
 * Thrown when a critical persistence operation fails for a mission exchange.
 */
class TerminalPersistenceException(
    val goalId: String,
    val taskId: String?,
    val exchangeId: String,
    val parentOperationId: String,
    val operation: String,
    val intendedOutcome: ExchangeOutcome,
    val storeFailure: String,
    val safeReason: String? = null,
) : IllegalStateException("Terminal persistence failed for goal $goalId, exchange $exchangeId ($operation -> $intendedOutcome): $storeFailure${safeReason?.let { " - $it" } ?: ""}")

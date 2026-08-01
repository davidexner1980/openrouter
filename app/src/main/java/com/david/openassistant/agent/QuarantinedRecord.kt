package com.david.openassistant.agent

import java.util.UUID

/**
 * Durable record representing a corrupted or malformed critical entity in storage.
 * Preserves diagnostic metadata, bounded SHA-256 hash, and parse error without
 * dropping sibling data or causing unsafe automatic execution replay.
 */
data class QuarantinedRecord(
    val id: String = UUID.randomUUID().toString(),
    val recordType: String,
    val originalIndexOrId: String,
    val sha256Hash: String,
    val safeParseError: String,
    val detectedTimestamp: Long = System.currentTimeMillis(),
    val sourceSchemaVersion: Int,
)

package com.david.openassistant.agent

import java.util.Locale
import kotlin.math.abs

/** A deterministic arithmetic conflict found inside one structured claim. */
internal data class DerivedMeasurementConsistencyIssue(
    val claimId: String,
    val message: String,
)

/**
 * Checks measurement combinations whose relationship is defined by a stable
 * formula. This complements source review: a page can be cited accurately
 * while a model still combines its numbers incorrectly.
 *
 * The first invariant is deliberately narrow and high-confidence. Arrow
 * kinetic energy in foot-pounds is grains * fps^2 / 450240. A segment is
 * checked only when it contains exactly one speed, mass, and energy value, so
 * a sentence comparing several products cannot create a false cross-pair.
 */
internal fun derivedMeasurementConsistencyIssues(
    claims: List<AgentClaim>,
): List<DerivedMeasurementConsistencyIssue> = claims.flatMap { claim ->
    claim.text
        .normalizeMeasurementText()
        .split(MEASUREMENT_SEGMENT_BOUNDARY)
        .mapNotNull { segment -> arrowKineticEnergyIssue(claim, segment) }
        .distinctBy(DerivedMeasurementConsistencyIssue::message)
}

private fun arrowKineticEnergyIssue(
    claim: AgentClaim,
    segment: String,
): DerivedMeasurementConsistencyIssue? {
    val speeds = SPEED_PATTERN.findAll(segment).mapNotNull { it.groupValues[1].measurementValue() }.toList()
    val masses = GRAIN_PATTERN.findAll(segment).mapNotNull { it.groupValues[1].measurementValue() }.toList()
    val energies = ENERGY_PATTERN.findAll(segment).mapNotNull { it.groupValues[1].measurementValue() }.toList()
    if (speeds.size != 1 || masses.size != 1 || energies.size != 1) return null

    val speedFps = speeds.single()
    val massGrains = masses.single()
    val reportedFootPounds = energies.single()
    if (speedFps !in 20.0..500.0 || massGrains !in 50.0..2_000.0 || reportedFootPounds !in 0.1..500.0) {
        return null
    }

    val expectedFootPounds = massGrains * speedFps * speedFps / ARROW_ENERGY_CONVERSION
    val tolerance = maxOf(MINIMUM_ENERGY_TOLERANCE, expectedFootPounds * RELATIVE_ENERGY_TOLERANCE)
    if (abs(reportedFootPounds - expectedFootPounds) <= tolerance) return null

    return DerivedMeasurementConsistencyIssue(
        claimId = claim.id,
        message = buildString {
            append("Claim '")
            append(claim.text.trim().replace(Regex("\\s+"), " ").take(90))
            append("' contains a derived measurement inconsistency: ")
            append(massGrains.compactMeasurement())
            append(" grains at ")
            append(speedFps.compactMeasurement())
            append(" fps implies about ")
            append("%.1f".format(Locale.US, expectedFootPounds))
            append(" ft-lb, not ")
            append(reportedFootPounds.compactMeasurement())
            append(" ft-lb.")
        },
    )
}

private fun String.normalizeMeasurementText(): String = replace('\u00a0', ' ')
    .replace('\u202f', ' ')
    .replace('\u2010', '-')
    .replace('\u2011', '-')
    .replace('\u2012', '-')
    .replace('\u2013', '-')
    .replace('\u2014', '-')

private fun String.measurementValue(): Double? = replace(",", "").toDoubleOrNull()

private fun Double.compactMeasurement(): String = if (this == toLong().toDouble()) {
    toLong().toString()
} else {
    "%.2f".format(Locale.US, this).trimEnd('0').trimEnd('.')
}

private const val ARROW_ENERGY_CONVERSION = 450_240.0
private const val MINIMUM_ENERGY_TOLERANCE = 3.0
private const val RELATIVE_ENERGY_TOLERANCE = 0.25
private const val MEASUREMENT_NUMBER = "(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"

private val MEASUREMENT_SEGMENT_BOUNDARY = Regex("[\\n;]+|(?<=[.!?])\\s+")
private val SPEED_PATTERN = Regex(
    "$MEASUREMENT_NUMBER\\s*(?:fps|feet\\s+per\\s+second)\\b",
    RegexOption.IGNORE_CASE,
)
private val GRAIN_PATTERN = Regex(
    "$MEASUREMENT_NUMBER\\s*-?\\s*grains?\\b",
    RegexOption.IGNORE_CASE,
)
private val ENERGY_PATTERN = Regex(
    "$MEASUREMENT_NUMBER\\s*(?:ft\\s*-?\\s*lbs?|foot\\s*-?\\s*pounds?)\\b",
    RegexOption.IGNORE_CASE,
)

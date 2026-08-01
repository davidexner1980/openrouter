package com.david.openassistant.domain.attachments

import kotlin.math.max
import kotlin.math.roundToInt

data class ImageDimensions(
    val width: Int,
    val height: Int,
)

fun calculateImageSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    require(width > 0 && height > 0) { "Image dimensions must be positive." }
    require(maxDimension > 0) { "Maximum dimension must be positive." }

    var sampleSize = 1
    while (max(width / sampleSize, height / sampleSize) > maxDimension * 2) {
        sampleSize *= 2
    }
    return sampleSize
}

fun scaledImageDimensions(
    width: Int,
    height: Int,
    maxDimension: Int,
): ImageDimensions {
    require(width > 0 && height > 0) { "Image dimensions must be positive." }
    require(maxDimension > 0) { "Maximum dimension must be positive." }

    val largest = max(width, height)
    if (largest <= maxDimension) return ImageDimensions(width, height)

    val scale = maxDimension.toDouble() / largest.toDouble()
    return ImageDimensions(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

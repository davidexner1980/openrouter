package com.david.openassistant

import com.david.openassistant.domain.attachments.ImageDimensions
import com.david.openassistant.domain.attachments.calculateImageSampleSize
import com.david.openassistant.domain.attachments.scaledImageDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizingTest {
    @Test
    fun sampleSizeUsesPowerOfTwoDownsampling() {
        assertEquals(4, calculateImageSampleSize(8000, 6000, 1920))
        assertEquals(1, calculateImageSampleSize(3000, 2000, 1920))
    }

    @Test
    fun scaledDimensionsPreserveAspectRatio() {
        assertEquals(ImageDimensions(1920, 1440), scaledImageDimensions(4000, 3000, 1920))
        assertEquals(ImageDimensions(1080, 1920), scaledImageDimensions(2250, 4000, 1920))
    }

    @Test
    fun smallImagesAreNotUpscaled() {
        assertEquals(ImageDimensions(800, 600), scaledImageDimensions(800, 600, 1920))
    }
}

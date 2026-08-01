package com.david.openassistant

import com.david.openassistant.data.openrouter.StreamingDeltaAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDeltaAccumulatorTest {
    @Test
    fun drainReturnsAllPendingTextAndClearsBuffer() {
        val accumulator = StreamingDeltaAccumulator()
        accumulator.append("Hello")
        accumulator.append(" world")

        assertEquals("Hello world", accumulator.drain())
        assertTrue(accumulator.isEmpty())
        assertEquals("", accumulator.drain())
    }

    @Test
    fun emptyDeltasDoNotCreatePendingWork() {
        val accumulator = StreamingDeltaAccumulator()
        accumulator.append("")
        assertTrue(accumulator.isEmpty())
    }
}

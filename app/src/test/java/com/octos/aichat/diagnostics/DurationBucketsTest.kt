package com.octos.aichat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationBucketsTest {

    @Test fun bucketBoundsAreExclusive() {
        assertEquals(0, DurationBuckets.indexOf(0))
        assertEquals(0, DurationBuckets.indexOf(249))
        assertEquals(1, DurationBuckets.indexOf(250))
        assertEquals(3, DurationBuckets.indexOf(1_999))
        assertEquals(DurationBuckets.SIZE - 1, DurationBuckets.indexOf(30_000))
        assertEquals(DurationBuckets.SIZE - 1, DurationBuckets.indexOf(Long.MAX_VALUE))
    }

    @Test fun quantiles() {
        val counts = List(DurationBuckets.SIZE) { 0 }.toMutableList().apply {
            this[1] = 5
            this[3] =
                4
            this[8] = 1
        }
        assertEquals(1, DurationBuckets.quantileBucket(counts, 0.50)) // 5 of 10 reached in bucket 1
        assertEquals(3, DurationBuckets.quantileBucket(counts, 0.90))
        assertEquals(8, DurationBuckets.quantileBucket(counts, 0.95))
        assertNull(DurationBuckets.quantileBucket(List(DurationBuckets.SIZE) { 0 }, 0.5))
    }

    @Test fun labels() {
        assertEquals("≤ 250ms", DurationBuckets.label(0))
        assertEquals("≤ 2s", DurationBuckets.label(3))
        assertEquals("> 30s", DurationBuckets.label(DurationBuckets.SIZE - 1))
    }
}

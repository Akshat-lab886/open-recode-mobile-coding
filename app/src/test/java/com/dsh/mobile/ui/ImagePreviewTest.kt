package com.dsh.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePreviewTest {

    @Test
    fun `an image already under the cap is not subsampled`() {
        assertEquals(1, ImagePreview.sampleSizeFor(800, 600))
        assertEquals(1, ImagePreview.sampleSizeFor(1600, 900))
    }

    @Test
    fun `an oversized image is halved until it fits`() {
        // 3200 -> 1600 fits exactly, so one halving is correct.
        assertEquals(2, ImagePreview.sampleSizeFor(3200, 2400))
        // 6400 -> 3200 -> 1600
        assertEquals(4, ImagePreview.sampleSizeFor(6400, 4800))
    }

    @Test
    fun `the longest edge is what decides`() {
        // Width is small but height is huge: the height must drive the sample.
        assertTrue(ImagePreview.sampleSizeFor(100, 6400) > 1)
        assertEquals(4, ImagePreview.sampleSizeFor(100, 6400))
    }

    @Test
    fun `sampling is always a power of two because that is all Android honours`() {
        for (w in intArrayOf(1601, 3200, 5000, 12000)) {
            val s = ImagePreview.sampleSizeFor(w, w)
            assertEquals("sample for $w", 0, s and (s - 1))
        }
    }

    @Test
    fun `degenerate bounds do not divide by zero`() {
        assertEquals(1, ImagePreview.sampleSizeFor(0, 100))
        assertEquals(1, ImagePreview.sampleSizeFor(100, -1))
        assertEquals(1, ImagePreview.sampleSizeFor(100, 100, maxDimension = 0))
    }
}

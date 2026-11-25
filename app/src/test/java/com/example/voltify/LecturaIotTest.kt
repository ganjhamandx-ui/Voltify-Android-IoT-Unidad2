package com.example.voltify

import com.example.voltify.model.LecturaIot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LecturaIotTest {
    @Test
    fun muestraRecienteCaducaYAdmiteDesfaseAcotado() {
        val timestamp = 1_767_225_600_000L
        val sample = LecturaIot(timestamp, 9.0, 90.0, 0.81, 50.0, "NORMAL", 0.001, 1.0)
        assertTrue(sample.isRecent(timestamp))
        assertTrue(sample.isRecent(timestamp + 90_000))
        assertTrue(sample.isRecent(timestamp - 5_000))
        assertFalse(sample.isRecent(timestamp + 90_001))
        assertFalse(sample.isRecent(timestamp - 5_001))
    }
}

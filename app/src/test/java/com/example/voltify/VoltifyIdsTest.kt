package com.example.voltify

import com.example.voltify.data.local.VoltifyIds
import org.junit.Assert.*
import org.junit.Test

class VoltifyIdsTest {
    @Test fun newIdsDoNotReuseExistingLocalIds() {
        val seen = mutableSetOf<Int>()
        repeat(2_000) {
            val id = VoltifyIds.newUniqueId(seen)
            assertTrue(id in 100_000_000..899_999_999)
            assertTrue(seen.add(id))
        }
    }

    @Test fun sameArtifactAndTimeBucketUseSameSimulatedId() {
        val block = 1_793_735_000_000L / 30_000L
        val id = VoltifyIds.simulationBucketId(1, block)
        assertEquals(id, VoltifyIds.simulationBucketId(1, block))
        assertNotEquals(id, VoltifyIds.simulationBucketId(2, block))
        assertNotEquals(id, VoltifyIds.simulationBucketId(1, block + 1))
        assertTrue(VoltifyIds.isSimulatedBucketId(id))
    }
}

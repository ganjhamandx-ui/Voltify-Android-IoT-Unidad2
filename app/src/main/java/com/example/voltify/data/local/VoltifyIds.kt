package com.example.voltify.data.local

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * IDs numéricos compatibles con los datos existentes de Firebase RTDB.
 * Reserva 100M..899M para altas de artefactos / lecturas individuales y
 * 1.000M..1.899M para eventos simulados agrupados por bloque y artefacto.
 */
object VoltifyIds {
    private val random = SecureRandom()

    fun newUniqueId(existing: Collection<Int>): Int {
        val used = existing.toHashSet()
        repeat(256) {
            val candidate = 100_000_000 + random.nextInt(800_000_000)
            if (candidate !in used) return candidate
        }
        error("No fue posible asignar un identificador local")
    }

    fun simulationBucketId(artifactId: Int, bucket: Long): Int {
        require(artifactId > 0 && bucket > 0L)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("voltify-sim-$artifactId-$bucket".toByteArray(Charsets.UTF_8))
        var hash = 0L
        for (i in 0 until 4) hash = (hash shl 8) or (digest[i].toLong() and 0xffL)
        return 1_000_000_000 + (hash % 900_000_000L).toInt()
    }

    fun isSimulatedBucketId(id: Int): Boolean = id in 1_000_000_000..1_899_999_999
}

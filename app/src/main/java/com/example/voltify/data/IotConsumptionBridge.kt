package com.example.voltify.data

/**
 * Punto de entrada para futuras lecturas INA219 recibidas desde la nube.
 */
interface IotConsumptionBridge {
    fun registerReading(artifactId: Int, kwh: Double, timestamp: Long)
}

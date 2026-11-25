package com.example.voltify.data

import com.example.voltify.model.LecturaIot
import com.example.voltify.model.IotTarget

/** Entrada de lecturas externas y consulta de telemetría física reciente. */
interface IotConsumptionBridge {
    fun registerReading(artifactId: Int, kwh: Double, timestamp: Long)
    suspend fun sendIotCommand(artifactId: Int, target: IotTarget): Boolean = false
    fun latestIotReadings(): Map<Int, LecturaIot> = emptyMap()
}

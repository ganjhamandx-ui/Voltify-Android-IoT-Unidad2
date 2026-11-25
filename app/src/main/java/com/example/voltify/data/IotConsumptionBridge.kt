package com.example.voltify.data

import com.example.voltify.model.LecturaIot

/** Entrada de lecturas externas y consulta de telemetría física reciente. */
interface IotConsumptionBridge {
    fun registerReading(artifactId: Int, kwh: Double, timestamp: Long)
    fun latestIotReadings(): Map<Int, LecturaIot> = emptyMap()
}

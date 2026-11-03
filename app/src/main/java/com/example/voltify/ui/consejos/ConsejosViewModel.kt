package com.example.voltify.ui.consejos

import android.app.Application
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.ui.BaseVoltifyViewModel

class ConsejosViewModel(application: Application) : BaseVoltifyViewModel(application) {
    fun getArtifacts(includeInactive: Boolean = false): List<ArtefactoLocal> = repository.getArtifacts(includeInactive)
    fun totalKwh(period: String): Double = repository.totalKwh(period)
    fun totalCost(period: String): Double = repository.totalCost(period)
    fun getMeta() = repository.getMeta()
    fun artifactKwh(id: Int, period: String): Double = repository.artifactKwh(id, period)
    fun currentChileDateTime(): String = repository.currentChileDateTime()
    fun formatKwh(value: Double): String = repository.formatKwh(value)
    fun formatClp(value: Double): String = repository.formatClp(value)
}

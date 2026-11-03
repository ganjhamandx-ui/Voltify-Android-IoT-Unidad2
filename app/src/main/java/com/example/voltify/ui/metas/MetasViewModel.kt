package com.example.voltify.ui.metas

import android.app.Application
import com.example.voltify.PERIODO_MES
import com.example.voltify.model.MetaLocal
import com.example.voltify.ui.BaseVoltifyViewModel

data class MetaUiState(
    val meta: MetaLocal?,
    val consumoActual: Double,
    val costoActual: Double,
    val progreso: Int,
    val fechaChile: String
)

class MetasViewModel(application: Application) : BaseVoltifyViewModel(application) {
    fun state(): MetaUiState {
        val meta = repository.getMeta()
        val consumo = repository.totalKwh(PERIODO_MES)
        val costo = repository.totalCost(PERIODO_MES)
        val progress = meta?.consumoKwh?.takeIf { it > 0.0 }?.let { ((consumo / it) * 100).toInt() } ?: 0
        return MetaUiState(meta, consumo, costo, progress, repository.currentChileDateTime())
    }

    fun save(consumoKwh: Double?, costoClp: Double?) = repository.saveMeta(MetaLocal(consumoKwh, costoClp))
    fun delete() = repository.deleteMeta()
    fun formatKwh(value: Double) = repository.formatKwh(value)
    fun formatClp(value: Double) = repository.formatClp(value)
}

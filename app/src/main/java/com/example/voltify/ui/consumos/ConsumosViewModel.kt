package com.example.voltify.ui.consumos

import android.app.Application
import com.example.voltify.PERIODO_TODOS
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.ArtifactComparison
import com.example.voltify.model.ConsumoArtefactoResumen
import com.example.voltify.model.EstadoArtefacto
import com.example.voltify.model.PeriodComparison
import com.example.voltify.model.TrendData
import com.example.voltify.ui.BaseVoltifyViewModel

data class ConsumosUiState(
    val periodoLabel: String,
    val totalKwh: Double,
    val costo: Double,
    val comparacion: PeriodComparison?,
    val tendencia: TrendData?,
    val items: List<ConsumoArtefactoResumen>
)

class ConsumosViewModel(application: Application) : BaseVoltifyViewModel(application) {
    fun state(period: String, filter: String, query: String, graphArtifactId: Int? = null): ConsumosUiState {
        repository.purgeExpiredRetiredArtifacts()
        val items = repository.consumptionSummaries(period)
            .filter { row ->
                when (filter) {
                    "activos" -> row.estado == EstadoArtefacto.ACTIVO
                    "retirados" -> row.estado == EstadoArtefacto.RETIRADO
                    "eliminados" -> row.estado == EstadoArtefacto.ELIMINADO
                    else -> true
                }
            }
            .filter { row ->
                query.isBlank() ||
                    row.nombre.contains(query, ignoreCase = true) ||
                    row.marca.contains(query, ignoreCase = true) ||
                    row.modelo.contains(query, ignoreCase = true)
            }

        val trend = when {
            period == PERIODO_TODOS -> null
            graphArtifactId != null -> repository.trendDataForArtifact(graphArtifactId, period)
            else -> repository.trendData(period)
        }

        return ConsumosUiState(
            periodoLabel = repository.periodLabel(period),
            totalKwh = repository.totalKwh(period),
            costo = repository.totalCost(period),
            comparacion = if (period == PERIODO_TODOS) null else repository.comparison(period),
            tendencia = trend,
            items = items
        )
    }

    fun currentArtifacts(): List<ArtefactoLocal> = repository.getArtifacts(includeInactive = false)
    fun compareArtifacts(aId: Int, bId: Int, period: String): ArtifactComparison? = repository.compareArtifacts(aId, bId, period)
    fun tariffKwh(): Double = repository.tariff().valorKwh
    fun retentionExpiryMillis(artifactId: Int) = repository.getArtifact(artifactId)?.let { repository.retentionExpiryMillis(it) }
    fun retentionRemainingDays(artifactId: Int) = repository.getArtifact(artifactId)?.let { repository.retentionRemainingDays(it) }
    fun formatKwh(value: Double) = repository.formatKwh(value)
    fun formatClp(value: Double) = repository.formatClp(value)
    fun formatVariation(value: Double?) = repository.formatVariation(value)
    fun formatChileDate(value: Long) = repository.formatChileDate(value)
}

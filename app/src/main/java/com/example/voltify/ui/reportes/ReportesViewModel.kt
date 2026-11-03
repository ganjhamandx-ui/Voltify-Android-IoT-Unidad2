package com.example.voltify.ui.reportes

import android.app.Application
import com.example.voltify.model.ConsumoArtefactoResumen
import com.example.voltify.model.PeriodComparison
import com.example.voltify.model.TrendData
import com.example.voltify.ui.BaseVoltifyViewModel

data class RankingItem(val resumen: ConsumoArtefactoResumen, val cost: Double)

data class ReporteUiState(
    val fecha: String,
    val periodo: String,
    val consumo: Double,
    val energia: Double,
    val fijo: Double,
    val total: Double,
    val comparacion: PeriodComparison,
    val tendencia: TrendData,
    val ranking: List<RankingItem>
)

class ReportesViewModel(application: Application) : BaseVoltifyViewModel(application) {
    fun state(period: String): ReporteUiState {
        repository.purgeExpiredRetiredArtifacts()
        val tarifa = repository.tariff().valorKwh
        val ranking = repository.consumptionSummaries(period)
            .filter { it.kwh > 0.0 }
            .sortedByDescending { it.kwh }
            .take(5)
            .map { RankingItem(it, it.kwh * tarifa) }
        return ReporteUiState(
            repository.currentChileDateTime(), repository.periodLabel(period),
            repository.totalKwh(period), repository.energyCost(period), repository.fixedCost(period), repository.totalCost(period),
            repository.comparison(period), repository.trendData(period), ranking
        )
    }

    fun reportText(period: String) = repository.reportText(period)
    fun formatKwh(value: Double) = repository.formatKwh(value)
    fun formatClp(value: Double) = repository.formatClp(value)
    fun formatVariation(value: Double?) = repository.formatVariation(value)
}

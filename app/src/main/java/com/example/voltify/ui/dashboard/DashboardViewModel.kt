package com.example.voltify.ui.dashboard

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.example.voltify.PERIODO_MES
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.PeriodComparison
import com.example.voltify.ui.BaseVoltifyViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class DashboardUiState(
    val fechaChile: String = "",
    val primerNombre: String = "",
    val consumoMes: Double = 0.0,
    val costoMes: Double = 0.0,
    val activos: Int = 0,
    val progresoMeta: Int = 0,
    val monitoreando: List<ArtefactoLocal> = emptyList(),
    val consumoMonitoreado: Double = 0.0,
    val comparacion: PeriodComparison? = null
)

class DashboardViewModel(application: Application) : BaseVoltifyViewModel(application) {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            repository.observeArtifacts().collect { refresh() }
        }
    }

    fun refresh() {
        repository.purgeExpiredRetiredArtifacts()
        val user = repository.getUser()
        val monitoring = repository.getMonitoringArtifacts()
        _uiState.value = DashboardUiState(
            fechaChile = repository.currentChileDateTime(),
            primerNombre = user.nombre.substringBefore(" "),
            consumoMes = repository.totalKwh(PERIODO_MES),
            costoMes = repository.totalCost(PERIODO_MES),
            activos = repository.getArtifacts().size,
            progresoMeta = repository.goalProgress(),
            monitoreando = monitoring,
            consumoMonitoreado = monitoring.sumOf { repository.artifactKwh(it.id, PERIODO_MES) },
            comparacion = repository.comparison(PERIODO_MES)
        )
    }

    fun formatKwh(value: Double) = repository.formatKwh(value)
    fun formatClp(value: Double) = repository.formatClp(value)
    fun formatVariation(value: Double?) = repository.formatVariation(value)
    fun logout() = repository.logout()
}

package com.example.voltify.ui.artefactos

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.example.voltify.PERIODO_MES
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.MonitoreoLecturaActual
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.ui.BaseVoltifyViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ArtefactosViewModel(application: Application) : BaseVoltifyViewModel(application) {
    private val _artifacts = MutableStateFlow<List<ArtefactoLocal>>(emptyList())
    val artifacts: StateFlow<List<ArtefactoLocal>> = _artifacts.asStateFlow()

    private val _liveReadings = MutableStateFlow<Map<Int, MonitoreoLecturaActual>>(emptyMap())
    val liveReadings: StateFlow<Map<Int, MonitoreoLecturaActual>> = _liveReadings.asStateFlow()

    private var simulationJob: Job? = null
    private var simulationVisible = false
    private val simulationIntervalSeconds = 3
    private val persistEveryCycles = 10 // Consolida historial cada 30 segundos.
    private var simulationCycle = 0
    private val pendingKwh = mutableMapOf<Int, Double>()
    private val pendingBuckets = mutableMapOf<Int, Long>()

    val categorias: List<String> get() = repository.categorias
    val fuentesMonitoreo: List<TipoFuenteMonitoreo> get() = repository.fuentesMonitoreo

    init {
        refresh()
        viewModelScope.launch {
            repository.observeArtifacts().collect { all ->
                _artifacts.value = all
                syncLiveSimulation()
            }
        }
    }

    fun refresh() {
        repository.purgeExpiredRetiredArtifacts()
        _artifacts.value = repository.getArtifacts(includeInactive = true)
    }

    fun filterArtifacts(filter: String, query: String): List<ArtefactoLocal> =
        _artifacts.value
            .filter { artifact ->
                when (filter) {
                    "monitoreando" -> artifact.activo && artifact.monitoreando
                    "detenidos" -> artifact.activo && !artifact.monitoreando
                    "retirados" -> !artifact.activo
                    else -> true
                }
            }
            .filter { artifact ->
                query.isBlank() ||
                    artifact.nombre.contains(query, ignoreCase = true) ||
                    artifact.marca.contains(query, ignoreCase = true) ||
                    artifact.modelo.contains(query, ignoreCase = true)
            }

    fun getArtifact(id: Int): ArtefactoLocal? = repository.getArtifact(id)

    fun saveArtifact(
        id: Int?,
        nombre: String,
        categoria: String,
        marca: String,
        modelo: String,
        potenciaW: Double,
        tipoFuente: TipoFuenteMonitoreo
    ) = repository.saveArtifact(id, nombre, categoria, marca, modelo, potenciaW, tipoFuente)

    fun setMonitoring(id: Int, enabled: Boolean) {
        if (!enabled) {
            flushPendingConsumption(id)
            _liveReadings.value = _liveReadings.value - id
        }

        repository.setMonitoring(id, enabled)

        // Respuesta inmediata de UI mientras llega la emisión del repositorio.
        _artifacts.value = _artifacts.value.map { artifact ->
            if (artifact.id == id && artifact.activo) artifact.copy(monitoreando = enabled) else artifact
        }
        syncLiveSimulation()
    }

    fun retireArtifact(id: Int) {
        flushPendingConsumption(id)
        repository.retireArtifact(id)
        pendingKwh.remove(id)
        _liveReadings.value = _liveReadings.value - id
        _artifacts.value = _artifacts.value.map { artifact ->
            if (artifact.id == id) artifact.copy(activo = false, monitoreando = false) else artifact
        }
        syncLiveSimulation()
    }

    fun deleteArtifactPermanently(id: Int) {
        flushPendingConsumption(id)
        repository.deleteArtifactPermanently(id)
        pendingKwh.remove(id)
        _liveReadings.value = _liveReadings.value - id
        _artifacts.value = _artifacts.value.filterNot { it.id == id }
        syncLiveSimulation()
    }

    fun simulateMonitoringSample(id: Int): Int = repository.simulateMonitoringSample(id)

    fun startLiveSimulation() {
        simulationVisible = true
        syncLiveSimulation()
    }

    fun pauseLiveSimulation() {
        simulationVisible = false
        flushAllPendingConsumption()
        simulationJob?.cancel()
        simulationJob = null
        _liveReadings.value = emptyMap()
    }

    fun liveReadingFor(id: Int): MonitoreoLecturaActual? = _liveReadings.value[id]

    /**
     * Mantiene un único ticker para todos los artefactos simulados. La lectura visible
     * cambia cada 3 segundos, pero el historial se consolida cada 30 segundos para
     * evitar miles de escrituras y mantener fluida la interfaz.
     */
    private fun syncLiveSimulation() {
        if (!simulationVisible) return

        val eligible = eligibleSimulatedArtifacts()
        val eligibleIds = eligible.map { it.id }.toSet()

        _liveReadings.value = _liveReadings.value.filterKeys { it in eligibleIds }
        pendingKwh.keys.filterNot { it in eligibleIds }.toList().forEach { id ->
            flushPendingConsumption(id)
        }

        if (eligible.isEmpty()) {
            simulationJob?.cancel()
            simulationJob = null
            return
        }

        if (simulationJob?.isActive == true) return

        simulationJob = viewModelScope.launch {
            while (isActive && simulationVisible) {
                val readings = mutableMapOf<Int, MonitoreoLecturaActual>()
                eligibleSimulatedArtifacts().forEach { artifact ->
                    repository.simulateLiveReading(artifact.id, simulationIntervalSeconds)?.let { reading ->
                        readings[artifact.id] = reading
                        val bucket = reading.timestamp / (persistEveryCycles * simulationIntervalSeconds * 1000L)
                        if (pendingBuckets[artifact.id] != null && pendingBuckets[artifact.id] != bucket) {
                            flushPendingConsumption(artifact.id)
                        }
                        pendingBuckets[artifact.id] = bucket
                        pendingKwh[artifact.id] = (pendingKwh[artifact.id] ?: 0.0) + reading.consumoIncrementalKwh
                    }
                }

                if (readings.isNotEmpty()) {
                    _liveReadings.value = _liveReadings.value + readings
                }

                simulationCycle++
                if (simulationCycle >= persistEveryCycles) {
                    flushAllPendingConsumption()
                    simulationCycle = 0
                }

                delay(simulationIntervalSeconds * 1000L)
            }
        }
    }

    private fun eligibleSimulatedArtifacts(): List<ArtefactoLocal> =
        _artifacts.value.filter {
            it.activo && it.monitoreando && it.tipoFuente == TipoFuenteMonitoreo.SIMULADA
        }

    private fun flushPendingConsumption(id: Int) {
        val kwh = pendingKwh.remove(id) ?: return
        val bucket = pendingBuckets.remove(id) ?: return
        if (kwh > 0.0) repository.appendSimulatedConsumption(id, kwh, bucket)
    }

    private fun flushAllPendingConsumption() {
        pendingKwh.keys.toList().forEach(::flushPendingConsumption)
    }

    fun artifactKwhMonth(id: Int): Double = repository.artifactKwh(id, PERIODO_MES)
    fun formatKwh(value: Double): String = repository.formatKwh(value)
    fun formatChileDate(timestamp: Long): String = repository.formatChileDate(timestamp)
}

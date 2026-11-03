package com.example.voltify.data

import android.content.Context
import com.example.voltify.data.local.VoltifyLocalDataSource
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.ArtifactComparison
import com.example.voltify.model.AuthOutcome
import com.example.voltify.model.ConsumoLocal
import com.example.voltify.model.ConsumoArtefactoResumen
import com.example.voltify.model.MetaLocal
import com.example.voltify.model.MonitoreoLecturaActual
import com.example.voltify.model.PeriodBounds
import com.example.voltify.model.PeriodComparison
import com.example.voltify.model.TarifaInfo
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.model.TrendData
import com.example.voltify.model.UsuarioLocal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Repositorio local usado mientras se integra Firebase y el puente IoT.
 */
class FakeVoltifyRepository(context: Context) : VoltifyRepository {
    private val appContext = context.applicationContext

    private val _user = MutableStateFlow<UsuarioLocal?>(null)
    private val _artifacts = MutableStateFlow<List<ArtefactoLocal>>(emptyList())
    private val _consumptions = MutableStateFlow<List<ConsumoLocal>>(emptyList())
    private val _meta = MutableStateFlow<MetaLocal?>(null)

    init {
        VoltifyLocalDataSource.ensureSeed(appContext)
        refresh()
    }

    override val comunas: List<String> get() = VoltifyLocalDataSource.comunas
    override val empresas: List<String> get() = VoltifyLocalDataSource.empresas
    override val categorias: List<String> get() = VoltifyLocalDataSource.categorias
    override val fuentesMonitoreo: List<TipoFuenteMonitoreo> get() = TipoFuenteMonitoreo.values().toList()

    override fun observeUser(): Flow<UsuarioLocal> = _user.asStateFlow().filterNotNull()
    override fun observeArtifacts(): Flow<List<ArtefactoLocal>> = _artifacts.asStateFlow()
    override fun observeConsumptions(): Flow<List<ConsumoLocal>> = _consumptions.asStateFlow()
    override fun observeMeta(): Flow<MetaLocal?> = _meta.asStateFlow()

    override fun refresh() {
        VoltifyLocalDataSource.ensureSeed(appContext)
        val user = VoltifyLocalDataSource.getUserOrNull(appContext)
        _user.value = user
        if (user == null) {
            _artifacts.value = emptyList()
            _consumptions.value = emptyList()
            _meta.value = null
        } else {
            _artifacts.value = VoltifyLocalDataSource.getArtifacts(appContext, includeInactive = true)
            _consumptions.value = VoltifyLocalDataSource.getConsumptions(appContext)
            _meta.value = VoltifyLocalDataSource.getMeta(appContext)
        }
    }

    override suspend fun login(correo: String, password: String): AuthOutcome {
        val result = VoltifyLocalDataSource.login(appContext, correo, password)
        if (result.success) refresh()
        return result
    }

    override fun logout() {
        VoltifyLocalDataSource.logout(appContext)
        _user.value = null
        _artifacts.value = emptyList()
        _consumptions.value = emptyList()
        _meta.value = null
    }

    override suspend fun register(user: UsuarioLocal, password: String): AuthOutcome {
        val result = VoltifyLocalDataSource.register(appContext, user, password)
        if (result.success) refresh()
        return result
    }

    override suspend fun beginRecovery(email: String): AuthOutcome = VoltifyLocalDataSource.beginRecovery(appContext, email)
    override fun verifyRecoveryCode(code: String): Boolean = VoltifyLocalDataSource.verifyRecoveryCode(appContext, code)

    override suspend fun finishRecovery(newPassword: String): AuthOutcome {
        val result = VoltifyLocalDataSource.finishRecovery(appContext, newPassword)
        if (result.success) refresh()
        return result
    }

    override fun isCloudConfigured(): Boolean = false
    override fun authModeLabel(): String = "LOCAL SEGURO · simulación"

    override fun getUser(): UsuarioLocal = VoltifyLocalDataSource.getUser(appContext)

    override fun saveUser(user: UsuarioLocal) {
        VoltifyLocalDataSource.saveUser(appContext, user)
        refresh()
    }

    override fun getArtifacts(includeInactive: Boolean): List<ArtefactoLocal> =
        VoltifyLocalDataSource.getArtifacts(appContext, includeInactive)

    override fun getArtifact(id: Int): ArtefactoLocal? = VoltifyLocalDataSource.getArtifact(appContext, id)

    override fun saveArtifact(
        id: Int?,
        nombre: String,
        categoria: String,
        marca: String,
        modelo: String,
        potenciaW: Double,
        tipoFuente: TipoFuenteMonitoreo
    ): ArtefactoLocal = VoltifyLocalDataSource.saveArtifact(
        appContext,
        id,
        nombre,
        categoria,
        marca,
        modelo,
        potenciaW,
        tipoFuente
    ).also { refresh() }

    override fun retireArtifact(id: Int) {
        VoltifyLocalDataSource.retireArtifact(appContext, id)
        refresh()
    }

    override fun deleteArtifactPermanently(id: Int) {
        VoltifyLocalDataSource.deleteArtifactPermanently(appContext, id)
        refresh()
    }

    override fun setMonitoring(id: Int, enabled: Boolean) {
        VoltifyLocalDataSource.setMonitoring(appContext, id, enabled)
        refresh()
    }

    override fun getMonitoring(): ArtefactoLocal? = VoltifyLocalDataSource.getMonitoring(appContext)
    override fun getMonitoringArtifacts(): List<ArtefactoLocal> = VoltifyLocalDataSource.getMonitoringArtifacts(appContext)

    override fun simulateMonitoringSample(id: Int): Int =
        VoltifyLocalDataSource.simulateMonitoringSample(appContext, id).also { refresh() }

    override fun simulateLiveReading(id: Int, intervalSeconds: Int): MonitoreoLecturaActual? =
        VoltifyLocalDataSource.simulateLiveReading(appContext, id, intervalSeconds)

    override fun purgeExpiredRetiredArtifacts() {
        VoltifyLocalDataSource.purgeExpiredRetiredArtifacts(appContext)
        refresh()
    }

    override fun retentionExpiryMillis(artifact: ArtefactoLocal): Long? = VoltifyLocalDataSource.retentionExpiryMillis(artifact)
    override fun retentionRemainingDays(artifact: ArtefactoLocal): Int? = VoltifyLocalDataSource.retentionRemainingDays(artifact)

    override fun getConsumptions(): List<ConsumoLocal> = VoltifyLocalDataSource.getConsumptions(appContext)

    override fun appendConsumption(artifactId: Int, kwh: Double) {
        VoltifyLocalDataSource.appendConsumption(appContext, artifactId, kwh)
        refresh()
    }

    override fun appendSimulatedConsumption(artifactId: Int, kwh: Double, bucket: Long) {
        VoltifyLocalDataSource.appendSimulatedConsumption(appContext, artifactId, kwh, bucket)
        refresh()
    }

    override fun registerReading(artifactId: Int, kwh: Double, timestamp: Long) {
        VoltifyLocalDataSource.registerIotReading(appContext, artifactId, kwh, timestamp)
        refresh()
    }

    override fun consumptionsForPeriod(period: String): List<ConsumoLocal> = VoltifyLocalDataSource.consumptionsForPeriod(appContext, period)
    override fun consumptionsBetween(bounds: PeriodBounds): List<ConsumoLocal> = VoltifyLocalDataSource.consumptionsBetween(appContext, bounds)
    override fun totalKwh(period: String): Double = VoltifyLocalDataSource.totalKwh(appContext, period)
    override fun totalKwh(bounds: PeriodBounds): Double = VoltifyLocalDataSource.totalKwh(appContext, bounds)
    override fun artifactKwh(id: Int, period: String): Double = VoltifyLocalDataSource.artifactKwh(appContext, id, period)
    override fun consumptionSummaries(period: String): List<ConsumoArtefactoResumen> =
        VoltifyLocalDataSource.consumptionSummaries(appContext, period)

    override fun periodBounds(period: String, previous: Boolean): PeriodBounds = VoltifyLocalDataSource.periodBounds(period, previous)
    override fun comparison(period: String): PeriodComparison = VoltifyLocalDataSource.comparison(appContext, period)
    override fun trendData(period: String): TrendData = VoltifyLocalDataSource.trendData(appContext, period)
    override fun trendDataForArtifact(artifactId: Int, period: String): TrendData =
        VoltifyLocalDataSource.trendDataForArtifact(appContext, artifactId, period)

    override fun compareArtifacts(artifactAId: Int, artifactBId: Int, period: String): ArtifactComparison? =
        VoltifyLocalDataSource.compareArtifacts(appContext, artifactAId, artifactBId, period)

    override fun tariff(): TarifaInfo = VoltifyLocalDataSource.tariff(appContext)
    override fun energyCost(period: String): Double = VoltifyLocalDataSource.energyCost(appContext, period)
    override fun fixedCost(period: String): Double = VoltifyLocalDataSource.fixedCost(appContext, period)
    override fun totalCost(period: String): Double = VoltifyLocalDataSource.totalCost(appContext, period)

    override fun getMeta(): MetaLocal? = VoltifyLocalDataSource.getMeta(appContext)

    override fun saveMeta(meta: MetaLocal) {
        VoltifyLocalDataSource.saveMeta(appContext, meta)
        refresh()
    }

    override fun deleteMeta() {
        VoltifyLocalDataSource.deleteMeta(appContext)
        refresh()
    }

    override fun goalProgress(): Int = VoltifyLocalDataSource.goalProgress(appContext)

    override fun currentChileDateTime(): String = VoltifyLocalDataSource.currentChileDateTime()
    override fun formatChileDate(millis: Long): String = VoltifyLocalDataSource.formatChileDate(millis)
    override fun formatKwh(value: Double): String = VoltifyLocalDataSource.formatKwh(value)
    override fun formatClp(value: Double): String = VoltifyLocalDataSource.formatClp(value)
    override fun formatVariation(value: Double?): String = VoltifyLocalDataSource.formatVariation(value)
    override fun periodLabel(period: String): String = VoltifyLocalDataSource.periodLabel(period)
    override fun reportText(period: String): String = VoltifyLocalDataSource.reportText(appContext, period)
}

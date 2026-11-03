package com.example.voltify.data

import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.AuthOutcome
import com.example.voltify.model.ArtifactComparison
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

/**
 * Contrato de datos usado por las pantallas de Voltify.
 */
interface VoltifyRepository : IotConsumptionBridge {
    val comunas: List<String>
    val empresas: List<String>
    val categorias: List<String>
    val fuentesMonitoreo: List<TipoFuenteMonitoreo>

    fun observeUser(): Flow<UsuarioLocal>
    fun observeArtifacts(): Flow<List<ArtefactoLocal>>
    fun observeConsumptions(): Flow<List<ConsumoLocal>>
    fun observeMeta(): Flow<MetaLocal?>

    fun refresh()

    suspend fun login(correo: String, password: String): AuthOutcome
    fun logout()
    suspend fun register(user: UsuarioLocal, password: String): AuthOutcome
    suspend fun beginRecovery(email: String): AuthOutcome
    fun verifyRecoveryCode(code: String): Boolean
    suspend fun finishRecovery(newPassword: String): AuthOutcome
    fun isCloudConfigured(): Boolean
    fun authModeLabel(): String

    fun getUser(): UsuarioLocal
    fun saveUser(user: UsuarioLocal)

    fun getArtifacts(includeInactive: Boolean = false): List<ArtefactoLocal>
    fun getArtifact(id: Int): ArtefactoLocal?
    fun saveArtifact(
        id: Int?,
        nombre: String,
        categoria: String,
        marca: String,
        modelo: String,
        potenciaW: Double,
        tipoFuente: TipoFuenteMonitoreo
    ): ArtefactoLocal
    fun retireArtifact(id: Int)
    fun deleteArtifactPermanently(id: Int)
    fun setMonitoring(id: Int, enabled: Boolean)
    fun getMonitoring(): ArtefactoLocal?
    fun getMonitoringArtifacts(): List<ArtefactoLocal>
    fun simulateMonitoringSample(id: Int): Int
    fun simulateLiveReading(id: Int, intervalSeconds: Int = 3): MonitoreoLecturaActual?
    fun purgeExpiredRetiredArtifacts()
    fun retentionExpiryMillis(artifact: ArtefactoLocal): Long?
    fun retentionRemainingDays(artifact: ArtefactoLocal): Int?

    fun getConsumptions(): List<ConsumoLocal>
    fun appendConsumption(artifactId: Int, kwh: Double)
    fun appendSimulatedConsumption(artifactId: Int, kwh: Double, bucket: Long)
    fun consumptionsForPeriod(period: String): List<ConsumoLocal>
    fun consumptionsBetween(bounds: PeriodBounds): List<ConsumoLocal>
    fun totalKwh(period: String): Double
    fun totalKwh(bounds: PeriodBounds): Double
    fun artifactKwh(id: Int, period: String): Double
    fun consumptionSummaries(period: String): List<ConsumoArtefactoResumen>

    fun periodBounds(period: String, previous: Boolean = false): PeriodBounds
    fun comparison(period: String): PeriodComparison
    fun trendData(period: String): TrendData
    fun trendDataForArtifact(artifactId: Int, period: String): TrendData
    fun compareArtifacts(artifactAId: Int, artifactBId: Int, period: String): ArtifactComparison?

    fun tariff(): TarifaInfo
    fun energyCost(period: String): Double
    fun fixedCost(period: String): Double
    fun totalCost(period: String): Double

    fun getMeta(): MetaLocal?
    fun saveMeta(meta: MetaLocal)
    fun deleteMeta()
    fun goalProgress(): Int

    fun currentChileDateTime(): String
    fun formatChileDate(millis: Long): String
    fun formatKwh(value: Double): String
    fun formatClp(value: Double): String
    fun formatVariation(value: Double?): String
    fun periodLabel(period: String): String
    fun reportText(period: String): String
}

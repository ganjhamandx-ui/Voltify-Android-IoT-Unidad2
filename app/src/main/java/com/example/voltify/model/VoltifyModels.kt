package com.example.voltify.model

data class UsuarioLocal(
    val nombre: String,
    val correo: String,
    val telefono: String,
    val comuna: String,
    val empresa: String,
    val usarTarifaManual: Boolean,
    val valorKwhManual: Double?,
    val cargoFijoManual: Double?
)

enum class AuthMode { LOCAL_SEGURO, FIREBASE }

enum class AuthStatus {
    SUCCESS,
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    EMAIL_NOT_VERIFIED,
    EMAIL_ALREADY_USED,
    PASSWORD_INVALID,
    RESET_EMAIL_SENT,
    RECOVERY_CODE_GENERATED,
    RECOVERY_RATE_LIMITED,
    CONFIGURATION_REQUIRED,
    NETWORK_ERROR,
    ERROR
}

data class AuthOutcome(
    val status: AuthStatus,
    val message: String = "",
    val retryAfterSeconds: Int = 0
) {
    val success: Boolean get() = status == AuthStatus.SUCCESS
}

enum class TipoFuenteMonitoreo {
    SIMULADA,
    IOT_REAL
}

data class ArtefactoLocal(
    val id: Int,
    val nombre: String,
    val categoria: String,
    val marca: String,
    val modelo: String,
    val potenciaW: Double,
    val activo: Boolean,
    val monitoreando: Boolean,
    val fechaRegistro: Long,
    val fechaRetiro: Long?,
    val tipoFuente: TipoFuenteMonitoreo = TipoFuenteMonitoreo.SIMULADA
)

data class ConsumoLocal(
    val id: Int,
    val artefactoId: Int,
    val artefactoNombre: String,
    val artefactoCategoria: String,
    val artefactoMarca: String,
    val artefactoModelo: String,
    val consumoKwh: Double,
    val fecha: Long,
    val tipoFuente: TipoFuenteMonitoreo = TipoFuenteMonitoreo.SIMULADA
)

/** Lectura visible del monitoreo simulado que se actualiza periódicamente. */
data class MonitoreoLecturaActual(
    val artefactoId: Int,
    val potenciaW: Double,
    val consumoIncrementalKwh: Double,
    val timestamp: Long
)

enum class EstadoArtefacto {
    ACTIVO,
    RETIRADO,
    ELIMINADO
}

data class ConsumoArtefactoResumen(
    val artefactoId: Int,
    val nombre: String,
    val categoria: String,
    val marca: String,
    val modelo: String,
    val estado: EstadoArtefacto,
    val fechaRetiro: Long?,
    val kwh: Double,
    val tipoFuente: TipoFuenteMonitoreo
)

data class MetaLocal(
    val consumoKwh: Double?,
    val costoClp: Double?
)

data class TarifaInfo(
    val disponible: Boolean,
    val fuente: String,
    val valorKwh: Double,
    val cargoFijo: Double
)

data class PeriodBounds(
    val start: Long,
    val endExclusive: Long,
    val label: String
)

data class PeriodComparison(
    val currentLabel: String,
    val previousLabel: String,
    val comparisonText: String,
    val currentKwh: Double,
    val previousKwh: Double,
    val currentCost: Double,
    val previousCost: Double,
    val consumptionChangePct: Double?,
    val costChangePct: Double?
)

data class TrendData(
    val labels: List<String>,
    val values: List<Double>
)

data class ArtifactComparison(
    val artifactA: ArtefactoLocal,
    val artifactB: ArtefactoLocal,
    val trendA: TrendData,
    val trendB: TrendData,
    val kwhA: Double,
    val kwhB: Double,
    val energyCostA: Double,
    val energyCostB: Double,
    val differenceKwh: Double,
    val differencePctFromB: Double?
)

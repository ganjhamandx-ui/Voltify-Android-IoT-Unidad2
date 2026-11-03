package com.example.voltify.data

import android.content.Context
import android.util.Log
import com.example.voltify.data.local.PasswordSecurity
import com.example.voltify.data.local.VoltifyLocalDataSource
import com.example.voltify.data.remote.FirebaseGateway
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.AuthOutcome
import com.example.voltify.model.AuthStatus
import com.example.voltify.model.ConsumoLocal
import com.example.voltify.model.MetaLocal
import com.example.voltify.model.MonitoreoLecturaActual
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.model.UsuarioLocal
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repositorio final de Voltify.
 * - Sin Firebase configurado: modo local seguro para simulación formativa.
 * - Con Firebase configurado: Authentication + RTDB por UID y caché local cifrada.
 */
class HybridVoltifyRepository(
    context: Context,
    private val local: FakeVoltifyRepository = FakeVoltifyRepository(context)
) : VoltifyRepository by local {
    private val appContext = context.applicationContext
    private val gateway = FirebaseGateway(appContext)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cloudMutex = Mutex()
    @Volatile private var artifactWritePending = 0
    @Volatile private var consumptionWritePending = 0
    @Volatile private var metaWritePending = 0
    private var listeningUid: String? = null
    private var artifactsListener: ValueEventListener? = null
    private var consumptionsListener: ValueEventListener? = null
    private var metaListener: ValueEventListener? = null

    private fun stopCloudListener() {
        val uid = listeningUid
        val listener = artifactsListener
        if (uid != null && listener != null) gateway.stopObservingArtifacts(uid, listener)
        if (uid != null) {
            consumptionsListener?.let { gateway.stopObservingConsumptions(uid, it) }
            metaListener?.let { gateway.stopObservingMeta(uid, it) }
        }
        listeningUid = null
        artifactsListener = null
        consumptionsListener = null
        metaListener = null
    }

    private fun startCloudListener(uid: String) {
        stopCloudListener()
        listeningUid = uid
        artifactsListener = gateway.observeArtifacts(uid, { artifacts ->
            if (gateway.currentUid() == uid && artifactWritePending == 0) {
                ioScope.launch {
                    cloudMutex.withLock {
                        if (gateway.currentUid() == uid && artifactWritePending == 0) {
                            VoltifyLocalDataSource.replaceRemoteArtifacts(appContext, artifacts)
                            local.refresh()
                        }
                    }
                }
            }
        }, { error -> Log.e("VoltifySync", "Listener artifacts: ${error.message}") })
        consumptionsListener = gateway.observeConsumptions(uid, { rows ->
            if (gateway.currentUid() == uid && consumptionWritePending == 0) {
                ioScope.launch {
                    cloudMutex.withLock {
                        if (gateway.currentUid() == uid && consumptionWritePending == 0 &&
                            VoltifyLocalDataSource.hasActiveSession(appContext)) {
                            VoltifyLocalDataSource.replaceRemoteConsumptions(appContext, rows)
                            local.refresh()
                        }
                    }
                }
            }
        }, { error -> Log.e("VoltifySync", "Listener consumptions: ${error.message}") })
        metaListener = gateway.observeMeta(uid, { meta ->
            if (gateway.currentUid() == uid && metaWritePending == 0) {
                ioScope.launch {
                    cloudMutex.withLock {
                        if (gateway.currentUid() == uid && metaWritePending == 0 &&
                            VoltifyLocalDataSource.hasActiveSession(appContext)) {
                            VoltifyLocalDataSource.replaceRemoteMeta(appContext, meta)
                            local.refresh()
                        }
                    }
                }
            }
        }, { error -> Log.e("VoltifySync", "Listener meta: ${error.message}") })
    }

    override fun refresh() {
        local.refresh()
        if (gateway.isConfigured() && VoltifyLocalDataSource.hasActiveSession(appContext)) {
            val uid = gateway.currentUid()
            if (uid != null && listeningUid != uid) startCloudListener(uid)
        } else if (listeningUid != null) {
            stopCloudListener()
        }
    }

    override fun isCloudConfigured(): Boolean = gateway.isConfigured()
    override fun authModeLabel(): String =
        if (gateway.isConfigured()) "FIREBASE AUTH + RTDB" else "LOCAL SEGURO · simulación"

    override suspend fun login(correo: String, password: String): AuthOutcome {
        val email = correo.trim().lowercase()
        if (!gateway.isConfigured()) return local.login(email, password)

        // La complejidad se valida en registro y cambio de contraseña.
        // En el login, Firebase comprueba las credenciales existentes sin imponer una política retroactiva.
        return try {
            val uid = gateway.signInVerified(email, password)
            val remote = gateway.download(uid)
            val profile = remote.profile ?: VoltifyLocalDataSource.getProfileByEmail(appContext, email)
                ?: UsuarioLocal("Usuario Voltify", email, "", "Santiago", "Enel Distribución", false, null, null)

            VoltifyLocalDataSource.replaceRemoteProfile(appContext, profile)
            VoltifyLocalDataSource.replaceCurrentDataFromRemote(
                appContext,
                remote.artifacts,
                remote.consumptions,
                remote.meta
            )
            // Una cuenta administrada por Firebase no conserva un verificador local de contraseña.
            VoltifyLocalDataSource.removeLocalCredential(appContext, email)
            local.refresh()

            if (remote.profile == null) {
                gateway.uploadProfile(uid, profile)
            }
            startCloudListener(uid)
            AuthOutcome(AuthStatus.SUCCESS)
        } catch (_: FirebaseGateway.EmailNotVerifiedException) {
            AuthOutcome(AuthStatus.EMAIL_NOT_VERIFIED, "Verifica tu correo antes de ingresar")
        } catch (_: FirebaseAuthInvalidUserException) {
            migrateLocalAccountIfPossible(email, password)
        } catch (_: FirebaseAuthInvalidCredentialsException) {
            migrateLocalAccountIfPossible(email, password)
        } catch (_: FirebaseNetworkException) {
            AuthOutcome(AuthStatus.NETWORK_ERROR, "No fue posible conectar con Firebase")
        } catch (e: Exception) {
            Log.e("VoltifySync", "Fallo en login o descarga Firebase", e)
            AuthOutcome(AuthStatus.ERROR, "No fue posible cargar los datos desde Firebase. Revisa la conexión y permisos")
        }
    }

    private suspend fun migrateLocalAccountIfPossible(email: String, password: String): AuthOutcome {
        if (!VoltifyLocalDataSource.hasLocalAccount(appContext, email)) {
            return AuthOutcome(AuthStatus.INVALID_CREDENTIALS)
        }
        val localResult = local.login(email, password)
        if (!localResult.success) return localResult

        val profile = local.getUser()
        val validation = PasswordSecurity.validate(password, email, profile.nombre)
        if (!validation.valid) {
            local.logout()
            return AuthOutcome(
                AuthStatus.PASSWORD_INVALID,
                "La cuenta local existe, pero su contraseña antigua no cumple la política segura. Restablécela antes de migrar a Firebase."
            )
        }

        return try {
            gateway.createAccount(profile, password)
            local.logout()
            AuthOutcome(AuthStatus.EMAIL_NOT_VERIFIED, "Cuenta migrada. Verifica el correo para activar Firebase")
        } catch (_: FirebaseAuthUserCollisionException) {
            local.logout()
            AuthOutcome(AuthStatus.INVALID_CREDENTIALS)
        } catch (_: FirebaseNetworkException) {
            local.logout()
            AuthOutcome(AuthStatus.NETWORK_ERROR, "No fue posible migrar la cuenta a Firebase")
        } catch (_: Exception) {
            local.logout()
            AuthOutcome(AuthStatus.ERROR, "No fue posible migrar la cuenta")
        }
    }

    override suspend fun register(user: UsuarioLocal, password: String): AuthOutcome {
        val email = user.correo.trim().lowercase()
        val cleanUser = user.copy(correo = email)
        val validation = PasswordSecurity.validate(password, email, user.nombre)
        if (!validation.valid) return AuthOutcome(AuthStatus.PASSWORD_INVALID, validation.message)

        if (!gateway.isConfigured()) return local.register(cleanUser, password)

        return try {
            gateway.createAccount(cleanUser, password)
            // Se conserva únicamente el perfil pendiente cifrado; no la contraseña.
            VoltifyLocalDataSource.replaceRemoteProfile(appContext, cleanUser)
            VoltifyLocalDataSource.removeLocalCredential(appContext, email)
            VoltifyLocalDataSource.logout(appContext)
            local.refresh()
            AuthOutcome(AuthStatus.EMAIL_NOT_VERIFIED, "Cuenta creada. Verifica el correo antes de iniciar sesión")
        } catch (_: FirebaseAuthUserCollisionException) {
            AuthOutcome(AuthStatus.EMAIL_ALREADY_USED)
        } catch (_: FirebaseNetworkException) {
            AuthOutcome(AuthStatus.NETWORK_ERROR, "Sin conexión con Firebase")
        } catch (e: Exception) {
            AuthOutcome(AuthStatus.ERROR, e.localizedMessage ?: "No fue posible crear la cuenta")
        }
    }

    override suspend fun beginRecovery(email: String): AuthOutcome {
        if (!gateway.isConfigured()) return local.beginRecovery(email)
        return try {
            gateway.sendPasswordReset(email.trim().lowercase())
            // Mensaje genérico para no revelar si el correo está registrado.
            AuthOutcome(AuthStatus.RESET_EMAIL_SENT, "Si la cuenta existe, recibirás instrucciones de recuperación")
        } catch (_: FirebaseNetworkException) {
            AuthOutcome(AuthStatus.NETWORK_ERROR, "No fue posible conectar con Firebase")
        } catch (_: Exception) {
            AuthOutcome(AuthStatus.RESET_EMAIL_SENT, "Si la cuenta existe, recibirás instrucciones de recuperación")
        }
    }

    override suspend fun finishRecovery(newPassword: String): AuthOutcome = local.finishRecovery(newPassword)

    override fun logout() {
        stopCloudListener()
        gateway.signOut()
        local.logout()
    }

    override fun saveUser(user: UsuarioLocal) {
        local.saveUser(user)
        syncProfile()
    }

    override fun saveArtifact(
        id: Int?,
        nombre: String,
        categoria: String,
        marca: String,
        modelo: String,
        potenciaW: Double,
        tipoFuente: TipoFuenteMonitoreo
    ): ArtefactoLocal = local.saveArtifact(id, nombre, categoria, marca, modelo, potenciaW, tipoFuente).also { artifact ->
        syncArtifact(artifact)
    }

    override fun retireArtifact(id: Int) {
        local.retireArtifact(id)
        local.getArtifact(id)?.let(::syncArtifact)
    }

    override fun deleteArtifactPermanently(id: Int) {
        local.deleteArtifactPermanently(id)
        withArtifactWrite { uid -> gateway.deleteArtifact(uid, id) }
    }

    override fun setMonitoring(id: Int, enabled: Boolean) {
        local.setMonitoring(id, enabled)
        local.getArtifact(id)?.let(::syncArtifact)
    }

    override fun simulateMonitoringSample(id: Int): Int {
        val before = local.getConsumptions().map { it.id }.toSet()
        val count = local.simulateMonitoringSample(id)
        local.getConsumptions().filter { it.id !in before }.forEach(::syncConsumption)
        return count
    }

    override fun simulateLiveReading(id: Int, intervalSeconds: Int): MonitoreoLecturaActual? =
        local.simulateLiveReading(id, intervalSeconds)

    override fun appendConsumption(artifactId: Int, kwh: Double) {
        val previous = local.getConsumptions().associateBy { it.id }
        local.appendConsumption(artifactId, kwh)
        local.getConsumptions().firstOrNull { previous[it.id] != it }?.let(::syncConsumption)
    }

    override fun appendSimulatedConsumption(artifactId: Int, kwh: Double, bucket: Long) {
        val previous = local.getConsumptions().associateBy { it.id }
        local.appendSimulatedConsumption(artifactId, kwh, bucket)
        local.getConsumptions().firstOrNull { previous[it.id] != it }?.let(::syncConsumption)
    }

    override fun registerReading(artifactId: Int, kwh: Double, timestamp: Long) {
        val previous = local.getConsumptions().associateBy { it.id }
        local.registerReading(artifactId, kwh, timestamp)
        local.getConsumptions().firstOrNull { previous[it.id] != it }?.let(::syncConsumption)
    }

    override fun purgeExpiredRetiredArtifacts() {
        val before = local.getArtifacts(true).map { it.id }.toSet()
        local.purgeExpiredRetiredArtifacts()
        val after = local.getArtifacts(true).map { it.id }.toSet()
        (before - after).forEach { id ->
            withArtifactWrite { uid -> gateway.deleteArtifact(uid, id) }
        }
    }

    override fun saveMeta(meta: MetaLocal) {
        local.saveMeta(meta)
        syncMeta()
    }

    override fun deleteMeta() {
        local.deleteMeta()
        syncMeta()
    }

    private fun syncProfile() = withUid { uid -> gateway.uploadProfile(uid, local.getUser()) }
    private fun syncArtifact(artifact: ArtefactoLocal) = withArtifactWrite { uid -> gateway.uploadArtifact(uid, artifact) }
    private fun withArtifactWrite(block: suspend (String) -> Unit) {
        if (!gateway.isConfigured()) return
        val uid = gateway.currentUid() ?: return
        synchronized(this) { artifactWritePending++ }
        ioScope.launch {
            try {
                cloudMutex.withLock { block(uid) }
            } catch (e: Exception) {
                Log.e("VoltifySync", "No se pudo sincronizar artefacto", e)
            } finally {
                val last = synchronized(this@HybridVoltifyRepository) {
                    artifactWritePending--
                    artifactWritePending == 0
                }
                if (last && gateway.currentUid() == uid) {
                    try {
                        cloudMutex.withLock {
                            val remote = gateway.download(uid)
                            // Nunca transformar un fallo de lectura en una lista vacía.
                            if (artifactWritePending == 0 && gateway.currentUid() == uid) {
                                VoltifyLocalDataSource.replaceRemoteArtifacts(appContext, remote.artifacts)
                                local.refresh()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VoltifySync", "No fue posible reconciliar artefactos", e)
                    }
                }
            }
        }
    }
    private fun syncMeta() {
        if (!gateway.isConfigured()) return
        val uid = gateway.currentUid() ?: return
        val value = local.getMeta()
        synchronized(this) { metaWritePending++ }
        ioScope.launch {
            try {
                cloudMutex.withLock { gateway.uploadMeta(uid, value) }
            } catch (e: Exception) {
                Log.e("VoltifySync", "Error al sincronizar meta", e)
            } finally {
                val last = synchronized(this@HybridVoltifyRepository) {
                    metaWritePending--
                    metaWritePending == 0
                }
                if (last && gateway.currentUid() == uid) {
                    try {
                        cloudMutex.withLock {
                            val remote = gateway.download(uid)
                            if (metaWritePending == 0 && gateway.currentUid() == uid) {
                                VoltifyLocalDataSource.replaceRemoteMeta(appContext, remote.meta)
                                local.refresh()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VoltifySync", "Error al reconciliar meta", e)
                    }
                }
            }
        }
    }

    private fun syncConsumption(row: ConsumoLocal) {
        if (!gateway.isConfigured()) return
        val uid = gateway.currentUid() ?: return
        synchronized(this) { consumptionWritePending++ }
        ioScope.launch {
            try {
                cloudMutex.withLock { gateway.uploadConsumption(uid, row) }
            } catch (e: Exception) {
                Log.e("VoltifySync", "Error al subir consumo ${row.id}", e)
            } finally {
                val last = synchronized(this@HybridVoltifyRepository) {
                    consumptionWritePending--
                    consumptionWritePending == 0
                }
                if (last && gateway.currentUid() == uid) {
                    try {
                        cloudMutex.withLock {
                            val remote = gateway.download(uid)
                            if (consumptionWritePending == 0 && gateway.currentUid() == uid) {
                                VoltifyLocalDataSource.replaceRemoteConsumptions(appContext, remote.consumptions)
                                local.refresh()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VoltifySync", "Error al reconciliar consumos", e)
                    }
                }
            }
        }
    }

    private fun withUid(block: suspend (String) -> Unit) {
        if (!gateway.isConfigured()) return
        val uid = gateway.currentUid() ?: return
        ioScope.launch {
            try { block(uid) }
            catch (e: Exception) { Log.e("VoltifySync", "Fallo al sincronizar con Firebase", e) }
        }
    }
}

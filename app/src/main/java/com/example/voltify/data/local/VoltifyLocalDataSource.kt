package com.example.voltify.data.local

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Base64
import android.util.Log
import com.example.voltify.PERIODO_HOY
import com.example.voltify.PERIODO_MES
import com.example.voltify.PERIODO_SEMANA
import com.example.voltify.PERIODO_TODOS
import com.example.voltify.PERIODO_TRES_MESES
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.ArtifactComparison
import com.example.voltify.model.AuthOutcome
import com.example.voltify.model.AuthStatus
import com.example.voltify.model.ConsumoLocal
import com.example.voltify.model.ConsumoArtefactoResumen
import com.example.voltify.model.EstadoArtefacto
import com.example.voltify.model.MetaLocal
import com.example.voltify.model.MonitoreoLecturaActual
import com.example.voltify.model.PeriodBounds
import com.example.voltify.model.PeriodComparison
import com.example.voltify.model.TarifaInfo
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.model.TrendData
import com.example.voltify.model.UsuarioLocal
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

private const val DIAS_RETENCION_ARTEFACTOS = 90
private const val DIA_MS = 86_400_000L
private val TZ_CHILE: TimeZone = TimeZone.getTimeZone("America/Santiago")

object VoltifyLocalDataSource {
    private const val PREFS = "voltify_local_store"

    // Claves antiguas conservadas exclusivamente para migrar instalaciones previas.
    private const val OLD_K_USER = "user"
    private const val OLD_K_USERS = "users_v2"
    private const val OLD_K_ACTIVE_EMAIL = "active_email"

    private const val K_USERS = "users_v3_secure"
    private const val K_CREDENTIALS = "credentials_v3_secure"
    private const val K_ACTIVE_EMAIL = "active_email_v3_secure"
    private const val K_SECURITY_MIGRATED = "security_migrated_v3"

    private const val K_ARTIFACTS = "artifacts"
    private const val K_CONSUMOS = "consumos"
    private const val K_META = "meta"
    private const val K_LOGGED = "logged"
    private const val K_SESSION_STARTED = "session_started"
    private const val K_RECOVERY_STATE = "recovery_state_secure"
    private const val K_RECOVERY_COOLDOWN = "recovery_cooldown"
    private const val K_DATA_VERSION = "data_version"
    private const val DATA_VERSION = 8
    private const val SESSION_MAX_MS = 8L * 60L * 60L * 1000L
    private const val RECOVERY_EXPIRY_MS = 10L * 60L * 1000L
    private const val RECOVERY_MAX_ATTEMPTS = 5
    private const val RECOVERY_REQUEST_COOLDOWN_MS = 60L * 1000L
    private const val RECOVERY_LOG_TAG = "VoltifyRecoveryAdmin"

    // Acumulación propia de este dispositivo: nunca sumar el historial remoto del otro Android.
    private val localSimulationContributions = mutableMapOf<String, Double>()

    val comunas = listOf("Santiago", "Estación Central", "Maipú", "Quinta Normal", "Providencia")
    val empresas = listOf("Enel Distribución", "CGE", "Chilquinta", "Otra")
    val categorias = listOf("Climatización", "Refrigeración", "Entretenimiento", "Lavado", "Cocina", "Iluminación", "Otros")

    private data class LocalCredential(
        val email: String,
        val algorithm: String,
        val iterations: Int,
        val saltBase64: String,
        val hashBase64: String,
        val failedAttempts: Int = 0,
        val lockUntil: Long = 0L
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        val local = email.substring(0, at)
        val domain = email.substring(at)
        val prefix = local.take(2)
        return "$prefix***$domain"
    }

    private fun profileId(email: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalizeEmail(email).toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun scopedKey(base: String, email: String): String = "${base}_${profileId(email)}"

    private fun secureRead(context: Context, key: String, default: String? = null): String? {
        val stored = prefs(context).getString(key, null) ?: return default
        return SecureLocalCipher.decrypt(stored) ?: default
    }

    private fun secureWrite(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, SecureLocalCipher.encrypt(value)).apply()
    }

    private fun secureRemove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    private fun userToJson(u: UsuarioLocal): JSONObject = JSONObject()
        .put("nombre", u.nombre)
        .put("correo", normalizeEmail(u.correo))
        .put("telefono", u.telefono)
        .put("comuna", u.comuna)
        .put("empresa", u.empresa)
        .put("usarTarifaManual", u.usarTarifaManual)
        .put("valorKwhManual", u.valorKwhManual ?: JSONObject.NULL)
        .put("cargoFijoManual", u.cargoFijoManual ?: JSONObject.NULL)

    private fun userFromJson(o: JSONObject): UsuarioLocal = UsuarioLocal(
        nombre = o.optString("nombre"),
        correo = normalizeEmail(o.optString("correo")),
        telefono = o.optString("telefono"),
        comuna = o.optString("comuna"),
        empresa = o.optString("empresa"),
        usarTarifaManual = o.optBoolean("usarTarifaManual", false),
        valorKwhManual = if (o.has("valorKwhManual") && !o.isNull("valorKwhManual")) o.optDouble("valorKwhManual") else null,
        cargoFijoManual = if (o.has("cargoFijoManual") && !o.isNull("cargoFijoManual")) o.optDouble("cargoFijoManual") else null
    )

    private fun readUsers(context: Context): MutableList<UsuarioLocal> {
        val raw = secureRead(context, K_USERS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return MutableList(arr.length()) { index -> userFromJson(arr.getJSONObject(index)) }
    }

    private fun saveUsers(context: Context, users: List<UsuarioLocal>) {
        val arr = JSONArray()
        users.forEach { arr.put(userToJson(it)) }
        secureWrite(context, K_USERS, arr.toString())
    }

    private fun credentialToJson(c: LocalCredential): JSONObject = JSONObject()
        .put("email", normalizeEmail(c.email))
        .put("algorithm", c.algorithm)
        .put("iterations", c.iterations)
        .put("salt", c.saltBase64)
        .put("hash", c.hashBase64)
        .put("failedAttempts", c.failedAttempts)
        .put("lockUntil", c.lockUntil)

    private fun credentialFromJson(o: JSONObject): LocalCredential = LocalCredential(
        email = normalizeEmail(o.optString("email")),
        algorithm = o.optString("algorithm", "PBKDF2WithHmacSHA256"),
        iterations = o.optInt("iterations", 210_000),
        saltBase64 = o.optString("salt"),
        hashBase64 = o.optString("hash"),
        failedAttempts = o.optInt("failedAttempts", 0),
        lockUntil = o.optLong("lockUntil", 0L)
    )

    private fun readCredentials(context: Context): MutableList<LocalCredential> {
        val raw = secureRead(context, K_CREDENTIALS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return MutableList(arr.length()) { index -> credentialFromJson(arr.getJSONObject(index)) }
    }

    private fun saveCredentials(context: Context, credentials: List<LocalCredential>) {
        val arr = JSONArray()
        credentials.forEach { arr.put(credentialToJson(it)) }
        secureWrite(context, K_CREDENTIALS, arr.toString())
    }

    private fun hashCredential(email: String, password: String): LocalCredential {
        val h = PasswordSecurity.createHash(password)
        return LocalCredential(
            email = normalizeEmail(email),
            algorithm = h.algorithm,
            iterations = h.iterations,
            saltBase64 = h.saltBase64,
            hashBase64 = h.hashBase64
        )
    }

    private fun verifyCredential(password: String, credential: LocalCredential): Boolean =
        PasswordSecurity.verify(
            password,
            PasswordSecurity.HashRecord(
                algorithm = credential.algorithm,
                iterations = credential.iterations,
                saltBase64 = credential.saltBase64,
                hashBase64 = credential.hashBase64,
                failedAttempts = credential.failedAttempts,
                lockUntil = credential.lockUntil
            )
        )

    private fun oldProfileId(email: String): String = profileId(email)

    private fun migrateSecurity(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(K_SECURITY_MIGRATED, false)) return

        val profiles = mutableListOf<UsuarioLocal>()
        val credentials = mutableListOf<LocalCredential>()

        fun importOldObject(o: JSONObject) {
            val email = normalizeEmail(o.optString("correo"))
            if (email.isBlank() || profiles.any { it.correo == email }) return
            val profile = UsuarioLocal(
                nombre = o.optString("nombre"),
                correo = email,
                telefono = o.optString("telefono"),
                comuna = o.optString("comuna"),
                empresa = o.optString("empresa"),
                usarTarifaManual = o.optBoolean("usarTarifaManual", false),
                valorKwhManual = if (o.has("valorKwhManual") && !o.isNull("valorKwhManual")) o.optDouble("valorKwhManual") else null,
                cargoFijoManual = if (o.has("cargoFijoManual") && !o.isNull("cargoFijoManual")) o.optDouble("cargoFijoManual") else null
            )
            profiles += profile
            val oldPassword = o.optString("contrasena")
            if (oldPassword.isNotBlank()) credentials += hashCredential(email, oldPassword)

            listOf(K_ARTIFACTS, K_CONSUMOS, K_META).forEach { base ->
                val oldKey = scopedKey(base, email)
                p.getString(oldKey, null)?.let { raw ->
                    secureWrite(context, oldKey, SecureLocalCipher.decrypt(raw) ?: raw)
                }
            }
        }

        p.getString(OLD_K_USERS, null)?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { arr ->
                for (i in 0 until arr.length()) importOldObject(arr.getJSONObject(i))
            }
        }
        p.getString(OLD_K_USER, null)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let(::importOldObject)
        }

        if (profiles.isNotEmpty()) {
            saveUsers(context, profiles)
            saveCredentials(context, credentials)
            val oldActive = normalizeEmail(p.getString(OLD_K_ACTIVE_EMAIL, "").orEmpty())
            val chosen = profiles.firstOrNull { it.correo == oldActive }?.correo ?: profiles.first().correo
            secureWrite(context, K_ACTIVE_EMAIL, chosen)
        }

        // No se crea cuenta demo automática: una instalación limpia debe comenzar sin datos de usuario.
        p.edit()
            .remove(OLD_K_USER)
            .remove(OLD_K_USERS)
            .remove(OLD_K_ACTIVE_EMAIL)
            .putBoolean(K_SECURITY_MIGRATED, true)
            .apply()
    }

    private fun activeEmail(context: Context): String = normalizeEmail(secureRead(context, K_ACTIVE_EMAIL, "").orEmpty())

    private fun currentScopedKey(context: Context, base: String): String {
        val email = activeEmail(context)
        check(email.isNotBlank()) { "No hay una cuenta activa en Voltify" }
        return scopedKey(base, email)
    }

    private fun initializeEmptyProfile(context: Context, email: String) {
        secureWrite(context, scopedKey(K_ARTIFACTS, email), "[]")
        secureWrite(context, scopedKey(K_CONSUMOS, email), "[]")
        secureRemove(context, scopedKey(K_META, email))
        prefs(context).edit().putInt(scopedKey(K_DATA_VERSION, email), DATA_VERSION).apply()
    }

    private fun ensureActiveProfileVersion(context: Context) {
        val email = activeEmail(context)
        if (email.isBlank()) return
        val p = prefs(context)
        val versionKey = scopedKey(K_DATA_VERSION, email)
        val version = p.getInt(versionKey, 0)
        if (version == 0) {
            initializeEmptyProfile(context, email)
            return
        }
        if (version < DATA_VERSION) {
            val artifacts = readArtifactsRaw(context)
            val consumptions = readConsumosRaw(context)
            saveConsumosRaw(context, withArtifactSnapshots(consumptions, artifacts))
            p.edit().putInt(versionKey, DATA_VERSION).apply()
        }
    }

    fun ensureSeed(context: Context) {
        migrateSecurity(context)
        if (hasActiveSession(context)) {
            ensureActiveProfileVersion(context)
            purgeExpiredRetiredArtifacts(context)
        }
    }

    fun hasActiveSession(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(K_LOGGED, false)) return false
        val started = p.getLong(K_SESSION_STARTED, 0L)
        if (started <= 0L || System.currentTimeMillis() - started > SESSION_MAX_MS) {
            logout(context)
            return false
        }
        val email = activeEmail(context)
        return email.isNotBlank() && readUsers(context).any { it.correo == email }
    }

    fun hasLocalAccount(context: Context, email: String): Boolean {
        migrateSecurity(context)
        val normalized = normalizeEmail(email)
        return readUsers(context).any { it.correo == normalized } && readCredentials(context).any { it.email == normalized }
    }

    fun getUserOrNull(context: Context): UsuarioLocal? {
        migrateSecurity(context)
        if (!hasActiveSession(context)) return null
        val email = activeEmail(context)
        return readUsers(context).firstOrNull { it.correo == email }
    }

    fun getUser(context: Context): UsuarioLocal = getUserOrNull(context)
        ?: error("No existe una sesión autenticada")

    fun saveUser(context: Context, u: UsuarioLocal) {
        ensureSeed(context)
        val active = activeEmail(context)
        if (active.isBlank()) return

        // El correo identifica la cuenta y no se modifica desde Configuración sin reautenticación.
        val sanitized = u.copy(correo = active)
        val users = readUsers(context)
        val index = users.indexOfFirst { it.correo == active }
        if (index >= 0) {
            users[index] = sanitized
            saveUsers(context, users)
        }
    }

    fun login(context: Context, correo: String, password: String): AuthOutcome {
        migrateSecurity(context)
        val email = normalizeEmail(correo)
        val credentials = readCredentials(context)
        val index = credentials.indexOfFirst { it.email == email }
        if (index < 0) return AuthOutcome(AuthStatus.INVALID_CREDENTIALS)

        val credential = credentials[index]
        val now = System.currentTimeMillis()
        if (credential.lockUntil > now) {
            val seconds = ((credential.lockUntil - now + 999) / 1000).toInt()
            return AuthOutcome(AuthStatus.ACCOUNT_LOCKED, retryAfterSeconds = seconds)
        }

        if (!verifyCredential(password, credential)) {
            val failures = credential.failedAttempts + 1
            val lockMillis = if (failures >= 5) minOf(15 * 60_000L, 30_000L * (1 shl minOf(failures - 5, 5)).toLong()) else 0L
            credentials[index] = credential.copy(
                failedAttempts = failures,
                lockUntil = if (lockMillis > 0) now + lockMillis else 0L
            )
            saveCredentials(context, credentials)
            return AuthOutcome(AuthStatus.INVALID_CREDENTIALS)
        }

        // Una contraseña previamente registrada no se bloquea por cambios posteriores de política.
        // La complejidad se exige al crear o actualizar la contraseña, no al autenticar.
        credentials[index] = credential.copy(failedAttempts = 0, lockUntil = 0L)
        saveCredentials(context, credentials)
        secureWrite(context, K_ACTIVE_EMAIL, email)
        prefs(context).edit()
            .putBoolean(K_LOGGED, true)
            .putLong(K_SESSION_STARTED, now)
            .apply()
        ensureActiveProfileVersion(context)
        return AuthOutcome(AuthStatus.SUCCESS)
    }

    fun logout(context: Context) {
        prefs(context).edit()
            .putBoolean(K_LOGGED, false)
            .remove(K_SESSION_STARTED)
            .apply()
    }

    fun register(context: Context, user: UsuarioLocal, password: String): AuthOutcome {
        migrateSecurity(context)
        val email = normalizeEmail(user.correo)
        if (email.isBlank()) return AuthOutcome(AuthStatus.ERROR, "Correo inválido")
        val validation = PasswordSecurity.validate(password, email, user.nombre)
        if (!validation.valid) return AuthOutcome(AuthStatus.PASSWORD_INVALID, validation.message)

        val users = readUsers(context)
        if (users.any { it.correo == email }) return AuthOutcome(AuthStatus.EMAIL_ALREADY_USED)

        users += user.copy(correo = email)
        saveUsers(context, users)
        val credentials = readCredentials(context)
        credentials += hashCredential(email, password)
        saveCredentials(context, credentials)
        secureWrite(context, K_ACTIVE_EMAIL, email)
        prefs(context).edit()
            .putBoolean(K_LOGGED, true)
            .putLong(K_SESSION_STARTED, System.currentTimeMillis())
            .apply()
        initializeEmptyProfile(context, email)
        return AuthOutcome(AuthStatus.SUCCESS)
    }

    fun beginRecovery(context: Context, email: String): AuthOutcome {
        migrateSecurity(context)
        val normalized = normalizeEmail(email)
        val now = System.currentTimeMillis()
        val cooldownKey = scopedKey(K_RECOVERY_COOLDOWN, normalized)
        val lastRequest = prefs(context).getLong(cooldownKey, 0L)
        val elapsed = now - lastRequest
        if (lastRequest > 0L && elapsed in 0 until RECOVERY_REQUEST_COOLDOWN_MS) {
            val remaining = ((RECOVERY_REQUEST_COOLDOWN_MS - elapsed + 999L) / 1000L).toInt()
            return AuthOutcome(
                status = AuthStatus.RECOVERY_RATE_LIMITED,
                message = "Solicitud temporalmente limitada",
                retryAfterSeconds = remaining
            )
        }
        prefs(context).edit().putLong(cooldownKey, now).apply()

        val exists = readCredentials(context).any { it.email == normalized }
        val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(salt + code.toByteArray())
        val state = JSONObject()
            .put("email", if (exists) normalized else "")
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("hash", Base64.encodeToString(digest, Base64.NO_WRAP))
            .put("expires", now + RECOVERY_EXPIRY_MS)
            .put("attempts", 0)
            .put("verified", false)
        secureWrite(context, K_RECOVERY_STATE, state.toString())

        // Solo el desarrollador con acceso ADB puede ver el código durante la simulación local.
        // En una build release sin Firebase no se expone ningún código.
        if (exists && isDebuggable(context)) {
            Log.i(
                RECOVERY_LOG_TAG,
                "Código temporal para ${maskEmail(normalized)}: $code · expira en 10 min · máximo 5 intentos"
            )
        }

        return AuthOutcome(
            status = AuthStatus.RECOVERY_CODE_GENERATED,
            message = "Si la cuenta existe, se generó un código de recuperación"
        )
    }

    fun verifyRecoveryCode(context: Context, code: String): Boolean {
        val raw = secureRead(context, K_RECOVERY_STATE, null) ?: return false
        val state = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (System.currentTimeMillis() > state.optLong("expires", 0L)) {
            secureRemove(context, K_RECOVERY_STATE)
            return false
        }
        val attempts = state.optInt("attempts", 0)
        if (attempts >= RECOVERY_MAX_ATTEMPTS) return false

        val salt = Base64.decode(state.optString("salt"), Base64.NO_WRAP)
        val expected = Base64.decode(state.optString("hash"), Base64.NO_WRAP)
        if (state.optBoolean("verified", false)) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(salt + code.trim().toByteArray())
        val ok = MessageDigest.isEqual(expected, actual) && state.optString("email").isNotBlank()
        if (ok) {
            state.put("verified", true)
            state.put("verifiedAt", System.currentTimeMillis())
            secureWrite(context, K_RECOVERY_STATE, state.toString())
        } else {
            state.put("attempts", attempts + 1)
            secureWrite(context, K_RECOVERY_STATE, state.toString())
        }
        return ok
    }

    fun finishRecovery(context: Context, newPassword: String): AuthOutcome {
        migrateSecurity(context)
        val raw = secureRead(context, K_RECOVERY_STATE, null)
            ?: return AuthOutcome(AuthStatus.ERROR, "Recuperación expirada")
        val state = runCatching { JSONObject(raw) }.getOrNull()
            ?: return AuthOutcome(AuthStatus.ERROR, "Recuperación inválida")
        if (System.currentTimeMillis() > state.optLong("expires", 0L)) {
            secureRemove(context, K_RECOVERY_STATE)
            return AuthOutcome(AuthStatus.ERROR, "Recuperación expirada")
        }
        if (!state.optBoolean("verified", false)) {
            return AuthOutcome(AuthStatus.ERROR, "Verifica el código antes de cambiar la contraseña")
        }
        val email = normalizeEmail(state.optString("email"))
        val user = readUsers(context).firstOrNull { it.correo == email }
            ?: return AuthOutcome(AuthStatus.ERROR, "Recuperación inválida")
        val validation = PasswordSecurity.validate(newPassword, email, user.nombre)
        if (!validation.valid) return AuthOutcome(AuthStatus.PASSWORD_INVALID, validation.message)

        val credentials = readCredentials(context)
        val index = credentials.indexOfFirst { it.email == email }
        if (index < 0) return AuthOutcome(AuthStatus.ERROR, "Recuperación inválida")
        credentials[index] = hashCredential(email, newPassword)
        saveCredentials(context, credentials)
        secureRemove(context, K_RECOVERY_STATE)
        return AuthOutcome(AuthStatus.SUCCESS)
    }

    fun replaceRemoteProfile(context: Context, user: UsuarioLocal) {
        migrateSecurity(context)
        val email = normalizeEmail(user.correo)
        val users = readUsers(context)
        val index = users.indexOfFirst { it.correo == email }
        if (index >= 0) users[index] = user.copy(correo = email) else users += user.copy(correo = email)
        saveUsers(context, users)
        secureWrite(context, K_ACTIVE_EMAIL, email)
        prefs(context).edit()
            .putBoolean(K_LOGGED, true)
            .putLong(K_SESSION_STARTED, System.currentTimeMillis())
            .apply()
        if (prefs(context).getInt(scopedKey(K_DATA_VERSION, email), 0) == 0) initializeEmptyProfile(context, email)
    }

    fun removeLocalCredential(context: Context, email: String) {
        migrateSecurity(context)
        val normalized = normalizeEmail(email)
        saveCredentials(context, readCredentials(context).filterNot { it.email == normalized })
    }

    fun getProfileByEmail(context: Context, email: String): UsuarioLocal? {
        migrateSecurity(context)
        val normalized = normalizeEmail(email)
        return readUsers(context).firstOrNull { it.correo == normalized }
    }

    fun replaceCurrentDataFromRemote(
        context: Context,
        artifacts: List<ArtefactoLocal>,
        consumptions: List<ConsumoLocal>,
        meta: MetaLocal?
    ) {
        saveArtifactsRaw(context, artifacts)
        saveConsumosRaw(context, consumptions)
        if (meta == null) secureRemove(context, currentScopedKey(context, K_META)) else saveMeta(context, meta)
        prefs(context).edit().putInt(currentScopedKey(context, K_DATA_VERSION), DATA_VERSION).apply()
    }

    /** Actualiza únicamente artefactos sincronizados; no borra consumos ni metas. */
    fun replaceRemoteArtifacts(context: Context, artifacts: List<ArtefactoLocal>) {
        if (!hasActiveSession(context)) return
        saveArtifactsRaw(context, artifacts)
    }

    /** Sólo aplica datos completos y validados procedentes de Firebase. */
    fun replaceRemoteConsumptions(context: Context, consumptions: List<ConsumoLocal>) {
        if (!hasActiveSession(context)) return
        saveConsumosRaw(context, consumptions)
    }

    fun replaceRemoteMeta(context: Context, meta: MetaLocal?) {
        if (!hasActiveSession(context)) return
        if (meta == null) secureRemove(context, currentScopedKey(context, K_META))
        else saveMeta(context, meta)
    }

    private fun readArtifactsRaw(context: Context): MutableList<ArtefactoLocal> {
        val raw = secureRead(context, currentScopedKey(context, K_ARTIFACTS), "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<ArtefactoLocal>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += ArtefactoLocal(
                id = o.getInt("id"),
                nombre = o.getString("nombre"),
                categoria = o.optString("categoria"),
                marca = o.optString("marca"),
                modelo = o.optString("modelo"),
                potenciaW = o.optDouble("potenciaW", 0.0),
                activo = o.optBoolean("activo", true),
                monitoreando = o.optBoolean("monitoreando", false),
                fechaRegistro = o.optLong("fechaRegistro", System.currentTimeMillis()),
                fechaRetiro = if (o.has("fechaRetiro") && !o.isNull("fechaRetiro")) o.optLong("fechaRetiro") else null,
                tipoFuente = runCatching { TipoFuenteMonitoreo.valueOf(o.optString("tipoFuente", TipoFuenteMonitoreo.SIMULADA.name)) }
                    .getOrDefault(TipoFuenteMonitoreo.SIMULADA)
            )
        }
        return out
    }

    private fun saveArtifactsRaw(context: Context, list: List<ArtefactoLocal>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("nombre", a.nombre)
                    .put("categoria", a.categoria)
                    .put("marca", a.marca)
                    .put("modelo", a.modelo)
                    .put("potenciaW", a.potenciaW)
                    .put("activo", a.activo)
                    .put("monitoreando", a.monitoreando)
                    .put("fechaRegistro", a.fechaRegistro)
                    .put("fechaRetiro", a.fechaRetiro ?: JSONObject.NULL)
                    .put("tipoFuente", a.tipoFuente.name)
            )
        }
        secureWrite(context, currentScopedKey(context, K_ARTIFACTS), arr.toString())
    }

    private fun readConsumosRaw(context: Context): MutableList<ConsumoLocal> {
        val raw = secureRead(context, currentScopedKey(context, K_CONSUMOS), "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<ConsumoLocal>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += ConsumoLocal(
                id = o.getInt("id"),
                artefactoId = o.getInt("artefactoId"),
                artefactoNombre = o.optString("artefactoNombre"),
                artefactoCategoria = o.optString("artefactoCategoria"),
                artefactoMarca = o.optString("artefactoMarca"),
                artefactoModelo = o.optString("artefactoModelo"),
                consumoKwh = o.getDouble("consumoKwh"),
                fecha = o.getLong("fecha"),
                tipoFuente = runCatching { TipoFuenteMonitoreo.valueOf(o.optString("tipoFuente", TipoFuenteMonitoreo.SIMULADA.name)) }
                    .getOrDefault(TipoFuenteMonitoreo.SIMULADA)
            )
        }
        return out
    }

    private fun saveConsumosRaw(context: Context, list: List<ConsumoLocal>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("artefactoId", c.artefactoId)
                    .put("artefactoNombre", c.artefactoNombre)
                    .put("artefactoCategoria", c.artefactoCategoria)
                    .put("artefactoMarca", c.artefactoMarca)
                    .put("artefactoModelo", c.artefactoModelo)
                    .put("consumoKwh", c.consumoKwh)
                    .put("fecha", c.fecha)
                    .put("tipoFuente", c.tipoFuente.name)
            )
        }
        secureWrite(context, currentScopedKey(context, K_CONSUMOS), arr.toString())
    }

    private fun withArtifactSnapshots(
        consumptions: List<ConsumoLocal>,
        artifacts: List<ArtefactoLocal>
    ): List<ConsumoLocal> {
        val byId = artifacts.associateBy { it.id }
        return consumptions.map { c ->
            if (c.artefactoNombre.isNotBlank()) c
            else {
                val a = byId[c.artefactoId]
                c.copy(
                    artefactoNombre = a?.nombre ?: "Artefacto histórico #${c.artefactoId}",
                    artefactoCategoria = a?.categoria ?: "Histórico",
                    artefactoMarca = a?.marca.orEmpty(),
                    artefactoModelo = a?.modelo.orEmpty(),
                    tipoFuente = a?.tipoFuente ?: c.tipoFuente
                )
            }
        }
    }

    fun purgeExpiredRetiredArtifacts(context: Context) {
        val limit = System.currentTimeMillis() - DIAS_RETENCION_ARTEFACTOS * DIA_MS
        val artifacts = readArtifactsRaw(context)
        val expiredIds = artifacts.filter { !it.activo && it.fechaRetiro != null && it.fechaRetiro <= limit }.map { it.id }.toSet()
        if (expiredIds.isEmpty()) return
        // Antes de depurar el maestro, fija su metadata final dentro del historial.
        val expiredById = artifacts.filter { it.id in expiredIds }.associateBy { it.id }
        val preservedHistory = withArtifactSnapshots(readConsumosRaw(context), artifacts).map { c ->
            val a = expiredById[c.artefactoId]
            if (a != null) c.copy(
                artefactoNombre = a.nombre,
                artefactoCategoria = a.categoria,
                artefactoMarca = a.marca,
                artefactoModelo = a.modelo
            ) else c
        }
        saveConsumosRaw(context, preservedHistory)
        // Conserva el consumo histórico al depurar el registro retirado.
        saveArtifactsRaw(context, artifacts.filterNot { it.id in expiredIds })
    }

    fun getArtifacts(context: Context, includeInactive: Boolean = false): List<ArtefactoLocal> {
        ensureSeed(context)
        val out = readArtifactsRaw(context)
        return if (includeInactive) out else out.filter { it.activo }
    }

    fun getArtifact(context: Context, id: Int): ArtefactoLocal? = getArtifacts(context, true).firstOrNull { it.id == id }

    fun saveArtifact(
        context: Context,
        id: Int?,
        nombre: String,
        categoria: String,
        marca: String,
        modelo: String,
        potenciaW: Double,
        tipoFuente: TipoFuenteMonitoreo
    ): ArtefactoLocal {
        val list = getArtifacts(context, true).toMutableList()
        val saved = if (id == null) {
            val nextId = VoltifyIds.newUniqueId(list.map { it.id } + readConsumosRaw(context).map { it.artefactoId })
            ArtefactoLocal(
                nextId,
                nombre,
                categoria,
                marca,
                modelo,
                potenciaW,
                true,
                false,
                System.currentTimeMillis(),
                null,
                tipoFuente
            )
        } else {
            val old = list.first { it.id == id }
            old.copy(
                nombre = nombre,
                categoria = categoria,
                marca = marca,
                modelo = modelo,
                potenciaW = potenciaW,
                tipoFuente = tipoFuente
            )
        }
        val idx = list.indexOfFirst { it.id == saved.id }
        if (idx >= 0) list[idx] = saved else list.add(saved)
        saveArtifactsRaw(context, list)

        val history = readConsumosRaw(context)
        if (history.any { it.artefactoId == saved.id }) {
            saveConsumosRaw(context, history.map { c ->
                if (c.artefactoId == saved.id) c.copy(
                    artefactoNombre = saved.nombre,
                    artefactoCategoria = saved.categoria,
                    artefactoMarca = saved.marca,
                    artefactoModelo = saved.modelo
                ) else c
            })
        }
        return saved
    }

    fun retireArtifact(context: Context, id: Int) {
        val list = getArtifacts(context, true).map {
            if (it.id == id) it.copy(activo = false, monitoreando = false, fechaRetiro = System.currentTimeMillis()) else it
        }
        saveArtifactsRaw(context, list)
    }

    fun deleteArtifactPermanently(context: Context, id: Int) {
        // Elimina el registro maestro y conserva las mediciones históricas.
        val artifacts = getArtifacts(context, true)
        val target = artifacts.firstOrNull { it.id == id } ?: return
        val consumptions = withArtifactSnapshots(readConsumosRaw(context), artifacts).map { c ->
            if (c.artefactoId == id) {
                c.copy(
                    artefactoNombre = target.nombre,
                    artefactoCategoria = target.categoria,
                    artefactoMarca = target.marca,
                    artefactoModelo = target.modelo
                )
            } else c
        }
        saveConsumosRaw(context, consumptions)
        saveArtifactsRaw(context, artifacts.filterNot { it.id == id })
    }

    fun setMonitoring(context: Context, id: Int, enabled: Boolean) {
        val list = getArtifacts(context, true).map { artifact ->
            if (artifact.id == id && artifact.activo) artifact.copy(monitoreando = enabled) else artifact
        }
        saveArtifactsRaw(context, list)
    }

    fun getMonitoring(context: Context): ArtefactoLocal? = getMonitoringArtifacts(context).firstOrNull()

    fun getMonitoringArtifacts(context: Context): List<ArtefactoLocal> =
        getArtifacts(context).filter { it.monitoreando }

    fun simulateMonitoringSample(context: Context, id: Int): Int {
        val artifact = getArtifact(context, id) ?: return 0
        if (!artifact.activo || !artifact.monitoreando || artifact.tipoFuente != TipoFuenteMonitoreo.SIMULADA) return 0

        val now = chileCalendar()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val startHour = maxOf(0, currentHour - 3)
        val factors = listOf(0.28, 0.42, 0.34, 0.38)
        val list = getConsumptions(context).toMutableList()
        val usedIds = list.map { it.id }.toMutableSet()
        var count = 0

        for (hour in startHour..currentHour) {
            val factor = factors[count % factors.size]
            val timestamp = startOfDay().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 30)
            }.timeInMillis
            val kwh = (artifact.potenciaW / 1000.0) * factor
            list += ConsumoLocal(
                id = VoltifyIds.newUniqueId(usedIds).also { usedIds.add(it) },
                artefactoId = artifact.id,
                artefactoNombre = artifact.nombre,
                artefactoCategoria = artifact.categoria,
                artefactoMarca = artifact.marca,
                artefactoModelo = artifact.modelo,
                consumoKwh = kwh,
                fecha = timestamp,
                tipoFuente = artifact.tipoFuente
            )
            count++
        }
        saveConsumosRaw(context, list)
        return count
    }

    /**
     * Genera una lectura de simulación en vivo. La variación es controlada y
     * cambia cada ciclo para representar el comportamiento de un artefacto real.
     */
    fun simulateLiveReading(context: Context, id: Int, intervalSeconds: Int = 3): MonitoreoLecturaActual? {
        val artifact = getArtifact(context, id) ?: return null
        if (!artifact.activo || !artifact.monitoreando || artifact.tipoFuente != TipoFuenteMonitoreo.SIMULADA) return null

        val safeInterval = intervalSeconds.coerceAtLeast(1)
        val factors = doubleArrayOf(0.48, 0.62, 0.79, 0.57, 0.88, 0.71, 0.53, 0.67)
        val step = ((System.currentTimeMillis() / (safeInterval * 1000L)) + artifact.id).toInt()
        val factor = factors[Math.floorMod(step, factors.size)]
        val powerW = (artifact.potenciaW * factor).coerceAtLeast(0.1)
        val incrementalKwh = (powerW / 1000.0) * (safeInterval / 3600.0)
        val timestamp = System.currentTimeMillis()

        // La lectura en vivo no escribe una fila histórica cada 3 segundos.
        // El ViewModel acumula la energía y consolida el historial en intervalos
        // razonables para evitar crecimiento excesivo y escrituras innecesarias.
        return MonitoreoLecturaActual(
            artefactoId = artifact.id,
            potenciaW = powerW,
            consumoIncrementalKwh = incrementalKwh,
            timestamp = timestamp
        )
    }

    fun getConsumptions(context: Context): List<ConsumoLocal> {
        ensureSeed(context)
        return readConsumosRaw(context)
    }

    fun appendConsumption(context: Context, artifactId: Int, kwh: Double) {
        val artifact = getArtifact(context, artifactId) ?: return
        val list = getConsumptions(context).toMutableList()
        list += ConsumoLocal(
            id = VoltifyIds.newUniqueId(list.map { it.id }),
            artefactoId = artifactId,
            artefactoNombre = artifact.nombre,
            artefactoCategoria = artifact.categoria,
            artefactoMarca = artifact.marca,
            artefactoModelo = artifact.modelo,
            consumoKwh = kwh,
            fecha = System.currentTimeMillis(),
            tipoFuente = artifact.tipoFuente
        )
        saveConsumosRaw(context, list)
    }


    /** Acumula una sola fila por artefacto y bloque de 30 s de simulación. */
    fun appendSimulatedConsumption(context: Context, artifactId: Int, kwh: Double, bucket: Long) {
        if (!kwh.isFinite() || kwh <= 0.0 || bucket <= 0L) return
        val artifact = getArtifact(context, artifactId) ?: return
        if (artifact.tipoFuente != TipoFuenteMonitoreo.SIMULADA) return
        val list = getConsumptions(context).toMutableList()
        val id = VoltifyIds.simulationBucketId(artifactId, bucket)
        val contributionKey = "${activeEmail(context)}:$artifactId:$bucket"
        val mine = (localSimulationContributions[contributionKey] ?: 0.0) + kwh
        localSimulationContributions[contributionKey] = mine
        // Mantener acotado el estado temporal de la simulación.
        if (localSimulationContributions.size > 200) localSimulationContributions.clear()
        val existingIndex = list.indexOfFirst {
            it.id == id && it.artefactoId == artifactId && it.fecha == bucket * 30_000L
        }
        if (existingIndex >= 0) {
            val current = list[existingIndex]
            list[existingIndex] = current.copy(consumoKwh = maxOf(current.consumoKwh, mine))
        } else {
            require(list.none { it.id == id }) { "Colisión de ID en consumo simulado" }
            list += ConsumoLocal(
                id = id,
                artefactoId = artifactId,
                artefactoNombre = artifact.nombre,
                artefactoCategoria = artifact.categoria,
                artefactoMarca = artifact.marca,
                artefactoModelo = artifact.modelo,
                consumoKwh = mine,
                fecha = bucket * 30_000L,
                tipoFuente = TipoFuenteMonitoreo.SIMULADA
            )
        }
        saveConsumosRaw(context, list)
    }

    fun registerIotReading(context: Context, artifactId: Int, kwh: Double, timestamp: Long) {
        val artifact = getArtifact(context, artifactId) ?: return
        if (!artifact.activo || !artifact.monitoreando || artifact.tipoFuente != TipoFuenteMonitoreo.IOT_REAL || kwh < 0.0) return
        val list = getConsumptions(context).toMutableList()
        list += ConsumoLocal(
            id = VoltifyIds.newUniqueId(list.map { it.id }),
            artefactoId = artifact.id,
            artefactoNombre = artifact.nombre,
            artefactoCategoria = artifact.categoria,
            artefactoMarca = artifact.marca,
            artefactoModelo = artifact.modelo,
            consumoKwh = kwh,
            fecha = timestamp,
            tipoFuente = TipoFuenteMonitoreo.IOT_REAL
        )
        saveConsumosRaw(context, list)
    }

    private fun chileCalendar(time: Long = System.currentTimeMillis()): Calendar =
        Calendar.getInstance(TZ_CHILE, Locale("es", "CL")).apply { timeInMillis = time }

    private fun startOfDay(time: Long = System.currentTimeMillis()): Calendar = chileCalendar(time).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun firstOfMonth(time: Long = System.currentTimeMillis()): Calendar = startOfDay(time).apply {
        set(Calendar.DAY_OF_MONTH, 1)
    }

    private fun moveMonths(c: Calendar, amount: Int): Calendar = (c.clone() as Calendar).apply { add(Calendar.MONTH, amount) }

    private fun addDays(c: Calendar, amount: Int): Calendar = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, amount) }

    private fun daysBetween(start: Calendar, end: Calendar): Int {
        var count = 0
        val c = start.clone() as Calendar
        while (c.before(end) && count < 400) { c.add(Calendar.DAY_OF_MONTH, 1); count++ }
        return count
    }

    fun periodBounds(period: String, previous: Boolean = false): PeriodBounds {
        val today = startOfDay()
        val tomorrow = addDays(today, 1)
        var currentStart: Calendar
        var currentEnd: Calendar = tomorrow
        var currentLabel: String
        var previousStart: Calendar? = null
        var previousEnd: Calendar? = null
        var previousLabel = "Período anterior"

        when (period) {
            PERIODO_HOY -> {
                currentStart = today
                currentLabel = "Hoy"
                previousStart = addDays(today, -1)
                previousEnd = today
                previousLabel = "Ayer"
            }
            PERIODO_SEMANA -> {
                currentStart = addDays(today, -6)
                currentLabel = "Últimos 7 días"
                previousStart = addDays(currentStart, -7)
                previousEnd = currentStart
                previousLabel = "7 días anteriores"
            }
            PERIODO_TRES_MESES -> {
                val monthStart = firstOfMonth()
                currentStart = moveMonths(monthStart, -2)
                currentLabel = "Últimos 3 meses"
                val dayCount = daysBetween(currentStart, currentEnd)
                previousStart = moveMonths(currentStart, -3)
                previousEnd = addDays(previousStart, dayCount)
                previousLabel = "3 meses anteriores equivalentes"
            }
            PERIODO_TODOS -> {
                currentStart = Calendar.getInstance(TZ_CHILE).apply { timeInMillis = 0L }
                currentLabel = "Todo el historial"
            }
            else -> {
                currentStart = firstOfMonth()
                currentLabel = "Mes actual"
                val dayCount = daysBetween(currentStart, currentEnd)
                previousStart = moveMonths(currentStart, -1)
                val previousMonthEnd = currentStart
                val estimated = addDays(previousStart, dayCount)
                previousEnd = if (estimated.before(previousMonthEnd)) estimated else previousMonthEnd
                previousLabel = "Mismo avance del mes anterior"
            }
        }

        if (!previous || previousStart == null || previousEnd == null) {
            return PeriodBounds(currentStart.timeInMillis, currentEnd.timeInMillis, currentLabel)
        }
        return PeriodBounds(previousStart.timeInMillis, previousEnd.timeInMillis, previousLabel)
    }

    fun consumptionsForPeriod(context: Context, period: String): List<ConsumoLocal> {
        if (period == PERIODO_TODOS) return getConsumptions(context)
        val b = periodBounds(period)
        return getConsumptions(context).filter { it.fecha >= b.start && it.fecha < b.endExclusive }
    }

    fun consumptionsBetween(context: Context, bounds: PeriodBounds): List<ConsumoLocal> =
        getConsumptions(context).filter { it.fecha >= bounds.start && it.fecha < bounds.endExclusive }

    fun totalKwh(context: Context, period: String): Double = consumptionsForPeriod(context, period).sumOf { it.consumoKwh }

    fun totalKwh(context: Context, bounds: PeriodBounds): Double = consumptionsBetween(context, bounds).sumOf { it.consumoKwh }

    fun artifactKwh(context: Context, id: Int, period: String): Double =
        consumptionsForPeriod(context, period).filter { it.artefactoId == id }.sumOf { it.consumoKwh }

    fun consumptionSummaries(context: Context, period: String): List<ConsumoArtefactoResumen> {
        val artifacts = getArtifacts(context, includeInactive = true).associateBy { it.id }
        return consumptionsForPeriod(context, period)
            .groupBy { it.artefactoId }
            .map { (artifactId, rows) ->
                val current = artifacts[artifactId]
                val snapshot = rows.maxByOrNull { it.fecha }!!
                val state = when {
                    current == null -> EstadoArtefacto.ELIMINADO
                    current.activo -> EstadoArtefacto.ACTIVO
                    else -> EstadoArtefacto.RETIRADO
                }
                ConsumoArtefactoResumen(
                    artefactoId = artifactId,
                    nombre = current?.nombre ?: snapshot.artefactoNombre,
                    categoria = current?.categoria ?: snapshot.artefactoCategoria,
                    marca = current?.marca ?: snapshot.artefactoMarca,
                    modelo = current?.modelo ?: snapshot.artefactoModelo,
                    estado = state,
                    fechaRetiro = current?.fechaRetiro,
                    kwh = rows.sumOf { it.consumoKwh },
                    tipoFuente = snapshot.tipoFuente
                )
            }
            .sortedByDescending { it.kwh }
    }

    fun tariff(context: Context): TarifaInfo {
        val u = getUser(context)
        if (u.usarTarifaManual && u.valorKwhManual != null && u.cargoFijoManual != null) return TarifaInfo(true, "manual", u.valorKwhManual, u.cargoFijoManual)
        val base = when (u.empresa) { "CGE" -> 178.0; "Chilquinta" -> 169.0; "Otra" -> 185.0; else -> 172.5 }
        val communeAdjust = when (u.comuna) { "Providencia" -> -2.0; "Maipú" -> 3.0; "Estación Central" -> 1.5; else -> 0.0 }
        return TarifaInfo(true, "estimación local no oficial", base + communeAdjust, 1980.0)
    }

    private fun fixedMonths(period: String): Double = when (period) {
        PERIODO_HOY, PERIODO_SEMANA -> 0.0
        PERIODO_TRES_MESES -> 3.0
        PERIODO_MES -> 1.0
        else -> 0.0
    }

    fun energyCost(context: Context, period: String): Double = totalKwh(context, period) * tariff(context).valorKwh
    fun fixedCost(context: Context, period: String): Double = tariff(context).cargoFijo * fixedMonths(period)
    fun totalCost(context: Context, period: String): Double = energyCost(context, period) + fixedCost(context, period)

    private fun totalCostForBounds(context: Context, bounds: PeriodBounds, period: String): Double =
        totalKwh(context, bounds) * tariff(context).valorKwh + tariff(context).cargoFijo * fixedMonths(period)

    fun comparison(context: Context, period: String): PeriodComparison {
        val current = periodBounds(period, false)
        val previous = periodBounds(period, true)
        val currentKwh = totalKwh(context, current)
        val previousKwh = totalKwh(context, previous)
        val currentCost = totalCostForBounds(context, current, period)
        val previousCost = totalCostForBounds(context, previous, period)
        fun pct(now: Double, before: Double): Double? = when {
            before > 0.0 -> ((now - before) / before) * 100.0
            now > 0.0 -> 100.0
            else -> 0.0
        }
        val comparisonText = when (period) {
            PERIODO_HOY -> "Comparado con ayer"
            PERIODO_SEMANA -> "Comparado con los 7 días anteriores"
            PERIODO_TRES_MESES -> "Comparado con un período equivalente anterior"
            else -> "Comparado con el mismo avance del mes anterior"
        }
        return PeriodComparison(current.label, previous.label, comparisonText, currentKwh, previousKwh, currentCost, previousCost, pct(currentKwh, previousKwh), pct(currentCost, previousCost))
    }

    fun trendData(context: Context, period: String): TrendData =
        buildTrendData(consumptionsForPeriod(context, period), period)

    fun trendDataForArtifact(context: Context, artifactId: Int, period: String): TrendData =
        buildTrendData(
            consumptionsForPeriod(context, period).filter { it.artefactoId == artifactId },
            period
        )

    private fun buildTrendData(consumptions: List<ConsumoLocal>, period: String): TrendData {
        val bounds = periodBounds(period)
        val labels = mutableListOf<String>()
        val values = mutableListOf<Double>()
        when (period) {
            PERIODO_HOY -> {
                val fmt = SimpleDateFormat("HH:mm", Locale("es", "CL")).apply { timeZone = TZ_CHILE }
                val start = chileCalendar(bounds.start)
                repeat(8) { index ->
                    val a = (start.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, index * 3) }
                    val b = (a.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 3) }
                    labels += fmt.format(Date(a.timeInMillis))
                    values += consumptions.filter { it.fecha >= a.timeInMillis && it.fecha < b.timeInMillis }.sumOf { it.consumoKwh }
                }
            }
            PERIODO_SEMANA -> {
                val fmt = SimpleDateFormat("E", Locale("es", "CL")).apply { timeZone = TZ_CHILE }
                var c = chileCalendar(bounds.start)
                repeat(7) {
                    val next = addDays(c, 1)
                    labels += fmt.format(Date(c.timeInMillis)).replaceFirstChar { ch -> ch.uppercase() }
                    values += consumptions.filter { it.fecha >= c.timeInMillis && it.fecha < next.timeInMillis }.sumOf { it.consumoKwh }
                    c = next
                }
            }
            PERIODO_TRES_MESES -> {
                val fmt = SimpleDateFormat("MMM", Locale("es", "CL")).apply { timeZone = TZ_CHILE }
                var c = firstOfMonth(bounds.start)
                repeat(3) {
                    val next = moveMonths(c, 1)
                    labels += fmt.format(Date(c.timeInMillis)).replaceFirstChar { ch -> ch.uppercase() }
                    values += consumptions.filter { it.fecha >= c.timeInMillis && it.fecha < minOf(next.timeInMillis, bounds.endExclusive) }.sumOf { it.consumoKwh }
                    c = next
                }
            }
            else -> {
                var c = firstOfMonth(bounds.start)
                var week = 1
                while (c.timeInMillis < bounds.endExclusive && week <= 5) {
                    val next = addDays(c, 7)
                    labels += "Sem. $week"
                    values += consumptions.filter { it.fecha >= c.timeInMillis && it.fecha < minOf(next.timeInMillis, bounds.endExclusive) }.sumOf { it.consumoKwh }
                    c = next
                    week++
                }
            }
        }
        return TrendData(labels, values)
    }

    fun compareArtifacts(
        context: Context,
        artifactAId: Int,
        artifactBId: Int,
        period: String
    ): ArtifactComparison? {
        if (artifactAId == artifactBId) return null
        val artifactA = getArtifact(context, artifactAId) ?: return null
        val artifactB = getArtifact(context, artifactBId) ?: return null
        val kwhA = artifactKwh(context, artifactAId, period)
        val kwhB = artifactKwh(context, artifactBId, period)
        val rate = tariff(context).valorKwh
        val difference = kwhA - kwhB
        val pct = if (kwhB > 0.0) (difference / kwhB) * 100.0 else null
        return ArtifactComparison(
            artifactA = artifactA,
            artifactB = artifactB,
            trendA = trendDataForArtifact(context, artifactAId, period),
            trendB = trendDataForArtifact(context, artifactBId, period),
            kwhA = kwhA,
            kwhB = kwhB,
            energyCostA = kwhA * rate,
            energyCostB = kwhB * rate,
            differenceKwh = difference,
            differencePctFromB = pct
        )
    }

    fun getMeta(context: Context): MetaLocal? {
        ensureSeed(context)
        val raw = secureRead(context, currentScopedKey(context, K_META), null) ?: return null
        val o = JSONObject(raw)
        return MetaLocal(
            if (o.has("consumo") && !o.isNull("consumo")) o.getDouble("consumo") else null,
            if (o.has("costo") && !o.isNull("costo")) o.getDouble("costo") else null
        )
    }

    fun saveMeta(context: Context, meta: MetaLocal) {
        secureWrite(context, currentScopedKey(context, K_META), JSONObject().put("consumo", meta.consumoKwh ?: JSONObject.NULL).put("costo", meta.costoClp ?: JSONObject.NULL).toString())
    }

    fun deleteMeta(context: Context) { prefs(context).edit().remove(currentScopedKey(context, K_META)).apply() }

    fun goalProgress(context: Context): Int {
        val meta = getMeta(context)?.consumoKwh ?: return 0
        return ((totalKwh(context, PERIODO_MES) / meta) * 100.0).toInt().coerceAtLeast(0)
    }

    fun retentionExpiryMillis(artifact: ArtefactoLocal): Long? = artifact.fechaRetiro?.plus(DIAS_RETENCION_ARTEFACTOS * DIA_MS)

    fun retentionRemainingDays(artifact: ArtefactoLocal): Int? {
        val expiry = retentionExpiryMillis(artifact) ?: return null
        return max(0, ((expiry - System.currentTimeMillis() + DIA_MS - 1) / DIA_MS).toInt())
    }

    fun formatChileDate(millis: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale("es", "CL")).apply { timeZone = TZ_CHILE }.format(Date(millis))

    fun currentChileDateTime(): String = SimpleDateFormat("d 'de' MMMM 'de' yyyy · HH:mm 'h'", Locale("es", "CL")).apply { timeZone = TZ_CHILE }.format(Date())

    fun formatKwh(value: Double) = String.format(Locale("es", "CL"), "%.2f kWh", value)

    fun formatClp(value: Double): String {
        val nf = NumberFormat.getIntegerInstance(Locale("es", "CL"))
        return "${'$'}${nf.format(value.toLong())} CLP"
    }

    fun reportText(context: Context, period: String): String {
        val rows = consumptionSummaries(context, period).filter { it.kwh > 0.0 }
        val tarifa = tariff(context)
        val cmp = comparison(context, period)
        return buildString {
            appendLine("VOLTIFY - REPORTE DE CONSUMO")
            appendLine("Fecha Chile: ${currentChileDateTime()}")
            appendLine("Período: ${periodLabel(period)}")
            appendLine("Consumo: ${formatKwh(totalKwh(context, period))}")
            appendLine("Costo energía: ${formatClp(energyCost(context, period))}")
            appendLine("Cargo fijo aplicado: ${formatClp(fixedCost(context, period))}")
            appendLine("Costo total estimado: ${formatClp(totalCost(context, period))}")
            appendLine("Tarifa: ${String.format(Locale("es", "CL"), "%.2f", tarifa.valorKwh)} \$/kWh + ${formatClp(tarifa.cargoFijo)} fijo mensual")
            appendLine()
            appendLine(cmp.comparisonText)
            appendLine("Actual: ${formatKwh(cmp.currentKwh)} / ${formatClp(cmp.currentCost)}")
            appendLine("Anterior: ${formatKwh(cmp.previousKwh)} / ${formatClp(cmp.previousCost)}")
            appendLine("Variación consumo: ${formatVariation(cmp.consumptionChangePct)}")
            appendLine("Variación costo: ${formatVariation(cmp.costChangePct)}")
            appendLine()
            appendLine("Consumo por artefacto:")
            rows.forEachIndexed { index, row ->
                val estado = when (row.estado) {
                    EstadoArtefacto.ACTIVO -> "Activo"
                    EstadoArtefacto.RETIRADO -> "Retirado"
                    EstadoArtefacto.ELIMINADO -> "Eliminado (histórico)"
                }
                appendLine("${index + 1}. ${row.nombre}: ${formatKwh(row.kwh)} · $estado")
            }
        }
    }

    fun formatVariation(value: Double?): String {
        if (value == null) return "Sin comparación"
        val arrow = when { value > 0.05 -> "↑"; value < -0.05 -> "↓"; else -> "=" }
        return "$arrow ${String.format(Locale("es", "CL"), "%.1f", kotlin.math.abs(value))}%"
    }

    fun periodLabel(period: String) = when (period) {
        PERIODO_HOY -> "Hoy"
        PERIODO_SEMANA -> "Últimos 7 días"
        PERIODO_MES -> "Mes actual"
        PERIODO_TRES_MESES -> "Últimos 3 meses"
        else -> "Todo el historial"
    }
}

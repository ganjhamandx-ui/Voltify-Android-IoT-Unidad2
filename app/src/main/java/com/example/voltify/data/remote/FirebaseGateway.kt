package com.example.voltify.data.remote

import android.content.Context
import android.util.Log
import com.example.voltify.data.local.VoltifyIds
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.ConsumoLocal
import com.example.voltify.model.MetaLocal
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.model.UsuarioLocal
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Capa remota opcional. Si google-services.json no está configurado, Voltify sigue funcionando
 * en modo local seguro para la evaluación formativa. Al agregar Firebase, la misma aplicación
 * sincroniza identidad y datos por UID sin cambiar Activities ni ViewModels.
 */
class FirebaseGateway(context: Context) {
    private val appContext = context.applicationContext

    data class RemoteSnapshot(
        val profile: UsuarioLocal?,
        val artifacts: List<ArtefactoLocal>,
        val consumptions: List<ConsumoLocal>,
        val meta: MetaLocal?
    )

    fun isConfigured(): Boolean = runCatching {
        FirebaseApp.getApps(appContext).isNotEmpty() || FirebaseApp.initializeApp(appContext) != null
    }.getOrDefault(false)

    private fun auth(): FirebaseAuth = FirebaseAuth.getInstance()

    // Inicializar ANTES del primer DatabaseReference: la caché y escrituras pendientes
    // se conservan en disco al perder Wi-Fi y al reiniciar la aplicación.
    private val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance().apply { setPersistenceEnabled(true) }
    }
    private fun db(): FirebaseDatabase = database

    suspend fun createAccount(profile: UsuarioLocal, password: String) {
        require(isConfigured())
        val result = auth().createUserWithEmailAndPassword(profile.correo, password).awaitTask()
        val user = result.user ?: error("No se pudo crear el usuario remoto")
        // El perfil no contiene contraseña. Se almacena bajo el UID autenticado.
        db().reference.child("users").child(user.uid).child("profile").setValue(profile.toMap()).awaitTask()
        user.sendEmailVerification().awaitTask()
        auth().signOut()
    }

    suspend fun signInVerified(email: String, password: String): String {
        require(isConfigured())
        val result = auth().signInWithEmailAndPassword(email, password).awaitTask()
        val user = result.user ?: error("No se pudo autenticar")
        user.reload().awaitTask()
        val refreshed = auth().currentUser ?: error("No se pudo validar la sesión")
        if (!refreshed.isEmailVerified) {
            auth().signOut()
            throw EmailNotVerifiedException()
        }
        // Fuerza un token actualizado: las reglas RTDB exigen email_verified.
        refreshed.getIdToken(true).awaitTask()
        return refreshed.uid
    }

    fun signOut() {
        if (isConfigured()) runCatching { auth().signOut() }
    }

    suspend fun sendPasswordReset(email: String) {
        require(isConfigured())
        auth().sendPasswordResetEmail(email).awaitTask()
    }

    fun currentUid(): String? = if (isConfigured()) auth().currentUser?.uid else null

    private fun userRef(uid: String) = db().reference.child("users").child(uid)

    /** Lecturas separadas por colección para aislar errores; las reglas publicadas también permiten leer el nodo padre. */
    suspend fun download(uid: String): RemoteSnapshot {
        val base = userRef(uid)
        val profile = base.child("profile").get().awaitTask()
        val artifacts = base.child("artifacts").get().awaitTask()
        val consumptions = base.child("consumptions").get().awaitTask()
        val meta = base.child("meta").get().awaitTask()
        Log.i("VoltifyFirebase", "Descarga RTDB: artifacts=${artifacts.childrenCount}, consumptions=${consumptions.childrenCount}")
        return RemoteSnapshot(
            profile = profile.toUser(),
            artifacts = artifacts.children.map { snapshot ->
                snapshot.toArtifact() ?: error("Artefacto remoto inválido en ${snapshot.key}")
            }.sortedBy { it.id },
            consumptions = consumptions.children.map { snapshot ->
                snapshot.toConsumption() ?: error("Consumo remoto inválido en ${snapshot.key}")
            }.sortedBy { it.id },
            meta = meta.toMeta()
        )
    }

    /** Evita sustituir el nodo completo del usuario, aunque las reglas publicadas permitan escribirlo. */
    suspend fun uploadFull(
        uid: String,
        profile: UsuarioLocal,
        artifacts: List<ArtefactoLocal>,
        consumptions: List<ConsumoLocal>,
        meta: MetaLocal?
    ) {
        // Se usa solamente en la creación/inicialización, sin operaciones sobre la raíz.
        uploadProfile(uid, profile)
        artifacts.forEach { uploadArtifact(uid, it) }
        consumptions.forEach { uploadConsumption(uid, it) }
        if (meta != null) uploadMeta(uid, meta)
    }

    /** Operaciones por registro: un móvil no reemplaza la colección del otro. */
    suspend fun uploadArtifact(uid: String, artifact: ArtefactoLocal) {
        userRef(uid).child("artifacts").child(artifact.id.toString())
            .setValue(artifact.toMap()).awaitTask()
    }

    suspend fun deleteArtifact(uid: String, id: Int) {
        userRef(uid).child("artifacts").child(id.toString()).removeValue().awaitTask()
    }

    /** Escucha cambios de artefactos manteniendo el aislamiento por UID. */
    fun observeArtifacts(uid: String, onChange: (List<ArtefactoLocal>) -> Unit, onError: (DatabaseError) -> Unit): ValueEventListener {
        val ref = userRef(uid).child("artifacts")
        return object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val rows = snapshot.children.map { item ->
                        item.toArtifact() ?: error("Artefacto remoto inválido: ${item.key}")
                    }.sortedBy { it.id }
                    onChange(rows)
                } catch (e: Exception) {
                    Log.e("VoltifyFirebase", "Error al interpretar artefactos RTDB", e)
                }
            }
            override fun onCancelled(error: DatabaseError) = onError(error)
        }.also { ref.addValueEventListener(it) }
    }

    fun stopObservingArtifacts(uid: String, listener: ValueEventListener) {
        userRef(uid).child("artifacts").removeEventListener(listener)
    }

    /** No borra datos existentes: sólo notifica colecciones remotas verificadas. */
    fun observeConsumptions(
        uid: String,
        onChange: (List<ConsumoLocal>) -> Unit,
        onError: (DatabaseError) -> Unit
    ): ValueEventListener {
        val ref = userRef(uid).child("consumptions")
        return object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val rows = snapshot.children.map { item ->
                        item.toConsumption() ?: error("Consumo remoto inválido: ${item.key}")
                    }.sortedBy { it.fecha }
                    onChange(rows)
                } catch (e: Exception) {
                    Log.e("VoltifyFirebase", "Lectura de consumos inválida; se conserva caché", e)
                }
            }
            override fun onCancelled(error: DatabaseError) = onError(error)
        }.also { ref.addValueEventListener(it) }
    }

    fun stopObservingConsumptions(uid: String, listener: ValueEventListener) {
        userRef(uid).child("consumptions").removeEventListener(listener)
    }

    fun observeMeta(
        uid: String,
        onChange: (MetaLocal?) -> Unit,
        onError: (DatabaseError) -> Unit
    ): ValueEventListener {
        val ref = userRef(uid).child("meta")
        return object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onChange(snapshot.toMeta())
            override fun onCancelled(error: DatabaseError) = onError(error)
        }.also { ref.addValueEventListener(it) }
    }

    fun stopObservingMeta(uid: String, listener: ValueEventListener) {
        userRef(uid).child("meta").removeEventListener(listener)
    }

    suspend fun uploadProfile(uid: String, profile: UsuarioLocal) {
        db().reference.child("users").child(uid).child("profile").setValue(profile.toMap()).awaitTask()
    }

    suspend fun uploadArtifacts(uid: String, artifacts: List<ArtefactoLocal>) {
        // Conservado solo para compatibilidad; no reemplaza toda la colección.
        artifacts.forEach { uploadArtifact(uid, it) }
    }

    suspend fun uploadMeta(uid: String, meta: MetaLocal?) {
        db().reference.child("users").child(uid).child("meta").setValue(meta?.toMap()).awaitTask()
    }

    suspend fun uploadConsumptions(uid: String, rows: List<ConsumoLocal>) {
        // No sustituir toda la colección: el otro Android puede haber escrito nuevas lecturas.
        rows.forEach { uploadConsumption(uid, it) }
    }

    suspend fun uploadConsumption(uid: String, row: ConsumoLocal) {
        val ref = userRef(uid).child("consumptions").child(row.id.toString())
        if (row.tipoFuente == TipoFuenteMonitoreo.SIMULADA && VoltifyIds.isSimulatedBucketId(row.id)) {
            // Dos teléfonos pueden proponer el mismo bloque de simulación.
            // Una transacción conserva la contribución mayor en lugar de sumarlas.
            suspendCancellableCoroutine<Unit> { continuation ->
                ref.runTransaction(object : Transaction.Handler {
                    override fun doTransaction(currentData: MutableData): Transaction.Result {
                        val currentKwh = (currentData.child("consumoKwh").value as? Number)?.toDouble() ?: -1.0
                        if (currentData.value == null || row.consumoKwh > currentKwh) {
                            currentData.value = row.toMap()
                        }
                        return Transaction.success(currentData)
                    }

                    override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                        if (!continuation.isActive) return
                        when {
                            error != null -> continuation.resumeWithException(error.toException())
                            !committed -> continuation.resumeWithException(IllegalStateException("Transacción de consumo cancelada"))
                            else -> continuation.resume(Unit)
                        }
                    }
                }, false)
            }
        } else {
            ref.setValue(row.toMap()).awaitTask()
        }
    }

    class EmailNotVerifiedException : IllegalStateException("EMAIL_NOT_VERIFIED")

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    private fun UsuarioLocal.toMap(): Map<String, Any?> = mapOf(
        "nombre" to nombre,
        "correo" to correo,
        "telefono" to telefono,
        "comuna" to comuna,
        "empresa" to empresa,
        "usarTarifaManual" to usarTarifaManual,
        "valorKwhManual" to valorKwhManual,
        "cargoFijoManual" to cargoFijoManual
    )

    private fun ArtefactoLocal.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "nombre" to nombre,
        "categoria" to categoria,
        "marca" to marca,
        "modelo" to modelo,
        "potenciaW" to potenciaW,
        "activo" to activo,
        "monitoreando" to monitoreando,
        "fechaRegistro" to fechaRegistro,
        "fechaRetiro" to fechaRetiro,
        "tipoFuente" to tipoFuente.name
    )

    private fun ConsumoLocal.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "artefactoId" to artefactoId,
        "artefactoNombre" to artefactoNombre,
        "artefactoCategoria" to artefactoCategoria,
        "artefactoMarca" to artefactoMarca,
        "artefactoModelo" to artefactoModelo,
        "consumoKwh" to consumoKwh,
        "fecha" to fecha,
        "tipoFuente" to tipoFuente.name
    )

    private fun MetaLocal.toMap(): Map<String, Any?> = mapOf(
        "consumoKwh" to consumoKwh,
        "costoClp" to costoClp
    )

    private fun DataSnapshot.toUser(): UsuarioLocal? {
        if (!exists()) return null
        return UsuarioLocal(
            nombre = child("nombre").getValue(String::class.java).orEmpty(),
            correo = child("correo").getValue(String::class.java).orEmpty(),
            telefono = child("telefono").getValue(String::class.java).orEmpty(),
            comuna = child("comuna").getValue(String::class.java).orEmpty(),
            empresa = child("empresa").getValue(String::class.java).orEmpty(),
            usarTarifaManual = child("usarTarifaManual").getValue(Boolean::class.java) ?: false,
            valorKwhManual = child("valorKwhManual").getValue(Double::class.java),
            cargoFijoManual = child("cargoFijoManual").getValue(Double::class.java)
        )
    }

    private fun DataSnapshot.toArtifact(): ArtefactoLocal? = runCatching {
        ArtefactoLocal(
            id = child("id").getValue(Long::class.java)?.toInt() ?: key?.toIntOrNull() ?: return null,
            nombre = child("nombre").getValue(String::class.java).orEmpty(),
            categoria = child("categoria").getValue(String::class.java).orEmpty(),
            marca = child("marca").getValue(String::class.java).orEmpty(),
            modelo = child("modelo").getValue(String::class.java).orEmpty(),
            potenciaW = child("potenciaW").getValue(Double::class.java) ?: 0.0,
            activo = child("activo").getValue(Boolean::class.java) ?: true,
            monitoreando = child("monitoreando").getValue(Boolean::class.java) ?: false,
            fechaRegistro = child("fechaRegistro").getValue(Long::class.java) ?: 0L,
            fechaRetiro = child("fechaRetiro").getValue(Long::class.java),
            tipoFuente = runCatching {
                TipoFuenteMonitoreo.valueOf(child("tipoFuente").getValue(String::class.java).orEmpty())
            }.getOrDefault(TipoFuenteMonitoreo.SIMULADA)
        )
    }.getOrNull()

    private fun DataSnapshot.toConsumption(): ConsumoLocal? = runCatching {
        ConsumoLocal(
            id = child("id").getValue(Long::class.java)?.toInt() ?: key?.toIntOrNull() ?: return null,
            artefactoId = child("artefactoId").getValue(Long::class.java)?.toInt() ?: return null,
            artefactoNombre = child("artefactoNombre").getValue(String::class.java).orEmpty(),
            artefactoCategoria = child("artefactoCategoria").getValue(String::class.java).orEmpty(),
            artefactoMarca = child("artefactoMarca").getValue(String::class.java).orEmpty(),
            artefactoModelo = child("artefactoModelo").getValue(String::class.java).orEmpty(),
            consumoKwh = child("consumoKwh").getValue(Double::class.java) ?: 0.0,
            fecha = child("fecha").getValue(Long::class.java) ?: 0L,
            tipoFuente = runCatching {
                TipoFuenteMonitoreo.valueOf(child("tipoFuente").getValue(String::class.java).orEmpty())
            }.getOrDefault(TipoFuenteMonitoreo.SIMULADA)
        )
    }.getOrNull()

    private fun DataSnapshot.toMeta(): MetaLocal? {
        if (!exists()) return null
        return MetaLocal(
            consumoKwh = child("consumoKwh").getValue(Double::class.java),
            costoClp = child("costoClp").getValue(Double::class.java)
        )
    }
}

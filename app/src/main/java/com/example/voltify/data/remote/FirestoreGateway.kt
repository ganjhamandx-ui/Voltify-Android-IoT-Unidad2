package com.example.voltify.data.remote

import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.UsuarioLocal
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Escritura de metadatos estaticos en Cloud Firestore (Unidad 3).
 *
 * Ruta de perfiles: users/{uid}
 * Ruta de dispositivos: users/{uid}/devices/{deviceId}
 *
 * No escribe lecturas, consumos, estados instantaneos ni comandos.
 * Esos datos siguen en Firebase Realtime Database. No migra ni borra
 * informacion existente automaticamente.
 */
class FirestoreGateway {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private fun requireOwnerVerified(uid: String) {
        val currentUser = auth.currentUser
            ?: throw IllegalStateException("Se requiere una sesion de Firebase")

        require(currentUser.uid == uid) { "El UID no corresponde al usuario autenticado" }
        check(currentUser.isEmailVerified) { "Debes verificar el correo antes de sincronizar" }
    }

    /** Guarda el perfil sin contrasena, usando la identidad autenticada. */
    suspend fun saveProfile(uid: String, profile: UsuarioLocal) {
        requireOwnerVerified(uid)

        val fields: Map<String, Any?> = mapOf(
            "nombre" to profile.nombre,
            "correo" to profile.correo,
            "telefono" to profile.telefono,
            "comuna" to profile.comuna,
            "empresa" to profile.empresa,
            "usarTarifaManual" to profile.usarTarifaManual,
            "valorKwhManual" to profile.valorKwhManual,
            "cargoFijoManual" to profile.cargoFijoManual
        )

        db.collection("users")
            .document(uid)
            .set(fields, SetOptions.merge())
            .awaitTask()
    }

    /**
     * Guarda solamente atributos descriptivos y estado de vida del artefacto.
     * No guarda 'monitoreando' ni lecturas: son datos dinamicos de RTDB.
     */
    suspend fun saveDevice(uid: String, artifact: ArtefactoLocal) {
        requireOwnerVerified(uid)

        val fields: Map<String, Any?> = mapOf(
            "id" to artifact.id,
            "nombre" to artifact.nombre,
            "categoria" to artifact.categoria,
            "marca" to artifact.marca,
            "modelo" to artifact.modelo,
            "potenciaW" to artifact.potenciaW,
            "fechaRegistro" to artifact.fechaRegistro,
            "fechaRetiro" to artifact.fechaRetiro,
            "tipoFuente" to artifact.tipoFuente.name,
            "activo" to artifact.activo
        )

        db.collection("users")
            .document(uid)
            .collection("devices")
            .document(artifact.id.toString())
            .set(fields, SetOptions.merge())
            .awaitTask()
    }

    /** Elimina solo el documento descriptivo del dispositivo indicado. */
    suspend fun deleteDevice(uid: String, deviceId: Int) {
        requireOwnerVerified(uid)
        db.collection("users")
            .document(uid)
            .collection("devices")
            .document(deviceId.toString())
            .delete()
            .awaitTask()
    }

    /** Adaptador sin dependencias adicionales a kotlinx-coroutines-play-services. */
    private suspend fun <T> Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel() }
        }
}

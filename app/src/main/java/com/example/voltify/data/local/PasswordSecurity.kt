package com.example.voltify.data.local

import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Política y hashing local usados solo cuando Firebase todavía no está configurado. */
object PasswordSecurity {
    const val MIN_LENGTH = 12
    private const val MAX_LENGTH = 64
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256

    private val commonPasswords = setOf(
        "password123!", "password1234!", "qwerty123456!", "admin123456!",
        "voltify123456!", "123456789012!", "aaaaaaaaaaaa1!"
    )

    data class Validation(val valid: Boolean, val message: String)

    data class HashRecord(
        val algorithm: String,
        val iterations: Int,
        val saltBase64: String,
        val hashBase64: String,
        val failedAttempts: Int = 0,
        val lockUntil: Long = 0L
    )

    fun validate(password: String, email: String = "", name: String = ""): Validation {
        if (password.length < MIN_LENGTH) return Validation(false, "Usa al menos $MIN_LENGTH caracteres")
        if (password.length > MAX_LENGTH) return Validation(false, "La contraseña no puede superar $MAX_LENGTH caracteres")
        if (password != password.trim()) return Validation(false, "No uses espacios al inicio o al final")
        if (!password.any { it.isUpperCase() }) return Validation(false, "Agrega al menos una mayúscula")
        if (!password.any { it.isLowerCase() }) return Validation(false, "Agrega al menos una minúscula")
        if (!password.any { it.isDigit() }) return Validation(false, "Agrega al menos un número")
        if (!password.any { !it.isLetterOrDigit() }) return Validation(false, "Agrega al menos un símbolo")

        val lower = password.lowercase()
        if (lower in commonPasswords) return Validation(false, "Elige una contraseña menos predecible")

        val localPart = email.substringBefore('@').trim().lowercase()
        if (localPart.length >= 4 && lower.contains(localPart)) {
            return Validation(false, "La contraseña no debe contener tu correo")
        }
        val normalizedName = name.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 4 }
        if (normalizedName.any { lower.contains(it) }) return Validation(false, "La contraseña no debe contener tu nombre")

        return Validation(true, "Contraseña segura")
    }

    fun createHash(password: String): HashRecord {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val algorithm = preferredAlgorithm()
        val hash = derive(password, salt, ITERATIONS, algorithm)
        return HashRecord(
            algorithm = algorithm,
            iterations = ITERATIONS,
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            hashBase64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        )
    }

    fun verify(password: String, record: HashRecord): Boolean {
        val salt = Base64.decode(record.saltBase64, Base64.NO_WRAP)
        val expected = Base64.decode(record.hashBase64, Base64.NO_WRAP)
        val actual = derive(password, salt, record.iterations, record.algorithm)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun preferredAlgorithm(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun derive(password: String, salt: ByteArray, iterations: Int, algorithm: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

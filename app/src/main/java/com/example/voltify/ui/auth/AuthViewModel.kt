package com.example.voltify.ui.auth

import android.app.Application
import com.example.voltify.data.local.PasswordSecurity
import com.example.voltify.model.AuthOutcome
import com.example.voltify.model.UsuarioLocal
import com.example.voltify.ui.BaseVoltifyViewModel

class AuthViewModel(application: Application) : BaseVoltifyViewModel(application) {
    val comunas get() = repository.comunas
    val empresas get() = repository.empresas

    fun refresh() = repository.refresh()
    suspend fun login(correo: String, password: String): AuthOutcome = repository.login(correo, password)
    suspend fun register(user: UsuarioLocal, password: String): AuthOutcome = repository.register(user, password)
    suspend fun beginRecovery(email: String): AuthOutcome = repository.beginRecovery(email)
    fun verifyRecoveryCode(code: String) = repository.verifyRecoveryCode(code)
    suspend fun finishRecovery(password: String): AuthOutcome = repository.finishRecovery(password)
    fun passwordValidation(password: String, email: String = "", name: String = "") =
        PasswordSecurity.validate(password, email, name)
    fun authModeLabel(): String = repository.authModeLabel()
}

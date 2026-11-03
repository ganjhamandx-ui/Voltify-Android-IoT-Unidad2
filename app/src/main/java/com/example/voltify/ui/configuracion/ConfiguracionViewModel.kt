package com.example.voltify.ui.configuracion

import android.app.Application
import com.example.voltify.model.UsuarioLocal
import com.example.voltify.ui.BaseVoltifyViewModel

class ConfiguracionViewModel(application: Application) : BaseVoltifyViewModel(application) {
    val comunas: List<String> get() = repository.comunas
    val empresas: List<String> get() = repository.empresas
    fun user(): UsuarioLocal = repository.getUser()
    fun save(user: UsuarioLocal) = repository.saveUser(user)
    fun tariff() = repository.tariff()
    fun formatClp(value: Double) = repository.formatClp(value)
    fun authModeLabel() = repository.authModeLabel()
    fun isCloudConfigured() = repository.isCloudConfigured()
    fun logout() = repository.logout()
}

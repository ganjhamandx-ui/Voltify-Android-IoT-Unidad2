package com.example.voltify

import android.os.Bundle
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voltify.model.AuthStatus
import com.example.voltify.ui.auth.AuthViewModel
import kotlinx.coroutines.launch

class NuevaContrasenaActivity : AppCompatActivity() {
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nueva_contrasena)
        applySystemBars(findViewById(R.id.main))
        findViewById<android.view.View>(R.id.btnGuardar).setOnClickListener {
            val password = findViewById<EditText>(R.id.etNueva).text.toString()
            val confirmation = findViewById<EditText>(R.id.etConfirmar).text.toString()
            val validation = viewModel.passwordValidation(password)
            when {
                !validation.valid -> toast(validation.message)
                password != confirmation -> toast("Las contraseñas no coinciden")
                else -> lifecycleScope.launch {
                    val result = viewModel.finishRecovery(password)
                    if (result.status == AuthStatus.SUCCESS) {
                        toast("Contraseña actualizada de forma segura")
                        goToLoginAndClearTask()
                    } else {
                        toast(result.message.ifBlank { "No fue posible actualizar la contraseña" })
                    }
                }
            }
        }
    }
}

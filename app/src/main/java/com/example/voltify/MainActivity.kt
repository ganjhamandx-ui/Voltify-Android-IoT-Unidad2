package com.example.voltify

import android.os.Bundle
import android.util.Patterns
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voltify.model.AuthStatus
import com.example.voltify.ui.auth.AuthViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.refresh()
        setContentView(R.layout.activity_main)
        applySystemBars(findViewById(R.id.main))

        findViewById<android.view.View>(R.id.btnIngresar).setOnClickListener {
            val correo = findViewById<EditText>(R.id.etCorreo).text.toString().trim()
            val pass = findViewById<EditText>(R.id.etContrasena).text.toString()
            when {
                !Patterns.EMAIL_ADDRESS.matcher(correo).matches() -> toast(getString(R.string.toast_invalid_email))
                pass.isBlank() -> toast(getString(R.string.toast_enter_password))
                else -> lifecycleScope.launch {
                    // Evita peticiones de autenticación duplicadas por pulsaciones repetidas.
                    val button = findViewById<android.view.View>(R.id.btnIngresar)
                    button.isEnabled = false
                    try {
                        val result = viewModel.login(correo, pass)
                        when (result.status) {
                            AuthStatus.SUCCESS -> goToDashboardAndClearTask()
                            AuthStatus.ACCOUNT_LOCKED -> toast("Demasiados intentos. Reintenta en ${result.retryAfterSeconds} s")
                            AuthStatus.EMAIL_NOT_VERIFIED -> toast("Verifica tu correo antes de iniciar sesión")
                            AuthStatus.PASSWORD_INVALID -> toast(result.message)
                            AuthStatus.NETWORK_ERROR -> toast("Sin conexión segura con Firebase")
                            else -> toast(getString(R.string.toast_invalid_login))
                        }
                    } finally {
                        button.isEnabled = true
                    }
                }
            }
        }
        findViewById<android.view.View>(R.id.tvCrearCuenta).setOnClickListener { openScreen<RegistroActivity>() }
        findViewById<android.view.View>(R.id.tvRecuperarContrasena).setOnClickListener { openScreen<RecuperarContrasenaActivity>() }
    }
}

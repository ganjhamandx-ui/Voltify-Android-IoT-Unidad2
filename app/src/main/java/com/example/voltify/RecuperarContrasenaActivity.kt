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

class RecuperarContrasenaActivity : AppCompatActivity() {
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recuperar_contrasena)
        applySystemBars(findViewById(R.id.main))

        findViewById<android.view.View>(R.id.btnEnviarCodigo).setOnClickListener {
            val email = findViewById<EditText>(R.id.etCorreo).text.toString().trim()

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                toast("Ingresa un correo válido")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val result = viewModel.beginRecovery(email)
                when (result.status) {
                    AuthStatus.RECOVERY_CODE_GENERATED -> {
                        toast("Si la cuenta existe, se generó un código de recuperación")
                        openScreen<VerificarCodigoActivity>()
                    }

                    AuthStatus.RECOVERY_RATE_LIMITED -> {
                        val seconds = result.retryAfterSeconds.coerceAtLeast(1)
                        toast("Espera $seconds segundos antes de solicitar otro código")
                    }

                    AuthStatus.RESET_EMAIL_SENT -> {
                        toast("Si la cuenta existe, recibirás instrucciones de recuperación")
                        goToLoginAndClearTask()
                    }

                    AuthStatus.NETWORK_ERROR -> toast("No fue posible conectar con el servicio de recuperación")
                    else -> toast("Si la cuenta existe, podrás continuar con la recuperación")
                }
            }
        }

        findViewById<android.view.View>(R.id.tvVolverLogin).setOnClickListener { finish() }
    }

}

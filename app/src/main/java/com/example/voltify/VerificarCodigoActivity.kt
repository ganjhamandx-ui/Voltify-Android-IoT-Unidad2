package com.example.voltify

import android.os.Bundle
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.voltify.ui.auth.AuthViewModel

class VerificarCodigoActivity : AppCompatActivity() {
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verificar_codigo)
        applySystemBars(findViewById(R.id.main))
        findViewById<android.view.View>(R.id.btnVerificar).setOnClickListener {
            val code = findViewById<EditText>(R.id.etCodigo).text.toString()
            if (viewModel.verifyRecoveryCode(code)) openScreen<NuevaContrasenaActivity>() else toast("Código incorrecto")
        }
    }
}

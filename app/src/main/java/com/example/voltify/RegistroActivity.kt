package com.example.voltify

import android.os.Bundle
import android.util.Patterns
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voltify.model.AuthStatus
import com.example.voltify.ui.auth.AuthViewModel
import kotlinx.coroutines.launch

class RegistroActivity : AppCompatActivity() {
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registro)
        applySystemBars(findViewById(R.id.main))

        val acComuna = findViewById<AutoCompleteTextView>(R.id.acComuna)
        val acEmpresa = findViewById<AutoCompleteTextView>(R.id.acEmpresa)
        acComuna.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, viewModel.comunas))
        acEmpresa.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, viewModel.empresas))
        acComuna.setOnClickListener { acComuna.showDropDown() }
        acEmpresa.setOnClickListener { acEmpresa.showDropDown() }

        findViewById<android.view.View>(R.id.btnRegistrar).setOnClickListener {
            val nombre = findViewById<EditText>(R.id.etNombre).text.toString().trim()
            val correo = findViewById<EditText>(R.id.etCorreo).text.toString().trim()
            val tel = findViewById<EditText>(R.id.etTelefono).text.toString().trim()
            val pass = findViewById<EditText>(R.id.etContrasena).text.toString()
            val conf = findViewById<EditText>(R.id.etConfirmar).text.toString()
            val comuna = acComuna.text.toString()
            val empresa = acEmpresa.text.toString()
            val passwordCheck = viewModel.passwordValidation(pass, correo, nombre)

            when {
                nombre.length !in 3..100 -> toast("Tu nombre debe tener entre 3 y 100 caracteres")
                tel.length > 30 -> toast("El teléfono no puede superar 30 caracteres")
                !Patterns.EMAIL_ADDRESS.matcher(correo).matches() -> toast("Ingresa un correo válido")
                comuna !in viewModel.comunas -> toast("Selecciona una comuna")
                empresa !in viewModel.empresas -> toast("Selecciona una empresa eléctrica")
                !passwordCheck.valid -> toast(passwordCheck.message)
                pass != conf -> toast("Las contraseñas no coinciden")
                else -> lifecycleScope.launch {
                    val user = UsuarioLocal(nombre, correo, tel, comuna, empresa, false, null, null)
                    val result = viewModel.register(user, pass)
                    when (result.status) {
                        AuthStatus.SUCCESS -> {
                            toast("Cuenta creada correctamente")
                            goToDashboardAndClearTask()
                        }
                        AuthStatus.EMAIL_NOT_VERIFIED -> {
                            toast("Cuenta creada. Revisa tu correo y verifica la cuenta")
                            goToLoginAndClearTask()
                        }
                        AuthStatus.EMAIL_ALREADY_USED -> toast("Ese correo ya está registrado")
                        AuthStatus.PASSWORD_INVALID -> toast(result.message)
                        AuthStatus.NETWORK_ERROR -> toast("No fue posible conectar con Firebase")
                        else -> toast(result.message.ifBlank { "No fue posible crear la cuenta" })
                    }
                }
            }
        }
        findViewById<android.view.View>(R.id.tvVolverLogin).setOnClickListener { finish() }
    }
}

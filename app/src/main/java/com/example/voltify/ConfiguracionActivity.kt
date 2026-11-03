package com.example.voltify

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.voltify.ui.configuracion.ConfiguracionViewModel

class ConfiguracionActivity : AppCompatActivity() {
    private val viewModel: ConfiguracionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuracion)
        applySystemBars(findViewById(R.id.main))

        val comuna = findViewById<AutoCompleteTextView>(R.id.acComuna)
        val empresa = findViewById<AutoCompleteTextView>(R.id.acEmpresa)
        comuna.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, viewModel.comunas))
        empresa.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, viewModel.empresas))
        comuna.setOnClickListener { comuna.showDropDown() }
        empresa.setOnClickListener { empresa.showDropDown() }

        val manualCheck = findViewById<CheckBox>(R.id.cbTarifaManual)
        fun toggleManualFields() {
            findViewById<EditText>(R.id.etKwh).isEnabled = manualCheck.isChecked
            findViewById<EditText>(R.id.etCargoFijo).isEnabled = manualCheck.isChecked
        }
        manualCheck.setOnCheckedChangeListener { _, _ -> toggleManualFields() }

        val user = viewModel.user()
        findViewById<EditText>(R.id.etNombre).setText(user.nombre)
        findViewById<EditText>(R.id.etCorreo).setText(user.correo)
        findViewById<EditText>(R.id.etTelefono).setText(user.telefono)
        comuna.setText(user.comuna, false)
        empresa.setText(user.empresa, false)
        manualCheck.isChecked = user.usarTarifaManual
        findViewById<EditText>(R.id.etKwh).setText(user.valorKwhManual?.toString() ?: "")
        findViewById<EditText>(R.id.etCargoFijo).setText(user.cargoFijoManual?.toString() ?: "")
        toggleManualFields()
        renderTarifa()
        findViewById<TextView>(R.id.tvEstadoFirebase).text =
            "Autenticación / datos: ${viewModel.authModeLabel()}"

        findViewById<android.view.View>(R.id.btnGuardar).setOnClickListener {
            val manual = manualCheck.isChecked
            val kwh = findViewById<EditText>(R.id.etKwh).text.toString().toDoubleOrNull()
            val fixed = findViewById<EditText>(R.id.etCargoFijo).text.toString().toDoubleOrNull()
            val nuevoNombre = findViewById<EditText>(R.id.etNombre).text.toString().trim()
            val nuevoTelefono = findViewById<EditText>(R.id.etTelefono).text.toString().trim()
            if (nuevoNombre.length !in 3..100 || nuevoTelefono.length > 30) {
                toast("Revisa el nombre (3-100 caracteres) y teléfono (máximo 30)")
            } else if (comuna.text.toString() !in viewModel.comunas || empresa.text.toString() !in viewModel.empresas) {
                toast("Selecciona comuna y empresa")
            } else if (manual && (kwh == null || !kwh.isFinite() || kwh <= 0 ||
                    fixed == null || !fixed.isFinite() || fixed < 0)) {
                toast("Completa correctamente la tarifa manual")
            } else {
                viewModel.save(
                    user.copy(
                        nombre = nuevoNombre,
                        correo = user.correo,
                        telefono = nuevoTelefono,
                        comuna = comuna.text.toString(),
                        empresa = empresa.text.toString(),
                        usarTarifaManual = manual,
                        valorKwhManual = if (manual) kwh else null,
                        cargoFijoManual = if (manual) fixed else null
                    )
                )
                toast(getString(R.string.toast_config_saved))
                renderTarifa()
            }
        }

        findViewById<android.view.View>(R.id.btnVolver).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnCerrarSesion).setOnClickListener {
            viewModel.logout()
            goToLoginAndClearTask()
        }
    }

    private fun renderTarifa() {
        val tariff = viewModel.tariff()
        findViewById<TextView>(R.id.tvTarifaActual).text =
            "Tarifa utilizada actualmente: ${tariff.fuente}\n${String.format("%.2f", tariff.valorKwh)} \$/kWh · ${viewModel.formatClp(tariff.cargoFijo)} fijo mensual"
    }
}

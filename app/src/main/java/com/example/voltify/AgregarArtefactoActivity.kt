package com.example.voltify

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.ui.artefactos.ArtefactosViewModel

class AgregarArtefactoActivity : AppCompatActivity() {
    private val viewModel: ArtefactosViewModel by viewModels()
    private var id: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agregar_artefacto)
        applySystemBars(findViewById(R.id.main))

        id = intent.getIntExtra("artefacto_id", -1).takeIf { it > 0 }

        val category = findViewById<AutoCompleteTextView>(R.id.acCategoria)
        category.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, viewModel.categorias))
        category.setOnClickListener { category.showDropDown() }

        val source = findViewById<AutoCompleteTextView>(R.id.acFuente)
        val sourceLabels = listOf(getString(R.string.source_simulated), getString(R.string.source_iot_real))
        source.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sourceLabels))
        source.setText(getString(R.string.source_simulated), false)
        source.setOnClickListener { source.showDropDown() }

        id?.let { artifactId ->
            viewModel.getArtifact(artifactId)?.let { artifact ->
                findViewById<TextView>(R.id.tvTitulo).text = getString(R.string.edit_device_title)
                findViewById<EditText>(R.id.etNombre).setText(artifact.nombre)
                category.setText(artifact.categoria, false)
                findViewById<EditText>(R.id.etMarca).setText(artifact.marca)
                findViewById<EditText>(R.id.etModelo).setText(artifact.modelo)
                findViewById<EditText>(R.id.etPotencia).setText(artifact.potenciaW.toString())
                source.setText(sourceLabel(artifact.tipoFuente), false)
            }
        }

        findViewById<android.view.View>(R.id.btnGuardar).setOnClickListener {
            val name = findViewById<EditText>(R.id.etNombre).text.toString().trim()
            val categoryValue = category.text.toString()
            val brand = findViewById<EditText>(R.id.etMarca).text.toString().trim()
            val model = findViewById<EditText>(R.id.etModelo).text.toString().trim()
            val power = findViewById<EditText>(R.id.etPotencia).text.toString().toDoubleOrNull()
            val sourceType = sourceType(source.text.toString())

            when {
                name.isBlank() || name.length > 100 -> toast("El nombre debe tener entre 1 y 100 caracteres")
                categoryValue !in viewModel.categorias -> toast(getString(R.string.toast_select_category))
                brand.isBlank() || brand.length > 100 -> toast("La marca debe tener entre 1 y 100 caracteres")
                model.isBlank() || model.length > 100 -> toast("El modelo debe tener entre 1 y 100 caracteres")
                power == null || !power.isFinite() || power <= 0.0 || power > 50_000.0 -> toast("Ingresa una potencia válida entre 0 y 50.000 W")
                sourceType == null -> toast(getString(R.string.select_monitoring_source))
                else -> {
                    viewModel.saveArtifact(id, name, categoryValue, brand, model, power, sourceType)
                    toast(if (id == null) "Artefacto registrado" else "Artefacto actualizado")
                    finish()
                }
            }
        }
        findViewById<android.view.View>(R.id.btnCancelar).setOnClickListener { finish() }
    }

    private fun sourceType(label: String): TipoFuenteMonitoreo? = when (label) {
        getString(R.string.source_simulated) -> TipoFuenteMonitoreo.SIMULADA
        getString(R.string.source_iot_real) -> TipoFuenteMonitoreo.IOT_REAL
        else -> null
    }

    private fun sourceLabel(source: TipoFuenteMonitoreo): String = when (source) {
        TipoFuenteMonitoreo.SIMULADA -> getString(R.string.source_simulated)
        TipoFuenteMonitoreo.IOT_REAL -> getString(R.string.source_iot_real)
    }
}

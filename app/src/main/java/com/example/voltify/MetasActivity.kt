package com.example.voltify

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.voltify.ui.metas.MetasViewModel

class MetasActivity : AppCompatActivity() {
    private val viewModel: MetasViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_metas)
        applySystemBars(findViewById(R.id.main))

        findViewById<android.view.View>(R.id.btnGuardarMeta).setOnClickListener {
            val kwh = findViewById<EditText>(R.id.etMetaKwh).text.toString().toDoubleOrNull()
            val clp = findViewById<EditText>(R.id.etMetaClp).text.toString().toDoubleOrNull()
            when {
                kwh == null && clp == null -> toast("Ingresa al menos una meta")
                (kwh != null && (!kwh.isFinite() || kwh <= 0)) ||
                    (clp != null && (!clp.isFinite() || clp <= 0)) -> toast("Las metas deben ser finitas y mayores a cero")
                else -> {
                    viewModel.save(kwh, clp)
                    toast("Meta mensual guardada")
                    render()
                }
            }
        }

        findViewById<android.view.View>(R.id.btnEliminarMeta).setOnClickListener {
            val meta = viewModel.state().meta
            if (meta == null) {
                toast("No existe una meta mensual para eliminar")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Eliminar meta mensual")
                    .setMessage(
                        "Esta acción eliminará definitivamente la meta del mes actual.\n\n" +
                            "Consumo: ${meta.consumoKwh?.let { viewModel.formatKwh(it) } ?: "Sin definir"}\n" +
                            "Costo: ${meta.costoClp?.let { viewModel.formatClp(it) } ?: "Sin definir"}\n\n" +
                            "¿Confirmas la eliminación?"
                    )
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewModel.delete()
                        toast("Meta mensual eliminada")
                        render()
                    }
                    .show()
            }
        }

        findViewById<android.view.View>(R.id.btnVolver).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        val state = viewModel.state()
        val meta = state.meta
        findViewById<EditText>(R.id.etMetaKwh).setText(meta?.consumoKwh?.toString() ?: "")
        findViewById<EditText>(R.id.etMetaClp).setText(meta?.costoClp?.toString() ?: "")
        findViewById<TextView>(R.id.tvEstadoMeta).text =
            "Fecha Chile: ${state.fechaChile}\n" +
                "Consumo actual: ${viewModel.formatKwh(state.consumoActual)}\n" +
                "Costo actual: ${viewModel.formatClp(state.costoActual)}\n" +
                "Meta consumo: ${meta?.consumoKwh?.let { viewModel.formatKwh(it) } ?: "Sin definir"}\n" +
                "Meta costo: ${meta?.costoClp?.let { viewModel.formatClp(it) } ?: "Sin definir"}"

        findViewById<ProgressBar>(R.id.pbMeta).progress = state.progreso.coerceIn(0, 100)
        findViewById<TextView>(R.id.tvProgreso).text = "${state.progreso}% de la meta de consumo"
        findViewById<android.view.View>(R.id.btnEliminarMeta).isEnabled = meta != null
        findViewById<android.view.View>(R.id.btnEliminarMeta).alpha = if (meta != null) 1f else 0.45f
    }
}

package com.example.voltify

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.MonitoreoLecturaActual
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.ui.artefactos.ArtefactosViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DispositivosActivityVoltify : AppCompatActivity() {
    private val viewModel: ArtefactosViewModel by viewModels()
    private lateinit var box: LinearLayout
    private var filter = "todos"
    private var query = ""

    // Las lecturas en vivo actualizan solo estos textos. No se reconstruye la lista
    // ni los botones cada 3 segundos, por lo que Editar/Detener/Retirar/Eliminar
    // permanecen estables y clickeables durante la simulación.
    private val powerViews = mutableMapOf<Int, TextView>()
    private val incrementViews = mutableMapOf<Int, TextView>()
    private val timeViews = mutableMapOf<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dispositivos_voltify)
        applySystemBars(findViewById(R.id.main))
        box = findViewById(R.id.llArtefactos)

        findViewById<android.view.View>(R.id.btnAgregar).setOnClickListener { openScreen<AgregarArtefactoActivity>() }
        findViewById<android.view.View>(R.id.btnVolver).setOnClickListener { finish() }

        mapOf(
            R.id.btnFiltroTodos to "todos",
            R.id.btnFiltroMonitoreando to "monitoreando",
            R.id.btnFiltroDetenidos to "detenidos",
            R.id.btnFiltroRetirados to "retirados"
        ).forEach { (id, value) ->
            findViewById<android.view.View>(id).setOnClickListener {
                filter = value
                renderFiltered()
            }
        }

        findViewById<EditText>(R.id.etBuscarArtefacto).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                renderFiltered()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.artifacts.collect { renderFiltered() } }
                launch { viewModel.liveReadings.collect { updateLiveReadingViews(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startLiveSimulation()
    }

    override fun onStop() {
        viewModel.pauseLiveSimulation()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun renderFiltered() {
        render(viewModel.filterArtifacts(filter, query))
    }

    private fun render(items: List<ArtefactoLocal>) {
        box.removeAllViews()
        powerViews.clear()
        incrementViews.clear()
        timeViews.clear()

        if (items.isEmpty()) {
            box.addView(makeText(getString(R.string.no_devices_filter), 15f))
            return
        }

        items.forEach { artifact ->
            val panel = makePanel()
            panel.addView(makeText(artifact.nombre, 18f, R.color.voltify_text_primary, true))
            panel.addView(makeText("${artifact.categoria} · ${artifact.marca} ${artifact.modelo} · ${String.format("%.0f", artifact.potenciaW)} W", 12f))
            panel.addView(
                makeText(
                    sourceLabel(artifact.tipoFuente),
                    12f,
                    if (artifact.tipoFuente == TipoFuenteMonitoreo.IOT_REAL) R.color.voltify_cyan else R.color.voltify_purple,
                    true
                )
            )

            val status = when {
                !artifact.activo -> getString(R.string.status_retired)
                artifact.monitoreando -> getString(R.string.status_monitoring)
                else -> getString(R.string.status_stopped)
            }
            val statusColor = when {
                !artifact.activo -> R.color.voltify_warning
                artifact.monitoreando -> R.color.voltify_success
                else -> R.color.voltify_text_secondary
            }
            panel.addView(makeText(status, 12f, statusColor, true))
            panel.addView(makeText("Mes: ${viewModel.formatKwh(viewModel.artifactKwhMonth(artifact.id))}", 12f))

            if (artifact.activo) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                }
                val monitorButton = makeActionButton(if (artifact.monitoreando) "Detener" else "Monitorear", primary = !artifact.monitoreando)
                monitorButton.setOnClickListener {
                    viewModel.setMonitoring(artifact.id, !artifact.monitoreando)
                }
                val editButton = makeActionButton("Editar")
                editButton.setOnClickListener {
                    startActivity(Intent(this, AgregarArtefactoActivity::class.java).putExtra("artefacto_id", artifact.id))
                }
                row.addView(monitorButton)
                row.addView(editButton)
                panel.addView(row)

                if (artifact.tipoFuente == TipoFuenteMonitoreo.SIMULADA && artifact.monitoreando) {
                    panel.addView(makeText(getString(R.string.simulation_live_title), 11f, R.color.voltify_purple, true))
                    val reading = viewModel.liveReadingFor(artifact.id)
                    val power = makeText(
                        if (reading == null) getString(R.string.simulation_live_preparing)
                        else getString(R.string.simulation_live_power, reading.potenciaW),
                        12f
                    )
                    val increment = makeText(
                        if (reading == null) "" else getString(R.string.simulation_live_increment, reading.consumoIncrementalKwh),
                        12f
                    )
                    val time = makeText(
                        if (reading == null) "" else getString(R.string.simulation_live_time, viewModel.formatChileDate(reading.timestamp)),
                        11f
                    )
                    powerViews[artifact.id] = power
                    incrementViews[artifact.id] = increment
                    timeViews[artifact.id] = time
                    panel.addView(power)
                    panel.addView(increment)
                    panel.addView(time)
                } else if (artifact.tipoFuente == TipoFuenteMonitoreo.IOT_REAL && artifact.monitoreando) {
                    panel.addView(makeText(getString(R.string.iot_waiting_readings), 11f, R.color.voltify_cyan))
                }

                val retireButton = makeActionButton("Retirar", danger = true).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) }
                }
                retireButton.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Retirar artefacto")
                        .setMessage(getString(R.string.retire_artifact_message_v8, artifact.nombre))
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Retirar") { _, _ -> viewModel.retireArtifact(artifact.id) }
                        .show()
                }
                panel.addView(retireButton)
            }

            val deleteButton = makeActionButton(getString(R.string.delete_permanently), danger = true).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) }
            }
            deleteButton.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_artifact_title))
                    .setMessage(getString(R.string.delete_artifact_message, artifact.nombre))
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewModel.deleteArtifactPermanently(artifact.id)
                        toast(getString(R.string.delete_artifact_success))
                    }
                    .show()
            }
            panel.addView(deleteButton)
            box.addView(panel)
        }

        updateLiveReadingViews(viewModel.liveReadings.value)
    }

    private fun updateLiveReadingViews(readings: Map<Int, MonitoreoLecturaActual>) {
        readings.forEach { (artifactId, reading) ->
            powerViews[artifactId]?.text = getString(R.string.simulation_live_power, reading.potenciaW)
            incrementViews[artifactId]?.text = getString(R.string.simulation_live_increment, reading.consumoIncrementalKwh)
            timeViews[artifactId]?.text = getString(R.string.simulation_live_time, viewModel.formatChileDate(reading.timestamp))
        }
    }

    private fun sourceLabel(source: TipoFuenteMonitoreo): String = when (source) {
        TipoFuenteMonitoreo.SIMULADA -> getString(R.string.source_simulated)
        TipoFuenteMonitoreo.IOT_REAL -> getString(R.string.source_iot_real)
    }
}

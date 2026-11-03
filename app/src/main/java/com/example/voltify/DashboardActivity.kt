package com.example.voltify

import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.ui.dashboard.DashboardUiState
import com.example.voltify.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        applySystemBars(findViewById(R.id.main))

        findViewById<android.view.View>(R.id.btnArtefactos).setOnClickListener { openScreen<DispositivosActivityVoltify>() }
        findViewById<android.view.View>(R.id.btnConsumos).setOnClickListener { openScreen<HistorialActivity>() }
        findViewById<android.view.View>(R.id.btnMetas).setOnClickListener { openScreen<MetasActivity>() }
        findViewById<android.view.View>(R.id.btnConsejos).setOnClickListener { openScreen<ConsejosActivity>() }
        findViewById<android.view.View>(R.id.btnReportes).setOnClickListener { openScreen<ReportesActivity>() }
        findViewById<android.view.View>(R.id.btnConfiguracion).setOnClickListener { openScreen<ConfiguracionActivity>() }
        findViewById<android.view.View>(R.id.btnCerrarSesionDashboard).setOnClickListener {
            viewModel.logout()
            goToLoginAndClearTask()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(state: DashboardUiState) {
        findViewById<TextView>(R.id.tvFechaChile).text = state.fechaChile
        findViewById<TextView>(R.id.tvHello).text = "¡Hola, ${state.primerNombre}!"
        findViewById<TextView>(R.id.tvConsumo).text = viewModel.formatKwh(state.consumoMes)
        findViewById<TextView>(R.id.tvCosto).text = viewModel.formatClp(state.costoMes)
        findViewById<TextView>(R.id.tvActivos).text = "${state.activos} activos"
        findViewById<TextView>(R.id.tvMeta).text = "${state.progresoMeta}%"

        if (state.monitoreando.isEmpty()) {
            findViewById<TextView>(R.id.tvMonitorNombre).text = getString(R.string.monitoring_none)
            findViewById<TextView>(R.id.tvMonitorEstado).text = "DETENIDO"
            findViewById<TextView>(R.id.tvMonitorValor).text = "Selecciona uno o más artefactos desde Mis artefactos"
        } else {
            findViewById<TextView>(R.id.tvMonitorNombre).text = "${state.monitoreando.size} artefacto(s) monitoreando"
            findViewById<TextView>(R.id.tvMonitorEstado).text = "MONITOREO MÚLTIPLE ACTIVO"
            val detail = state.monitoreando.joinToString("\n") { artifact ->
                val source = if (artifact.tipoFuente == TipoFuenteMonitoreo.IOT_REAL) "IOT REAL" else "SIMULADA"
                "• ${artifact.nombre} · $source"
            }
            findViewById<TextView>(R.id.tvMonitorValor).text =
                "$detail\nTotal mes monitoreado: ${viewModel.formatKwh(state.consumoMonitoreado)}"
        }

        state.comparacion?.let { cmp ->
            findViewById<TextView>(R.id.tvComparacionDashboard).text =
                "${cmp.comparisonText}\nActual: ${viewModel.formatKwh(cmp.currentKwh)} · Anterior: ${viewModel.formatKwh(cmp.previousKwh)}\nVariación: ${viewModel.formatVariation(cmp.consumptionChangePct)}"
            findViewById<VoltifyBarChartView>(R.id.chartDashboard).setData(
                listOf("Actual", "Anterior"),
                listOf(cmp.currentKwh, cmp.previousKwh),
                comparison = true
            )
        }
    }
}

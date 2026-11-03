package com.example.voltify

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.voltify.model.ArtefactoLocal
import com.example.voltify.model.EstadoArtefacto
import com.example.voltify.model.TipoFuenteMonitoreo
import com.example.voltify.ui.consumos.ConsumosViewModel
import kotlin.math.abs

class HistorialActivity : AppCompatActivity() {
    private val viewModel: ConsumosViewModel by viewModels()
    private var period = PERIODO_MES
    private var state = "todos"
    private var query = ""
    private var graphArtifactId: Int? = null
    private var compareAId: Int? = null
    private var compareBId: Int? = null
    private lateinit var box: LinearLayout
    private var artifactOptions: Map<String, ArtefactoLocal> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historial)
        applySystemBars(findViewById(R.id.main))
        box = findViewById(R.id.llHistorial)

        mapOf(
            R.id.btnHoy to PERIODO_HOY,
            R.id.btnSemana to PERIODO_SEMANA,
            R.id.btnMes to PERIODO_MES,
            R.id.btnTresMeses to PERIODO_TRES_MESES,
            R.id.btnTodos to PERIODO_TODOS
        ).forEach { (id, selectedPeriod) ->
            findViewById<android.view.View>(id).setOnClickListener {
                period = selectedPeriod
                render()
                renderArtifactComparison()
            }
        }

        mapOf(
            R.id.btnEstadoTodos to "todos",
            R.id.btnEstadoActivos to "activos",
            R.id.btnEstadoRetirados to "retirados",
            R.id.btnEstadoEliminados to "eliminados"
        ).forEach { (id, selectedState) ->
            findViewById<android.view.View>(id).setOnClickListener {
                state = selectedState
                render()
            }
        }

        val search = findViewById<EditText>(R.id.etBuscar)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<android.view.View>(R.id.btnBuscar).setOnClickListener {
            query = search.text.toString().trim()
            render()
        }

        configureArtifactSelectors()
        findViewById<android.view.View>(R.id.btnCompararArtefactos).setOnClickListener { renderArtifactComparison() }
        findViewById<android.view.View>(R.id.btnVolver).setOnClickListener { finish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        configureArtifactSelectors()
        render()
    }

    private fun configureArtifactSelectors() {
        val artifacts = viewModel.currentArtifacts()
        artifactOptions = artifacts.associateBy { displayName(it) }

        val graph = findViewById<AutoCompleteTextView>(R.id.acGraficaArtefacto)
        val graphLabels = listOf(getString(R.string.all_devices_graph)) + artifactOptions.keys
        graph.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, graphLabels))
        if (graph.text.isBlank()) graph.setText(getString(R.string.all_devices_graph), false)
        graph.setOnClickListener { graph.showDropDown() }
        graph.setOnItemClickListener { _, _, position, _ ->
            graphArtifactId = if (position == 0) null else artifactOptions[graphLabels[position]]?.id
            render()
        }

        configureCompareSelector(findViewById(R.id.acCompararA), true)
        configureCompareSelector(findViewById(R.id.acCompararB), false)
    }

    private fun configureCompareSelector(view: AutoCompleteTextView, first: Boolean) {
        val labels = artifactOptions.keys.toList()
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        view.setOnClickListener { view.showDropDown() }
        view.setOnItemClickListener { _, _, position, _ ->
            val id = artifactOptions[labels[position]]?.id
            if (first) compareAId = id else compareBId = id
        }
    }

    private fun render() {
        val screen = viewModel.state(period, state, query, graphArtifactId)
        findViewById<TextView>(R.id.tvPeriodo).text = screen.periodoLabel
        findViewById<TextView>(R.id.tvTotalKwh).text = viewModel.formatKwh(screen.totalKwh)
        findViewById<TextView>(R.id.tvCosto).text = viewModel.formatClp(screen.costo)

        val comparison = screen.comparacion
        val trend = screen.tendencia
        if (comparison != null && trend != null) {
            findViewById<TextView>(R.id.tvComparacionHistorial).text =
                "${comparison.comparisonText}\nAnterior: ${viewModel.formatKwh(comparison.previousKwh)} · Variación: ${viewModel.formatVariation(comparison.consumptionChangePct)}"
            findViewById<VoltifyBarChartView>(R.id.chartComparacionHistorial).setData(
                listOf("Actual", "Anterior"), listOf(comparison.currentKwh, comparison.previousKwh), true
            )
            findViewById<VoltifyBarChartView>(R.id.chartTendenciaHistorial).setData(trend.labels, trend.values)
        } else {
            findViewById<TextView>(R.id.tvComparacionHistorial).text = "Historial completo: comparación temporal no aplica."
            findViewById<VoltifyBarChartView>(R.id.chartComparacionHistorial).setData(emptyList(), emptyList())
            findViewById<VoltifyBarChartView>(R.id.chartTendenciaHistorial).setData(emptyList(), emptyList())
        }

        val graphArtifact = graphArtifactId?.let { id -> viewModel.currentArtifacts().firstOrNull { it.id == id } }
        findViewById<TextView>(R.id.tvTrendSource).text = if (graphArtifact == null) {
            "Tendencia combinada de todos los artefactos"
        } else {
            "${graphArtifact.nombre} · ${sourceLabel(graphArtifact.tipoFuente)}"
        }

        box.removeAllViews()
        val tariffKwh = viewModel.tariffKwh()
        screen.items.forEach { item ->
            val panel = makePanel()
            val stateLabel = when (item.estado) {
                EstadoArtefacto.ACTIVO -> "Activo"
                EstadoArtefacto.RETIRADO -> "Retirado"
                EstadoArtefacto.ELIMINADO -> getString(R.string.deleted_history_status)
            }
            val stateColor = when (item.estado) {
                EstadoArtefacto.ACTIVO -> R.color.voltify_success
                EstadoArtefacto.RETIRADO -> R.color.voltify_warning
                EstadoArtefacto.ELIMINADO -> R.color.voltify_danger
            }
            panel.addView(makeText(item.nombre, 16f, R.color.voltify_text_primary, true))
            panel.addView(makeText("$stateLabel · ${item.categoria} · ${sourceLabel(item.tipoFuente)}", 12f, stateColor))
            panel.addView(makeText("${viewModel.formatKwh(item.kwh)} · ${viewModel.formatClp(item.kwh * tariffKwh)} energía", 13f, R.color.voltify_cyan, true))
            if (item.estado == EstadoArtefacto.RETIRADO) {
                val expiry = viewModel.retentionExpiryMillis(item.artefactoId)
                val remaining = viewModel.retentionRemainingDays(item.artefactoId)
                if (expiry != null && remaining != null) {
                    panel.addView(makeText(getString(R.string.retired_history_notice, viewModel.formatChileDate(expiry), remaining), 11f, R.color.voltify_warning))
                }
            } else if (item.estado == EstadoArtefacto.ELIMINADO) {
                panel.addView(makeText(getString(R.string.deleted_history_notice), 11f, R.color.voltify_text_muted))
            }
            box.addView(panel)
        }
        if (box.childCount == 0) box.addView(makeText("No hay consumos para este filtro.", 14f))
    }

    private fun renderArtifactComparison() {
        val aId = compareAId
        val bId = compareBId
        val text = findViewById<TextView>(R.id.tvComparacionArtefactos)
        val chart = findViewById<VoltifyComparisonChartView>(R.id.chartComparacionArtefactos)

        if (aId == null || bId == null || aId == bId) {
            text.text = getString(R.string.comparison_select_two)
            chart.setData(emptyList(), emptyList(), emptyList(), "A", "B")
            return
        }
        if (period == PERIODO_TODOS) {
            text.text = "Selecciona Día, Semana, Mes o 3 meses para comparar la evolución temporal."
            chart.setData(emptyList(), emptyList(), emptyList(), "A", "B")
            return
        }

        val result = viewModel.compareArtifacts(aId, bId, period) ?: run {
            text.text = getString(R.string.comparison_select_two)
            return
        }
        val differenceText = when {
            result.differenceKwh > 0.000001 -> "${result.artifactA.nombre} consumió ${viewModel.formatKwh(abs(result.differenceKwh))} más."
            result.differenceKwh < -0.000001 -> "${result.artifactB.nombre} consumió ${viewModel.formatKwh(abs(result.differenceKwh))} más."
            else -> "Ambos artefactos registran el mismo consumo en el período."
        }
        text.text = buildString {
            appendLine("${result.artifactA.nombre} · ${sourceLabel(result.artifactA.tipoFuente)}")
            appendLine("${viewModel.formatKwh(result.kwhA)} · ${viewModel.formatClp(result.energyCostA)} energía")
            appendLine()
            appendLine("${result.artifactB.nombre} · ${sourceLabel(result.artifactB.tipoFuente)}")
            appendLine("${viewModel.formatKwh(result.kwhB)} · ${viewModel.formatClp(result.energyCostB)} energía")
            appendLine()
            append(differenceText)
        }
        chart.setData(
            result.trendA.labels,
            result.trendA.values,
            result.trendB.values,
            result.artifactA.nombre,
            result.artifactB.nombre
        )
    }

    private fun displayName(artifact: ArtefactoLocal): String = "${artifact.nombre} (#${artifact.id})"

    private fun sourceLabel(source: TipoFuenteMonitoreo): String = when (source) {
        TipoFuenteMonitoreo.SIMULADA -> "SIMULADA"
        TipoFuenteMonitoreo.IOT_REAL -> "IOT REAL"
    }
}

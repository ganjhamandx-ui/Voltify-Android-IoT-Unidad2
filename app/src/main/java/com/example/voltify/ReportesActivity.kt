package com.example.voltify

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.voltify.ui.reportes.ReportesViewModel

class ReportesActivity : AppCompatActivity() {
    private val viewModel: ReportesViewModel by viewModels()
    private var period = PERIODO_MES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reportes)
        applySystemBars(findViewById(R.id.main))

        mapOf(
            R.id.btnHoy to PERIODO_HOY,
            R.id.btnSemana to PERIODO_SEMANA,
            R.id.btnMes to PERIODO_MES,
            R.id.btnTresMeses to PERIODO_TRES_MESES
        ).forEach { (id, p) -> findViewById<android.view.View>(id).setOnClickListener { period = p; render() } }

        findViewById<android.view.View>(R.id.btnGenerar).setOnClickListener {
            val text = viewModel.reportText(period)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Reporte Voltify")
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Compartir reporte"
                )
            )
        }
        findViewById<android.view.View>(R.id.btnVolver).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        val state = viewModel.state(period)
        findViewById<TextView>(R.id.tvFechaReporte).text = state.fecha
        findViewById<TextView>(R.id.tvPeriodo).text = state.periodo
        findViewById<TextView>(R.id.tvConsumo).text = viewModel.formatKwh(state.consumo)
        findViewById<TextView>(R.id.tvEnergia).text = viewModel.formatClp(state.energia)
        findViewById<TextView>(R.id.tvFijo).text = viewModel.formatClp(state.fijo)
        findViewById<TextView>(R.id.tvTotal).text = viewModel.formatClp(state.total)

        val cmp = state.comparacion
        findViewById<TextView>(R.id.tvComparacionReporte).text = buildString {
            appendLine(cmp.comparisonText)
            appendLine("Consumo actual: ${viewModel.formatKwh(cmp.currentKwh)}")
            appendLine("Período anterior: ${viewModel.formatKwh(cmp.previousKwh)}")
            appendLine("Variación consumo: ${viewModel.formatVariation(cmp.consumptionChangePct)}")
            appendLine("Costo actual: ${viewModel.formatClp(cmp.currentCost)}")
            append("Costo anterior: ${viewModel.formatClp(cmp.previousCost)} · ${viewModel.formatVariation(cmp.costChangePct)}")
        }
        findViewById<VoltifyBarChartView>(R.id.chartComparacionReporte).setData(
            listOf("Actual", "Anterior"), listOf(cmp.currentKwh, cmp.previousKwh), true
        )
        findViewById<VoltifyBarChartView>(R.id.chartTendenciaReporte).setData(state.tendencia.labels, state.tendencia.values)

        val box = findViewById<LinearLayout>(R.id.llRanking)
        box.removeAllViews()
        state.ranking.forEachIndexed { i, item ->
            box.addView(
                makeText(
                    "${i + 1}. ${item.resumen.nombre} · ${viewModel.formatKwh(item.resumen.kwh)} · ${viewModel.formatClp(item.cost)}",
                    13f,
                    if (i == 0) R.color.voltify_cyan else R.color.voltify_text_secondary,
                    i == 0
                )
            )
        }
    }
}

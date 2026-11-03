package com.example.voltify

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.voltify.ui.consejos.ConsejosViewModel
import java.util.Locale

private data class ConsejoUi(
    val titulo: String,
    val descripcion: String,
    val accion: String,
    val nivel: String,
    val etiqueta: String
)

class ConsejosActivity : AppCompatActivity() {
    private val viewModel: ConsejosViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consejos)
        applySystemBars(findViewById(R.id.main))

        render()
        findViewById<android.view.View>(R.id.btnVolver).setOnClickListener { finish() }
    }

    private fun render() {
        val personalizadosBox = findViewById<LinearLayout>(R.id.llConsejosPersonalizados)
        val generalesBox = findViewById<LinearLayout>(R.id.llConsejosGenerales)
        personalizadosBox.removeAllViews()
        generalesBox.removeAllViews()

        val activos = viewModel.getArtifacts()
        val todos = viewModel.getArtifacts(includeInactive = true)
        val monitoreando = activos.count { it.monitoreando }
        val totalMes = viewModel.totalKwh(PERIODO_MES)
        val costoMes = viewModel.totalCost(PERIODO_MES)
        val meta = viewModel.getMeta()

        val consumosPorArtefacto = todos
            .map { it to viewModel.artifactKwh(it.id, PERIODO_MES) }
            .filter { it.second > 0.0 }

        val top = consumosPorArtefacto.maxByOrNull { it.second }
        val topPct = if (top != null && totalMes > 0.0) {
            (top.second / totalMes) * 100.0
        } else 0.0

        val pctMetaConsumo = meta?.consumoKwh
            ?.takeIf { it > 0.0 }
            ?.let { (totalMes / it) * 100.0 }

        val pctMetaCosto = meta?.costoClp
            ?.takeIf { it > 0.0 }
            ?.let { (costoMes / it) * 100.0 }

        findViewById<TextView>(R.id.tvPeriodoConsejos).text =
            "Mes actual · ${viewModel.currentChileDateTime()}"

        val resumen = buildString {
            append("Consumo: ${viewModel.formatKwh(totalMes)}")
            append(" · Costo: ${viewModel.formatClp(costoMes)}")
            append("\nArtefactos activos: ${activos.size}")
            append(" · En monitoreo: $monitoreando")
            if (pctMetaConsumo != null) {
                append("\nMeta de consumo utilizada: ${fmtPct(pctMetaConsumo)}")
            }
            if (top != null) {
                append("\nMayor consumidor: ${top.first.nombre} (${fmtPct(topPct)})")
            }
        }
        findViewById<TextView>(R.id.tvResumenConsejos).text = resumen

        val personalizados = mutableListOf<ConsejoUi>()

        // Replica la lógica del backend: consejo específico para el mayor consumidor.
        if (top != null) {
            val artefacto = top.first
            val textoBusqueda = "${artefacto.nombre} ${artefacto.categoria}".lowercase(Locale.ROOT)
            val nivel = when {
                topPct >= 50.0 -> "alta"
                topPct >= 30.0 -> "media"
                else -> "informativo"
            }

            val accion = when {
                "refrig" in textoBusqueda || "freezer" in textoBusqueda ->
                    "Como es un equipo de funcionamiento continuo, no se recomienda desconectarlo. Revisa el cierre de la puerta, la temperatura configurada y que tenga ventilación suficiente."

                "calef" in textoBusqueda || "estufa" in textoBusqueda ->
                    "Revisa cuánto tiempo permanece encendido y evita usar una temperatura mayor a la necesaria. Los equipos de calefacción pueden concentrar una parte importante del consumo del hogar."

                "hervidor" in textoBusqueda || "tetera" in textoBusqueda ->
                    "Calienta solamente la cantidad de agua que necesitas y evita repetir ciclos de calentamiento innecesarios."

                "lavadora" in textoBusqueda || "lavado" in textoBusqueda ->
                    "Agrupa las cargas de ropa y utiliza ciclos adecuados para evitar lavados pequeños innecesarios."

                "ampolleta" in textoBusqueda || "ilumin" in textoBusqueda || "luz" in textoBusqueda ->
                    "Mantén apagadas las luces que no estés utilizando y prioriza iluminación LED cuando corresponda."

                "televisor" in textoBusqueda || textoBusqueda.contains(" tv") ->
                    "Evita dejar el televisor funcionando cuando nadie lo utiliza y revisa sus opciones de ahorro o suspensión."

                "computador" in textoBusqueda || "notebook" in textoBusqueda || textoBusqueda.contains(" pc") ->
                    "Utiliza suspensión cuando el equipo no esté en uso y evita mantener periféricos encendidos sin necesidad."

                else ->
                    "Revisa sus horarios y tiempo de uso para identificar períodos en que puedas reducir consumo sin afectar su funcionamiento normal."
            }

            personalizados += ConsejoUi(
                titulo = "Revisa ${artefacto.nombre}",
                descripcion = "Este artefacto representa ${fmtPct(topPct)} del consumo registrado durante el mes (${viewModel.formatKwh(top.second)}).",
                accion = accion,
                nivel = nivel,
                etiqueta = "Mayor consumidor"
            )
        }

        // Meta de consumo mensual.
        if (pctMetaConsumo != null) {
            when {
                pctMetaConsumo >= 100.0 -> personalizados += ConsejoUi(
                    "Meta de consumo superada",
                    "Has utilizado ${fmtPct(pctMetaConsumo)} de tu meta mensual de consumo.",
                    "Revisa primero los artefactos con mayor consumo y reduce usos que no sean necesarios durante el resto del mes.",
                    "alta",
                    "Meta mensual"
                )

                pctMetaConsumo >= 80.0 -> personalizados += ConsejoUi(
                    "Estás cerca de tu meta mensual",
                    "Ya has utilizado ${fmtPct(pctMetaConsumo)} de tu límite de consumo.",
                    "Conviene vigilar el consumo de los próximos días y priorizar los equipos realmente necesarios.",
                    "media",
                    "Meta mensual"
                )

                else -> personalizados += ConsejoUi(
                    "Tu meta sigue bajo control",
                    "Actualmente llevas ${fmtPct(pctMetaConsumo)} de tu meta mensual de energía.",
                    "Mantén tus hábitos actuales y continúa revisando qué equipos concentran el mayor consumo.",
                    "positiva",
                    "Meta mensual"
                )
            }
        }

        // Meta económica mensual.
        if (pctMetaCosto != null) {
            val nivel: String
            val accion: String
            when {
                pctMetaCosto >= 100.0 -> {
                    nivel = "alta"
                    accion = "Tu costo estimado ya superó la meta económica del mes. Revisa los consumos de mayor impacto antes de realizar nuevos usos intensivos."
                }
                pctMetaCosto >= 80.0 -> {
                    nivel = "media"
                    accion = "Estás cerca del límite económico que definiste. Controla especialmente los artefactos de mayor consumo."
                }
                else -> {
                    nivel = "positiva"
                    accion = "El costo estimado permanece dentro de la meta económica definida para el mes."
                }
            }
            personalizados += ConsejoUi(
                "Seguimiento de tu presupuesto",
                "Has utilizado aproximadamente ${fmtPct(pctMetaCosto)} de tu meta económica mensual.",
                accion,
                nivel,
                "Costo mensual"
            )
        }

        // Cobertura de monitoreo.
        if (activos.isNotEmpty() && monitoreando < activos.size) {
            val faltantes = activos.size - monitoreando
            personalizados += ConsejoUi(
                "Amplía tu cobertura de monitoreo",
                "Tienes $monitoreando de ${activos.size} artefactos en monitoreo actualmente.",
                "Hay $faltantes artefacto(s) registrado(s) sin monitoreo. Actívalos cuando quieras incluir su consumo en el seguimiento.",
                "informativo",
                "Monitoreo"
            )
        }

        if (personalizados.isEmpty() && totalMes == 0.0) {
            personalizados += ConsejoUi(
                "Aún no tenemos datos suficientes",
                "Voltify necesita mediciones de consumo para generar recomendaciones personalizadas.",
                "Activa el monitoreo de tus artefactos y vuelve cuando existan registros.",
                "informativo",
                "Datos"
            )
        }

        personalizados.forEach { personalizadosBox.addView(makeConsejoPanel(it)) }

        val generales = listOf(
            ConsejoUi(
                "Reduce consumos innecesarios",
                "Apaga los equipos que no necesites mantener funcionando y evita dejar dispositivos activos sin uso.",
                "Prioriza primero los períodos de inactividad prolongada.",
                "informativo",
                "Buenas prácticas"
            ),
            ConsejoUi(
                "Observa primero los equipos de mayor impacto",
                "Una reducción pequeña en un artefacto que consume mucho puede ser más relevante que varios cambios en equipos de consumo bajo.",
                "Usa Consumos e historial para identificar dónde está el mayor impacto.",
                "informativo",
                "Análisis"
            ),
            ConsejoUi(
                "Usa las metas como referencia",
                "Una meta mensual permite detectar con anticipación si tu consumo o gasto está creciendo más rápido de lo esperado.",
                "Ajusta metas realistas y revísalas junto con la comparación del período anterior.",
                "informativo",
                "Metas"
            ),
            ConsejoUi(
                "No desconectes equipos esenciales por defecto",
                "Equipos como refrigeradores necesitan funcionar de forma continua.",
                "En esos casos conviene buscar anomalías o cambios de consumo en vez de simplemente apagarlos.",
                "informativo",
                "Uso responsable"
            )
        )
        generales.forEach { generalesBox.addView(makeConsejoPanel(it)) }
    }

    private fun makeConsejoPanel(consejo: ConsejoUi): LinearLayout {
        val panel = makePanel()

        val badgeColor = when (consejo.nivel) {
            "alta" -> R.color.voltify_danger
            "media" -> R.color.voltify_warning
            "positiva" -> R.color.voltify_success
            else -> R.color.voltify_cyan
        }

        panel.addView(TextView(this).apply {
            text = "${consejo.etiqueta} · ${consejo.nivel.uppercase(Locale.ROOT)}"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@ConsejosActivity, badgeColor))
            setTypeface(typeface, Typeface.BOLD)
        })

        panel.addView(makeText(consejo.titulo, 16f, R.color.voltify_text_primary, true).apply {
            setPadding(0, dp(5), 0, 0)
        })

        panel.addView(makeText(consejo.descripcion, 13f).apply {
            setPadding(0, dp(5), 0, 0)
        })

        panel.addView(makeText("Qué hacer: ${consejo.accion}", 13f, badgeColor, false).apply {
            setPadding(0, dp(7), 0, 0)
        })

        return panel
    }

    private fun fmtPct(value: Double): String =
        String.format(Locale("es", "CL"), "%.1f%%", value)
}

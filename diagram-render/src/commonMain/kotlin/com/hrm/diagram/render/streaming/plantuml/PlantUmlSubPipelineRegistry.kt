package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.parser.plantuml.PlantUmlStructParser
import com.hrm.diagram.render.streaming.dispatcher.SubPipelineRegistry

internal enum class PlantUmlDiagramKind {
    Sequence,
    Class,
    State,
    Component,
    Usecase,
    Activity,
    Object,
    Deployment,
    Erd,
    Mindmap,
    Wbs,
    Json,
    Yaml,
    Network,
    Gantt,
    Timing,
    Salt,
    Archimate,
    C4,
    Ditaa,
    Pie,
    BarChart,
    LineChart,
    ScatterChart,
}

internal data class PlantUmlStartDirective(
    val kind: PlantUmlDiagramKind,
    val closingDirective: String,
)

internal class PlantUmlSubPipelineRegistry(
    private val textMeasurer: TextMeasurer,
) : SubPipelineRegistry<PlantUmlDiagramKind, PlantUmlSubPipeline> {
    override val defaultKind: PlantUmlDiagramKind = PlantUmlDiagramKind.Sequence

    override fun create(kind: PlantUmlDiagramKind): PlantUmlSubPipeline =
        when (kind) {
            PlantUmlDiagramKind.Sequence -> PlantUmlSequenceSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Class -> PlantUmlClassSubPipeline(textMeasurer)
            PlantUmlDiagramKind.State -> PlantUmlStateSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Component -> PlantUmlComponentSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Usecase -> PlantUmlUsecaseSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Activity -> PlantUmlActivitySubPipeline(textMeasurer)
            PlantUmlDiagramKind.Object -> PlantUmlObjectSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Deployment -> PlantUmlDeploymentSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Erd -> PlantUmlErdSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Mindmap -> PlantUmlMindmapSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Wbs -> PlantUmlWbsSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Json -> PlantUmlStructSubPipeline(PlantUmlStructParser.Format.JSON, textMeasurer)
            PlantUmlDiagramKind.Yaml -> PlantUmlStructSubPipeline(PlantUmlStructParser.Format.YAML, textMeasurer)
            PlantUmlDiagramKind.Network -> PlantUmlNetworkSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Gantt -> PlantUmlTimeSeriesSubPipeline(PlantUmlTimeSeriesSubPipeline.Kind.Gantt, textMeasurer)
            PlantUmlDiagramKind.Timing -> PlantUmlTimeSeriesSubPipeline(PlantUmlTimeSeriesSubPipeline.Kind.Timing, textMeasurer)
            PlantUmlDiagramKind.Salt -> PlantUmlSaltSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Archimate -> PlantUmlArchimateSubPipeline(textMeasurer)
            PlantUmlDiagramKind.C4 -> PlantUmlC4SubPipeline(textMeasurer)
            PlantUmlDiagramKind.Ditaa -> PlantUmlDitaaSubPipeline(textMeasurer)
            PlantUmlDiagramKind.Pie -> PlantUmlPieSubPipeline(textMeasurer)
            PlantUmlDiagramKind.BarChart -> PlantUmlXYChartSubPipeline(SeriesKind.Bar, textMeasurer)
            PlantUmlDiagramKind.LineChart -> PlantUmlXYChartSubPipeline(SeriesKind.Line, textMeasurer)
            PlantUmlDiagramKind.ScatterChart -> PlantUmlXYChartSubPipeline(SeriesKind.Scatter, textMeasurer)
        }

    fun acceptsBufferedSkinparam(kind: PlantUmlDiagramKind): Boolean =
        when (kind) {
            PlantUmlDiagramKind.Sequence,
            PlantUmlDiagramKind.Activity,
            PlantUmlDiagramKind.Usecase,
            PlantUmlDiagramKind.State,
            PlantUmlDiagramKind.Class,
            PlantUmlDiagramKind.Component,
            PlantUmlDiagramKind.Deployment,
            PlantUmlDiagramKind.Object,
            PlantUmlDiagramKind.Pie,
            PlantUmlDiagramKind.BarChart,
            PlantUmlDiagramKind.LineChart,
            PlantUmlDiagramKind.ScatterChart,
            -> true
            else -> false
        }

    fun acceptsActiveSkinparam(kind: PlantUmlDiagramKind, trimmed: String): Boolean =
        when {
            acceptsBufferedSkinparam(kind) -> true
            kind == PlantUmlDiagramKind.Ditaa -> trimmed.startsWith("skinparam handwritten", ignoreCase = true)
            else -> false
        }

    fun acceptsBufferedSkinparamLine(kind: PlantUmlDiagramKind, trimmed: String): Boolean =
        acceptsBufferedSkinparam(kind) ||
            (kind == PlantUmlDiagramKind.Ditaa && trimmed.startsWith("skinparam handwritten", ignoreCase = true))

    fun requiresRawPayload(kind: PlantUmlDiagramKind): Boolean =
        kind == PlantUmlDiagramKind.Json ||
            kind == PlantUmlDiagramKind.Yaml ||
            kind == PlantUmlDiagramKind.Ditaa

    fun matchStartDirective(trimmed: String): PlantUmlStartDirective? =
        when {
            trimmed.equals("@startmindmap", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Mindmap, "@endmindmap")
            trimmed.equals("@startwbs", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Wbs, "@endwbs")
            trimmed.equals("@startjson", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Json, "@endjson")
            trimmed.equals("@startyaml", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Yaml, "@endyaml")
            trimmed.equals("@startnwdiag", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Network, "@endnwdiag")
            trimmed.equals("@startgantt", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Gantt, "@endgantt")
            trimmed.equals("@startsalt", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Salt, "@endsalt")
            trimmed.equals("@startditaa", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Ditaa, "@endditaa")
            trimmed.equals("@startpie", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.Pie, "@endpie")
            trimmed.equals("@startbar", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.BarChart, "@endbar")
            trimmed.equals("@startline", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.LineChart, "@endline")
            trimmed.equals("@startscatter", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.ScatterChart, "@endscatter")
            trimmed.equals("@startchart", ignoreCase = true) -> PlantUmlStartDirective(PlantUmlDiagramKind.BarChart, "@endchart")
            else -> null
        }
}

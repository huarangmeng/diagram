package com.hrm.diagram

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.export.ExportBackground
import com.hrm.diagram.core.export.ExportScale
import com.hrm.diagram.core.export.JpegExportOptions
import com.hrm.diagram.core.export.RasterExportOptions
import com.hrm.diagram.core.export.SvgExportOptions
import com.hrm.diagram.core.export.exportJpeg
import com.hrm.diagram.core.export.exportPng
import com.hrm.diagram.core.export.svg.exportSvg
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.gallery.DemoSample
import com.hrm.diagram.gallery.DemoSamples
import com.hrm.diagram.gallery.SourceLang
import com.hrm.diagram.render.Diagram
import com.hrm.diagram.render.compose.DiagramView
import com.hrm.diagram.render.export.prepareExport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize().safeContentPadding()) {
            GalleryScaffold()
        }
    }
}

@Composable
private fun GalleryScaffold() {
    val samples = remember { DemoSamples.all }
    var selected by remember { mutableStateOf(samples.first()) }
    var sourceText by remember(selected) { mutableStateOf(selected.source) }
    var previewSource by remember(selected) { mutableStateOf(selected.source) }
    // Feed mode for the diagram session. ONESHOT is the default (selection switch /
    // editor changes); STREAM is triggered by the "Stream this source" button and
    // exposes a growing source string to DiagramView.
    var feedMode by remember(selected) { mutableStateOf(FeedMode.ONESHOT) }
    var runEpoch by remember(selected) { mutableStateOf(0) }

    LaunchedEffect(selected, sourceText, feedMode, runEpoch) {
        when (feedMode) {
            FeedMode.ONESHOT -> previewSource = sourceText
            FeedMode.STREAM -> {
                previewSource = ""
                sourceText.chunked(16).forEach { chunk ->
                    previewSource += chunk
                    delay(40)
                }
                feedMode = FeedMode.ONESHOT
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        SampleSidebar(
            samples = samples,
            current = selected,
            onSelect = {
                selected = it
                sourceText = it.source
                feedMode = FeedMode.ONESHOT
                runEpoch = 0
            },
            modifier = Modifier.width(260.dp).fillMaxHeight(),
        )
        VerticalDivider()
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            HeaderBar(selected)
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SourceEditor(
                    text = sourceText,
                    onTextChange = {
                        sourceText = it
                        feedMode = FeedMode.ONESHOT
                        runEpoch = 0
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                VerticalDivider()
                PreviewPane(
                    sample = selected,
                    sourceText = sourceText,
                    previewSource = previewSource,
                    onStreamRequested = {
                        feedMode = FeedMode.STREAM
                        runEpoch += 1
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun SampleSidebar(
    samples: List<DemoSample>,
    current: DemoSample,
    onSelect: (DemoSample) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = remember(samples) { samples.groupBy { it.lang } }
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        grouped.forEach { (lang, list) ->
            item(key = "header-${lang.name}") {
                Text(
                    text = lang.display,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(items = list, key = { "${it.lang.name}:${it.kind}" }) { sample ->
                val isSelected = sample == current
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = sample.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clickableSimple { onSelect(sample) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(sample: DemoSample) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = sample.lang.display,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = sample.label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    HorizontalDivider()
}

@Composable
private fun SourceEditor(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionLabel("Source")
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        )
    }
}

private enum class FeedMode { ONESHOT, STREAM }

@Composable
private fun PreviewPane(
    sample: DemoSample,
    sourceText: String,
    previewSource: String,
    onStreamRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var exportState by remember(sample, sourceText) { mutableStateOf<ExportPreviewState>(ExportPreviewState.Idle) }

    Column(modifier = modifier) {
        SectionLabel("Preview")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        DiagramView(
                            source = previewSource,
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            zoomEnabled = true,
                        )
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${sample.lang.display} · ${sample.kind}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${sourceText.lines().size} lines · ${sourceText.length} chars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "preview = ${previewSource.lines().size} lines · ${previewSource.length} chars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onStreamRequested,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Stream")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        exportState = ExportPreviewState.Running("SVG")
                                        exportState = runExportPreview(sample, sourceText, ExportFormat.SVG)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("SVG")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        exportState = ExportPreviewState.Running("PNG")
                                        exportState = runExportPreview(sample, sourceText, ExportFormat.PNG)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("PNG")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        exportState = ExportPreviewState.Running("JPEG")
                                        exportState = runExportPreview(sample, sourceText, ExportFormat.JPEG)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("JPEG")
                            }
                        }
                        ExportPreviewSection(exportState = exportState)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportPreviewSection(exportState: ExportPreviewState) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    when (exportState) {
        ExportPreviewState.Idle -> {
            Text(
                text = "点击 SVG / PNG / JPEG，直接生成并保存文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ExportPreviewState.Running -> {
            Text(
                text = "正在导出 ${exportState.format} ...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is ExportPreviewState.Saved -> {
            Text(
                text = exportState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ExportPreviewState.Failure -> {
            Text(
                text = exportState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun Modifier.clickableSimple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private enum class ExportFormat {
    SVG,
    PNG,
    JPEG,
}

private sealed interface ExportPreviewState {
    data object Idle : ExportPreviewState
    data class Running(val format: String) : ExportPreviewState
    data class Saved(val message: String) : ExportPreviewState
    data class Failure(val message: String) : ExportPreviewState
}

private suspend fun runExportPreview(
    sample: DemoSample,
    sourceText: String,
    format: ExportFormat,
): ExportPreviewState =
    runCatching {
        val session = Diagram.session(language = sample.lang.toSourceLanguage())
        try {
            session.append(sourceText)
            val snapshot = session.finish()
            val rendered = snapshot.prepareExport(
                background = if (format == ExportFormat.SVG) ExportBackground.Transparent else ExportBackground.Solid(Color.White),
            )
            when (format) {
                ExportFormat.SVG -> {
                    val artifact = rendered.exportSvg(
                        SvgExportOptions(
                            scale = ExportScale.Width(1280),
                            background = ExportBackground.Transparent,
                            includeXmlDeclaration = false,
                            pretty = true,
                        ),
                    )
                    val saved = savePreviewExport(
                        fileName = buildPreviewFileName(sample, format),
                        mimeType = artifact.mimeType,
                        bytes = artifact.value.encodeToByteArray(),
                    )
                    ExportPreviewState.Saved("SVG 已保存到 ${saved.locationDescription}")
                }
                ExportFormat.PNG -> {
                    val artifact = rendered.exportPng(
                        RasterExportOptions(
                            scale = ExportScale.Width(1280),
                            background = ExportBackground.Solid(Color.White),
                        ),
                    )
                    val saved = savePreviewExport(
                        fileName = buildPreviewFileName(sample, format),
                        mimeType = artifact.mimeType,
                        bytes = artifact.value,
                    )
                    ExportPreviewState.Saved("PNG 已保存到 ${saved.locationDescription}")
                }
                ExportFormat.JPEG -> {
                    val artifact = rendered.exportJpeg(
                        JpegExportOptions(
                            scale = ExportScale.Width(1280),
                            background = ExportBackground.Solid(Color.White),
                            quality = 90,
                        ),
                    )
                    val saved = savePreviewExport(
                        fileName = buildPreviewFileName(sample, format),
                        mimeType = artifact.mimeType,
                        bytes = artifact.value,
                    )
                    ExportPreviewState.Saved("JPEG 已保存到 ${saved.locationDescription}")
                }
            }
        } finally {
            session.close()
        }
    }.getOrElse { error ->
        ExportPreviewState.Failure("${format.name} 导出失败: ${error.message ?: error::class.simpleName ?: "unknown error"}")
    }

private fun SourceLang.toSourceLanguage(): SourceLanguage =
    when (this) {
        SourceLang.MERMAID -> SourceLanguage.MERMAID
        SourceLang.PLANTUML -> SourceLanguage.PLANTUML
        SourceLang.DOT -> SourceLanguage.DOT
    }

private fun buildPreviewFileName(sample: DemoSample, format: ExportFormat): String {
    val kind = sample.kind.asSafeFilePart()
    val lang = sample.lang.name.lowercase()
    val extension = when (format) {
        ExportFormat.SVG -> "svg"
        ExportFormat.PNG -> "png"
        ExportFormat.JPEG -> "jpg"
    }
    return "diagram-$lang-$kind.$extension"
}

private fun String.asSafeFilePart(): String =
    lowercase().map { char ->
        when {
            char in 'a'..'z' || char in '0'..'9' -> char
            else -> '-'
        }
    }.joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifEmpty { "preview" }

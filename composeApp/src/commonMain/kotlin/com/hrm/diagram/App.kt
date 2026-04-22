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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.diagram.gallery.DemoSample
import com.hrm.diagram.gallery.DemoSamples
import com.hrm.diagram.gallery.SourceLang

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

    Row(modifier = Modifier.fillMaxSize()) {
        SampleSidebar(
            samples = samples,
            current = selected,
            onSelect = { selected = it; sourceText = it.source },
            modifier = Modifier.width(260.dp).fillMaxHeight(),
        )
        VerticalDivider()
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            HeaderBar(selected)
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SourceEditor(
                    text = sourceText,
                    onTextChange = { sourceText = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                VerticalDivider()
                PreviewPane(
                    sample = selected,
                    sourceText = sourceText,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            HorizontalDivider()
            DiagnosticsPane(modifier = Modifier.fillMaxWidth())
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

@Composable
private fun PreviewPane(
    sample: DemoSample,
    sourceText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionLabel("Preview")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "DiagramView placeholder",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${sample.lang.display} · ${sample.kind}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${sourceText.lines().size} lines · ${sourceText.length} chars",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsPane(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Diagnostics",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "(parser / layout / render not yet wired — Phase 0 placeholder)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

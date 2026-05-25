package com.hrm.diagram.render.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.render.Diagram
import com.hrm.diagram.render.streaming.DiagramSession
import com.hrm.diagram.render.streaming.DiagramSnapshot
import kotlinx.coroutines.delay

/**
 * Public Compose facade for rendering diagram source text.
 *
 * The view owns language detection, session lifecycle, snapshot production, and append-only
 * incremental updates internally.
 *
 * Minimal usage:
 * ```kotlin
 * DiagramView(source = mermaidText, modifier = Modifier.fillMaxSize())
 * ```
 */
@Composable
@DiagramApi
fun DiagramView(
    source: String,
    modifier: Modifier = Modifier,
    zoomEnabled: Boolean = false,
) {
    val language = detectSourceLanguage(source)
    val textMeasurer = rememberDiagramTextMeasurer()
    val holder = remember { DiagramViewSessionHolder() }
    val snapshotState = remember {
        mutableStateOf(DiagramSnapshot.empty(language))
    }

    DisposableEffect(holder) {
        onDispose { holder.close() }
    }

    LaunchedEffect(source, language, textMeasurer) {
        val session = holder.sessionFor(language, source, textMeasurer)
        val appendedFrom = holder.source.length
        if (source.length > appendedFrom) {
            session.append(source.substring(appendedFrom))
            snapshotState.value = session.state.value
        }
        holder.source = source
        delay(FINISH_DEBOUNCE_MS)
        snapshotState.value = session.finish()
    }

    DiagramSnapshotView(
        snapshot = snapshotState.value,
        modifier = modifier,
        zoomEnabled = zoomEnabled,
    )
}

@Composable
internal fun DiagramSnapshotView(
    snapshot: DiagramSnapshot,
    modifier: Modifier = Modifier,
    zoomEnabled: Boolean = false,
) {
    val viewportState = rememberDiagramViewportState()
    DiagramCanvas(
        snapshot = snapshot,
        modifier = modifier,
        viewportState = viewportState,
        panZoomEnabled = zoomEnabled,
    )
}

private class DiagramViewSessionHolder {
    var source: String = ""
    private var language: SourceLanguage? = null
    private var textMeasurer: TextMeasurer? = null
    private var session: DiagramSession? = null

    fun sessionFor(
        nextLanguage: SourceLanguage,
        nextSource: String,
        nextTextMeasurer: TextMeasurer,
    ): DiagramSession {
        val current = session
        val canReuse = current != null &&
            language == nextLanguage &&
            textMeasurer === nextTextMeasurer &&
            nextSource.startsWith(source)
        if (canReuse) return current

        close()
        source = ""
        language = nextLanguage
        textMeasurer = nextTextMeasurer
        return Diagram.session(language = nextLanguage, textMeasurer = nextTextMeasurer)
            .also { session = it }
    }

    fun close() {
        session?.close()
        session = null
    }
}

private const val FINISH_DEBOUNCE_MS = 120L

internal fun detectSourceLanguage(source: CharSequence): SourceLanguage =
    Diagram.detectSource(source).language ?: SourceLanguage.MERMAID

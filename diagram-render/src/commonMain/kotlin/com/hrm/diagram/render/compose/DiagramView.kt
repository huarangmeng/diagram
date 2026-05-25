package com.hrm.diagram.render.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.render.streaming.DiagramSession
import com.hrm.diagram.render.streaming.DiagramSnapshot

/**
 * Public Compose facade for rendering a diagram snapshot.
 *
 * Prefer this entry point in app-facing code. It keeps the surface at the "view" level while
 * [DiagramCanvas] remains available as the lower-level projection primitive.
 *
 * Minimal usage:
 * ```kotlin
 * DiagramView(
 *     snapshot = snapshot,
 *     modifier = Modifier.fillMaxSize(),
 *     zoomEnabled = true,
 * )
 * ```
 */
@Composable
@DiagramApi
fun DiagramView(
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

/**
 * Convenience overload that collects [DiagramSession.state] and renders the latest snapshot.
 */
@Composable
@DiagramApi
fun DiagramView(
    session: DiagramSession,
    modifier: Modifier = Modifier,
    zoomEnabled: Boolean = false,
) {
    val snapshot by session.state.collectAsState()
    DiagramView(
        snapshot = snapshot,
        modifier = modifier,
        zoomEnabled = zoomEnabled,
    )
}

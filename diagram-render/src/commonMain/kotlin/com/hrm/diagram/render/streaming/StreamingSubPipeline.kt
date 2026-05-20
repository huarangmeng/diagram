package com.hrm.diagram.render.streaming

import com.hrm.diagram.render.cache.DrawEntity

/** Shared lifecycle contract for language-specific streaming sub-pipelines. */
internal interface StreamingSubPipeline {
    fun dispose() {}
}

/** Capability for sub-pipelines that expose a DrawEntity snapshot after rendering. */
internal interface DrawEntitySnapshotProvider : StreamingSubPipeline {
    fun drawEntitiesFor(snapshot: DiagramSnapshot): List<DrawEntity>
}

/** Capability for sub-pipelines that render their current parser state on demand. */
internal interface RenderStateProvider<out T> : StreamingSubPipeline {
    fun render(previousSnapshot: DiagramSnapshot, seq: Long, isFinal: Boolean): T
}

internal fun DrawEntitySnapshotProvider?.drawEntitiesOrEmpty(snapshot: DiagramSnapshot): List<DrawEntity> =
    this?.drawEntitiesFor(snapshot).orEmpty()

package com.hrm.diagram.render.streaming

import com.hrm.diagram.render.cache.DrawEntity

/**
 * Internal seam for pipelines that can expose IR-entity keyed draw output.
 */
internal interface StructuredDrawEntityProvider {
    val lastDrawEntities: List<DrawEntity>
}

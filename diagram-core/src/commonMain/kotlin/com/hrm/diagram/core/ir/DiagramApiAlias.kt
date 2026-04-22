package com.hrm.diagram.core.ir

/**
 * Internal alias so docs can call out which symbols are part of the public IR
 * before the @DiagramApi annotation graduates from :diagram-core's public surface.
 * For now this is just a marker comment annotation (no retention) — replaced in a
 * later phase with the real @DiagramApi check.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
internal annotation class DiagramApiAlias

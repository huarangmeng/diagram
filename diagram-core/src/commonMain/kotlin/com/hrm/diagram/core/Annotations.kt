package com.hrm.diagram.core

/**
 * Marks a public, stable Diagram API. ABI changes require an ADR (see docs/adr/).
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@Retention(AnnotationRetention.BINARY)
annotation class DiagramApi

/**
 * Marks an experimental Diagram API. Callers must opt-in.
 */
@RequiresOptIn(message = "This Diagram API is experimental and may change without notice.")
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@Retention(AnnotationRetention.BINARY)
annotation class DiagramExperimental

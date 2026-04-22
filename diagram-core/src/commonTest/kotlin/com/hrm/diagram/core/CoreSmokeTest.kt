package com.hrm.diagram.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreSmokeTest {
    @Test
    fun packageIsAlive() {
        assertEquals("com.hrm.diagram.core", DiagramApi::class.qualifiedName!!.substringBeforeLast('.'))
    }
}

package com.hrm.diagram.core.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenTest {

    @Test
    fun token_length_derives_from_offsets() {
        val t = Token(kind = 1, start = 5, end = 9, text = "abcd")
        assertEquals(4, t.length)
    }

    @Test
    fun rejects_zero_length_token() {
        assertFailsWith<IllegalArgumentException> {
            Token(kind = 0, start = 3, end = 3, text = "")
        }
    }

    @Test
    fun lexer_step_carries_safe_point() {
        val state = object : LexerState { override val pendingChars: Int = 0 }
        val step = LexerStep(
            tokens = emptyList(),
            newState = state,
            safePoint = 12,
        )
        assertEquals(12, step.safePoint)
        assertEquals(0, step.diagnostics.size)
    }
}

package com.hrm.diagram

import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.gallery.DemoSamples
import com.hrm.diagram.gallery.SourceLang
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertTrue

class ComposeAppCommonTest {

    @Test
    fun gallery_samples_render_without_error_diagnostics() {
        val failures = DemoSamples.all.mapNotNull { sample ->
            val session = Diagram.session(sample.lang.toCoreLanguage())
            session.append(sample.source)
            val snapshot = session.finish()
            val errors = snapshot.diagnostics.filter { it.severity == Severity.ERROR }
            if (errors.isEmpty()) null else "${sample.lang.display}/${sample.kind}: $errors"
        }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }
}

private fun SourceLang.toCoreLanguage(): SourceLanguage = when (this) {
    SourceLang.MERMAID -> SourceLanguage.MERMAID
    SourceLang.PLANTUML -> SourceLanguage.PLANTUML
    SourceLang.DOT -> SourceLanguage.DOT
}

package com.umair.purpose.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplatesTest {
    @Test
    fun `fills known placeholders and leaves unknown ones visible`() {
        assertEquals(
            "Date: 2026-10-03, ctx: a {b}, {{MISSING}}",
            Templates.fill("Date: {{SESSION_DATE}}, ctx: {{CONTEXT}}, {{MISSING}}", mapOf("SESSION_DATE" to "2026-10-03", "CONTEXT" to "a {b}")),
        )
    }

    @Test
    fun `replacement text with dollar signs is inserted literally`() {
        assertEquals("cost \$5", Templates.fill("cost {{X}}", mapOf("X" to "\$5")))
    }
}

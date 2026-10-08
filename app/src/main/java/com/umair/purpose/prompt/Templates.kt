package com.umair.purpose.prompt

/** Fills `{{NAME}}` placeholders. Unknown placeholders are left as they are, so mistakes stay visible. */
object Templates {
    private val PLACEHOLDER = Regex("""\{\{([A-Z_]+)\}\}""")

    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }
}

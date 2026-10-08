package com.umair.purpose.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Thin line icons, drawn for Purpose. Tinted by the Icon that shows them. */
object PurposeIcons {
    private fun line(name: String, vararg paths: String, stroke: Float = 1.6f, filled: List<String> = emptyList()) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            filled.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val More = line(
        "more",
        filled = listOf(
            "M5,12m-1.6,0a1.6,1.6 0,1 1,3.2 0a1.6,1.6 0,1 1,-3.2 0",
            "M12,12m-1.6,0a1.6,1.6 0,1 1,3.2 0a1.6,1.6 0,1 1,-3.2 0",
            "M19,12m-1.6,0a1.6,1.6 0,1 1,3.2 0a1.6,1.6 0,1 1,-3.2 0",
        ),
    )
    val Back = line("back", "M15,5L8,12L15,19")
    val ArrowUp = line("send", "M12,19L12,5", "M6,11L12,5L18,11", stroke = 2f)
    val ArrowDown = line("down", "M12,5L12,19", "M6,13L12,19L18,13", stroke = 2f)
    val Plus = line("plus", "M12,5L12,19", "M5,12L19,12")
    val Mic = line("mic", "M12,3a3,3 0,0 1,3 3v5a3,3 0,0 1,-6 0V6a3,3 0,0 1,3 -3z", "M6,11a6,6 0,0 0,12 0", "M12,17L12,21")
    val Speaker = line("speaker", "M4,9.5h3.5L12,6v12l-4.5,-3.5H4z", "M15.5,9a4,4 0,0 1,0 6", "M18,6.5a7.5,7.5 0,0 1,0 11")
    val Bell = line("bell", "M6,16V11a6,6 0,0 1,12 0v5l1.5,2h-15z", "M10,20.5a2,2 0,0 0,4 0")
    val Check = line("check", "M5,12.5L10,17L19,7", stroke = 2f)
    val Chevron = line("chevron", "M6,9L12,15L18,9")
    val ChevronUp = line("chevron_up", "M6,15L12,9L18,15")
    val Drag = line("drag", "M5,9H19", "M5,15H19")
    val Menu = line("menu", "M4,7H20", "M4,12H20", "M4,17H20")
    val Search = line("search", "M10.5,4.5a6,6 0,1 1,0 12a6,6 0,1 1,0 -12z", "M15,15L20,20")

    // Tab icons
    val Talk = line("talk", "M4,6.5a2.5,2.5 0,0 1,2.5 -2.5h11a2.5,2.5 0,0 1,2.5 2.5v7a2.5,2.5 0,0 1,-2.5 2.5H10l-4.5,4v-4H6.5A2.5,2.5 0,0 1,4 13.5z")
    val Mirror = line("mirror", "M12,3c3.6,0 6,3.4 6,8s-2.4,8 -6,8s-6,-3.4 -6,-8s2.4,-8 6,-8z", "M12,19L12,21.5", "M9,21.5H15", "M9.5,8.5c0.7,-1.2 1.5,-1.8 2.5,-2")
    val Path = line("path", "M3,19L8,12L11,15.5L15,9L21,19", "M15,5.5m-1.2,0a1.2,1.2 0,1 1,2.4 0a1.2,1.2 0,1 1,-2.4 0")
    val Know = line("know", "M12,6.5C10,5 7,4.5 4,5v13c3,-0.5 6,0 8,1.5c2,-1.5 5,-2 8,-1.5V5c-3,-0.5 -6,0 -8,1.5z", "M12,6.5V19")
}

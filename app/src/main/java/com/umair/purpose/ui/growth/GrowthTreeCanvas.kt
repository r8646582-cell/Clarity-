package com.umair.purpose.ui.growth

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.umair.purpose.growth.LeafMark
import com.umair.purpose.growth.Pt
import com.umair.purpose.growth.TreeGeometry
import com.umair.purpose.growth.TreeLabel
import com.umair.purpose.growth.TreeLayout
import com.umair.purpose.growth.TreeStroke
import com.umair.purpose.ui.theme.Purpose
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class GrowState(val leafId: Long, val strokeFrom: Map<String, Float>, val branch: Float, val leaf: Float)

class TreeView {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    fun reset() { zoom = 1f; pan = Offset.Zero }
    fun zoomBy(factor: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Buttons zoom about the viewport's centre, preserving the current focal point.
        pan *= next / zoom
        zoom = next
        if (next <= 1f) pan *= 0.6f
    }
}

@Composable
fun GrowthTreeCanvas(
    g: TreeGeometry,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    view: TreeView = remember { TreeView() },
    grow: GrowState? = null,
    onLeaf: (Long) -> Unit = {},
    onLeafLongPress: (Long) -> Unit = {},
    onArea: (area: String, branch: String?) -> Unit = { _, _ -> },
) {
    val colors = Purpose.colors
    val type = Purpose.type
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val reduceMotion = Purpose.reduceMotion

    // Idle wind: a slow, reversible tick drives a gentle sway of the whole tree about its base.
    // Read in the draw phase (not composition) so the sway only invalidates drawing.
    val wind = rememberInfiniteTransition(label = "tree-wind")
    val windTick = wind.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tree-sway",
    )

    val strokePaths = remember(g) { g.strokes.associate { it.key to outlinePath(it.outline(samples = 40)) } }
    val leafPaths = remember(g) {
        g.leaves.associate { l ->
            val shape = TreeLayout.leafShape(l.type, l.size)
            l.id to shape.blades.map { outlinePath(it) }
        }
    }
    val styleFor = { l: TreeLabel ->
        when (l.kind) {
            TreeLabel.Kind.AREA -> type.meta.copy(color = colors.text.copy(alpha = 0.88f))
            TreeLabel.Kind.BRANCH -> type.meta.copy(color = colors.textMuted.copy(alpha = 0.82f), fontSize = type.meta.fontSize * 0.9f)
            TreeLabel.Kind.VALUE -> type.meta.copy(color = colors.textMuted.copy(alpha = 0.75f))
        }
    }
    val layouts = remember(g, colors, type, interactive, density) {
        if (!interactive) emptyMap() else g.labels.associateWith { measurer.measure(it.text, styleFor(it)) }
    }

    val labelMeasure = { l: TreeLabel ->
        layouts[l]?.let { it.size.width.toFloat() to it.size.height.toFloat() } ?: (0f to 0f)
    }
    val viewportCache = remember(g, layouts, density, interactive) { ViewportCache() }

    val hit = remember { HitState() }
    val onLeafNow by rememberUpdatedState(onLeaf)
    val onLongNow by rememberUpdatedState(onLeafLongPress)
    val onAreaNow by rememberUpdatedState(onArea)

    fun toTree(p: Offset): Pt {
        val s = hit.fitScale * view.zoom
        return Pt((p.x - hit.origin.x) / s, (p.y - hit.origin.y) / s).rotated(-hit.swayAngle)
    }

    fun leafAt(p: Offset): LeafMark? {
        val t = toTree(p)
        val s = hit.fitScale * view.zoom
        val reach = with(density) { 22.dp.toPx() } / s
        return g.leaves.minByOrNull { (it.center - t).length }?.takeIf { (it.center - t).length <= max(reach, it.size * 0.7f) }
    }

    val gestures = if (!interactive) Modifier else Modifier
        .pointerInput(g) {
            detectTransformGestures { centroid, panBy, zoomBy, _ ->
                // Pinch to zoom (0.75x–3.0x) and drag to pan. The pan is anchored on the pinch centroid, so the
                // point under his fingers stays put while the tree scales; labels and cluster badges redraw from
                // the same onScreen() transform, so they stay glued to their nodes through both gestures.
                val old = view.zoom
                val next = (old * zoomBy).coerceIn(MIN_ZOOM, MAX_ZOOM)
                view.pan = (view.pan + panBy) + (view.pan - (centroid - Offset(size.width / 2f, size.height / 2f))) * (next / old - 1f)
                view.zoom = next
                // Below 1x the fitted tree shrinks toward the edges; ease the pan back so it can't drift off screen.
                if (next <= 1f) view.pan = view.pan * 0.6f
            }
        }
        .pointerInput(g) {
            detectTapGestures(
                onDoubleTap = { view.reset() },
                onLongPress = { p -> leafAt(p)?.let { onLongNow(it.id) } },
                onTap = { p ->
                    val leaf = leafAt(p)
                    when {
                        leaf != null -> onLeafNow(leaf.id)
                        else -> {
                            val label = hit.labels.firstOrNull { (l, r) -> l.area != null && r.inflate(with(density) { 8.dp.toPx() }).contains(p) }?.first
                            if (label != null) onAreaNow(label.area!!, label.branch)
                            else {
                                val t = toTree(p)
                                val s = hit.fitScale * view.zoom
                                g.clusters.firstOrNull { (it.at - t).length * s < with(density) { 28.dp.toPx() } }?.let { onAreaNow(it.area, it.branch) }
                            }
                        }
                    }
                },
            )
        }

    Canvas(
        modifier.clipToBounds().then(gestures).semantics {
            contentDescription = if (g.leafCount == 0) "Your growth tree: roots, and a seedling." else "Your growth tree: ${g.leafCount} leaves on ${g.branchCount} branches."
        }
    ) {
        val framing = viewportCache.fit.takeIf { viewportCache.size == size } ?: TreeLayout.fitViewport(
            g, size.width, size.height, if (interactive) 18.dp.toPx() else 8.dp.toPx(),
            labelMeasure, showLabels = interactive, maxScale = if (interactive) 3.2f else 2.4f,
        ).also { viewportCache.fit = it; viewportCache.size = size }
        val fit = framing.scale
        val s = fit * view.zoom
        val cx = size.width / 2f + view.pan.x - framing.center.x * s
        val cy = size.height / 2f + view.pan.y - framing.center.y * s
        hit.fitScale = fit
        hit.origin = Offset(cx, cy)

        if (g.leafCount > 0) {
            val crownCenter = Offset(cx, cy - g.height * 0.38f * s)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accent.copy(alpha = 0.06f), Color.Transparent),
                    center = crownCenter,
                    radius = g.width * 0.65f * s
                ),
                radius = g.width * 0.65f * s,
                center = crownCenter
            )
        }

        val ink = colors.textMuted
        val strokeMap = g.strokes.associateBy { it.key }
        val sway = if (reduceMotion) 0f else windTick.value
        val swayAngle = sway * SWAY_RADIANS
        hit.swayAngle = swayAngle
        val cosSway = cos(swayAngle)
        val sinSway = sin(swayAngle)

        // Tree coordinates → screen, following the same sway (rotation about the base at cx,cy) so
        // labels and cluster badges stay attached to the moving branches.
        fun onScreen(tx: Float, ty: Float): Offset {
            val ax = tx * s
            val ay = ty * s
            return Offset(cx + ax * cosSway - ay * sinSway, cy + ax * sinSway + ay * cosSway)
        }

        // The ground stays still; the tree above it sways in the wind, pivoting at its base.
        withTransform({
            translate(cx, cy)
            scale(s, s, pivot = Offset.Zero)
        }) {
            drawGround(g, ink.copy(alpha = 0.32f), 1.5.dp.toPx() / s)
        }

        withTransform({
            translate(cx, cy)
            scale(s, s, pivot = Offset.Zero)
            if (sway != 0f) rotateRad(sway * SWAY_RADIANS, pivot = Offset.Zero)
        }) {
            g.strokes.forEach { st ->
                val alpha = if (st.kind == TreeStroke.Kind.ROOT) 0.50f else 0.92f
                val from = grow?.strokeFrom?.get(st.key)
                if (from != null && grow.branch < 1f) {
                    val upTo = from + (1f - from) * grow.branch
                    if (upTo > 0.01f) drawPath(outlinePath(st.outline(samples = 40, upTo = upTo)), ink.copy(alpha = alpha))
                } else {
                    drawPath(strokePaths.getValue(st.key), ink.copy(alpha = alpha))
                }
            }

            g.rings.forEach { r ->
                val dx = cos(r.angle) * r.halfWidth
                val dy = sin(r.angle) * r.halfWidth
                drawLine(colors.background.copy(alpha = 0.55f), Offset(r.center.x - dx, r.center.y - dy), Offset(r.center.x + dx, r.center.y + dy), strokeWidth = 0.7f)
            }

            val sunDirX = -0.4f
            val sunDirY = -0.916f

            g.leaves.forEachIndexed { idx, l ->
                val unfold = when {
                    grow == null -> 1f
                    l.id == grow.leafId -> if (grow.branch < 1f) 0f else grow.leaf
                    grow.strokeFrom[l.strokeKey]?.let { from -> l.t > from + (1f - from) * grow.branch } == true -> 0f
                    else -> 1f
                }
                if (unfold <= 0f) return@forEachIndexed

                val parentStroke = strokeMap[l.strokeKey]
                val stemAngle = parentStroke?.curve?.tangent(l.t)?.let { atan2(it.y, it.x) } ?: l.rotation
                parentStroke?.let { ps ->
                    val woodPoint = ps.curve.at(l.t)
                    drawPath(
                        stemPath(woodPoint, l.base),
                        ink.copy(alpha = 0.65f),
                        style = Stroke(width = (0.75.dp.toPx() / s).coerceAtLeast(0.5f), cap = StrokeCap.Round),
                    )
                }

                val normalX = cos(l.rotation - PI.toFloat() / 2f)
                val normalY = sin(l.rotation - PI.toFloat() / 2f)
                val sunDot = (normalX * sunDirX + normalY * sunDirY).coerceIn(-1f, 1f)
                val isRearLayer = (idx % 2 == 1) && !l.bright

                // Foliage depth: the per-leaf alpha (0.85–1.0) plus a little sun shading keeps overlapping
                // leaves from merging into one solid color blob, without dropping below a readable floor.
                val foliageAlpha = (l.alpha * (if (isRearLayer) 0.9f else 1f) + sunDot * 0.03f).coerceIn(0.85f, 1f)
                val tint = when {
                    l.bright -> Color(0xFFFDE047)
                    else -> colors.accent.copy(alpha = foliageAlpha)
                }

                withTransform({
                    translate(l.base.x, l.base.y)
                    // Unfurl: while the node sprouts (unfold 0→1) it swings open from the stem
                    // into its final place; a spring can overshoot, so it settles with a bounce.
                    rotateRad(stemAngle + (l.rotation - stemAngle) * unfold, pivot = Offset.Zero)
                    val layerScale = if (isRearLayer) 0.90f * unfold else unfold
                    if (layerScale != 1f) scale(layerScale, layerScale, pivot = Offset.Zero)
                }) {
                    leafPaths[l.id]?.forEach { drawPath(it, tint) }
                    val shape = TreeLayout.leafShape(l.type, l.size)
                    shape.vein?.let { (a, b) ->
                        drawLine(colors.background.copy(alpha = 0.55f), Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = l.size * 0.05f, cap = StrokeCap.Round)
                    }
                    shape.bud?.let { (c, r) ->
                        drawCircle(tint, radius = r, center = Offset(c.x, c.y))
                    }
                }
            }
        }

        if (interactive) {
            // UPDATE-21: label level of detail. Zoomed out, only the main life-area headers are drawn, so a dense
            // crown stays readable; zoomed in, branch and value names appear too — each on a soft scrim so a label
            // never disappears into the branch line beneath it. Milestone counts follow the same zoom-in threshold.
            val detailed = view.zoom >= TreeLayout.DETAIL_ZOOM
            val placed = viewportCache.labels.takeIf { viewportCache.labelScale == s && viewportCache.labelZoom == view.zoom } ?: TreeLayout.visibleLabels(
                g = g,
                scale = s,
                measure = labelMeasure,
                labels = TreeLayout.labelsForZoom(g, view.zoom),
            ).also { viewportCache.labels = it; viewportCache.labelScale = s; viewportCache.labelZoom = view.zoom }
            val boxes = ArrayList<Pair<TreeLabel, androidx.compose.ui.geometry.Rect>>()
            placed.forEach { p ->
                val layout: TextLayoutResult = layouts[p.label] ?: return@forEach
                val anchor = onScreen(p.at.x, p.at.y)
                val x = anchor.x - if (p.label.alignEnd) layout.size.width else 0
                val y = anchor.y - layout.size.height * 0.8f
                // Labels outside the current viewport should reappear when panned into view, never
                // cover the screen header or get clipped into fragments at the edges.
                if (x < 0f || y < 0f || x + layout.size.width > size.width || y + layout.size.height > size.height)
                    return@forEach
                if ((p.at - p.label.at).length * s > 12.dp.toPx()) {
                    val branchTip = when (p.label.kind) {
                        TreeLabel.Kind.AREA -> strokeMap["area:${p.label.area}"]?.curve?.p3
                        TreeLabel.Kind.BRANCH -> strokeMap["branch:${p.label.area}/${p.label.branch}"]?.curve?.p3
                        TreeLabel.Kind.VALUE -> null
                    } ?: p.label.at
                    val source = onScreen(branchTip.x, branchTip.y)
                    val edge = Offset(if (p.label.alignEnd) x + layout.size.width else x, y + layout.size.height / 2f)
                    drawLine(ink.copy(alpha = 0.35f), source, edge, strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                }
                // A scrim pill behind the text, so the branch wood never cuts through the words.
                val padX = 4.dp.toPx()
                val padY = 1.5.dp.toPx()
                drawRoundRect(
                    color = colors.background.copy(alpha = if (detailed && p.label.kind != TreeLabel.Kind.AREA) 0.78f else 0.62f),
                    topLeft = Offset(x - padX, y - padY),
                    size = Size(layout.size.width.toFloat() + padX * 2f, layout.size.height.toFloat() + padY * 2f),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
                drawText(layout, topLeft = Offset(x, y))
                boxes += p.label to androidx.compose.ui.geometry.Rect(x, y, x + layout.size.width, y + layout.size.height)
            }
            hit.labels = boxes

            if (detailed) {
                // UPDATE-22: junction count badges sit on a solid, bordered pill so overlapping branch vectors
                // never cut through the digits. The badge text is light to match the always-dark pill.
                val pill = Color(0xEE161B26)
                val pillBorder = Color(0x40E9ECEF)
                val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                g.clusters.forEach { c ->
                    val layout = measurer.measure(c.count.toString(), type.meta.copy(color = Color(0xFFE9ECEF)))
                    val anchor = onScreen(c.at.x, c.at.y)
                    val badgeX = anchor.x + 3.dp.toPx()
                    val badgeY = anchor.y - layout.size.height / 2f
                    val pillPad = 3.dp.toPx()
                    val topLeft = Offset(badgeX - pillPad, badgeY - 1.dp.toPx())
                    val size = Size(layout.size.width.toFloat() + pillPad * 2f, layout.size.height.toFloat() + 2.dp.toPx())

                    drawRoundRect(color = pill, topLeft = topLeft, size = size, cornerRadius = corner)
                    drawRoundRect(
                        color = pillBorder,
                        topLeft = topLeft,
                        size = size,
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawText(layout, topLeft = Offset(badgeX, badgeY))
                }
            }
        }
    }
}

private class HitState {
    var swayAngle = 0f
    var fitScale = 1f
    var origin = Offset.Zero
    var labels: List<Pair<TreeLabel, androidx.compose.ui.geometry.Rect>> = emptyList()
}

/** Cache geometry work across wind frames and panning; only size or zoom changes require relayout. */
private class ViewportCache {
    var size = Size.Zero
    var fit: TreeLayout.ViewportFit? = null
    var labelScale = Float.NaN
    var labelZoom = Float.NaN
    var labels: List<TreeLayout.PlacedLabel>? = null
}

private fun DrawScope.drawGround(g: TreeGeometry, color: Color, width: Float) {
    val ridge = RIDGE
    val w = max(g.width, 300f) * 1.15f
    val pts = ridge.map { (x, y) ->
        Pt(-w / 2f + x / 360f * w, (y - 52f) * w / 360f * 0.45f + 2f)
    }
    drawPath(smoothOpenPath(pts), color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private val RIDGE = listOf(
    0f to 52f, 22f to 46f, 38f to 49f, 58f to 36f, 70f to 40f, 92f to 22f, 104f to 30f, 118f to 10f, 128f to 22f,
    140f to 18f, 160f to 34f, 176f to 30f, 196f to 42f, 214f to 33f, 232f to 38f, 250f to 26f, 266f to 34f,
    284f to 44f, 302f to 38f, 322f to 47f, 340f to 44f, 360f to 50f,
)

/** Max sway of the tree at its crown, in radians (~0.7°). */
private const val SWAY_RADIANS = 0.012f

/** Pinch-zoom limits: a little below fit so a large crown can be pulled into view, up to 3x for close reading. */
private const val MIN_ZOOM = 0.75f
private const val MAX_ZOOM = 3.0f

/**
 * A closed smooth outline through [pts]. Every segment is a Cubic Bézier built from Catmull-Rom
 * tangents, so the branch edges are genuinely cubic rather than straight chords.
 */
fun outlinePath(pts: List<Pt>): Path {
    val p = Path()
    if (pts.size < 3) return p
    val n = pts.size
    p.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until n) {
        val p0 = pts[(i - 1 + n) % n]
        val p1 = pts[i]
        val p2 = pts[(i + 1) % n]
        val p3 = pts[(i + 2) % n]
        p.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    p.close()
    return p
}

/** An open smooth curve through [pts] using the same Cubic Bézier (Catmull-Rom) construction. */
private fun smoothOpenPath(pts: List<Pt>): Path {
    val p = Path()
    if (pts.size < 2) return p
    val n = pts.size
    p.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until n - 1) {
        val p0 = pts[if (i - 1 < 0) 0 else i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[if (i + 2 > n - 1) n - 1 else i + 2]
        p.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    return p
}

/** A gently bowed Cubic Bézier stem joining a leaf's base back to the wood it grew from. */
private fun stemPath(from: Pt, to: Pt, bow: Float = 0.16f): Path {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy).coerceAtLeast(0.0001f)
    val nx = -dy / len
    val ny = dx / len
    val off = len * bow
    return Path().apply {
        moveTo(from.x, from.y)
        cubicTo(
            from.x + dx * 0.35f + nx * off, from.y + dy * 0.35f + ny * off,
            from.x + dx * 0.70f + nx * off, from.y + dy * 0.70f + ny * off,
            to.x, to.y,
        )
    }
}

package com.umair.purpose.growth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    val length: Float get() = hypot(x, y)
    fun normalized(): Pt = length.let { if (it == 0f) Pt(0f, -1f) else Pt(x / it, y / it) }
    fun normal(): Pt = Pt(-y, x)
    fun rotated(rad: Float): Pt = Pt(x * cos(rad) - y * sin(rad), x * sin(rad) + y * cos(rad))
}

data class Curve(val p0: Pt, val c1: Pt, val c2: Pt, val p3: Pt) {
    fun at(t: Float): Pt {
        val u = 1 - t
        return p0 * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + p3 * (t * t * t)
    }

    fun tangent(t: Float): Pt {
        val u = 1 - t
        return ((c1 - p0) * (3 * u * u) + (c2 - c1) * (6 * u * t) + (p3 - c2) * (3 * t * t)).normalized()
    }

    fun length(samples: Int = 24): Float {
        var len = 0f
        var prev = p0
        for (i in 1..samples) {
            val p = at(i / samples.toFloat())
            len += (p - prev).length
            prev = p
        }
        return len
    }
}

data class TreeStroke(val key: String, val curve: Curve, val w0: Float, val w1: Float, val kind: Kind) {
    enum class Kind { TRUNK, BRANCH, TWIG, ROOT, SEEDLING }

    fun widthAt(t: Float) = w0 + (w1 - w0) * t

    fun outline(samples: Int = 28, upTo: Float = 1f): List<Pt> {
        val n = max(4, (samples * upTo).toInt())
        val left = ArrayList<Pt>(n + 1)
        val right = ArrayList<Pt>(n + 1)
        for (i in 0..n) {
            val t = upTo * i / n
            val p = curve.at(t)
            val half = widthAt(t) / 2f
            val nrm = curve.tangent(t).normal()
            left += p + nrm * half
            right += p - nrm * half
        }
        val tipT = upTo
        val tip = curve.at(tipT)
        val dir = curve.tangent(tipT)
        val capR = widthAt(tipT) / 2f
        val base = curve.at(0f)
        val back = curve.tangent(0f) * -1f
        val baseR = widthAt(0f) / 2f
        return buildList {
            addAll(left)
            for (k in 1..5) add(tip + dir.rotated((-PI / 2 + PI * k / 6).toFloat()) * capR)
            addAll(right.asReversed())
            for (k in 1..5) add(base + back.rotated((-PI / 2 + PI * k / 6).toFloat()) * baseR)
        }
    }
}

data class LeafMark(
    val id: Long,
    val type: String,
    val base: Pt,
    val rotation: Float,
    val size: Float,
    val bright: Boolean,
    val strokeKey: String,
    val t: Float,
    /** Per-leaf opacity in 0.85..1.0, so overlapping foliage gains depth instead of merging into a blob. */
    val alpha: Float = 1f,
) {
    val center: Pt get() = base + Pt(cos(rotation), sin(rotation)) * (size * 0.5f)
}

data class LeafCluster(val strokeKey: String, val at: Pt, val count: Int, val area: String, val branch: String?)

data class TreeLabel(val text: String, val at: Pt, val alignEnd: Boolean, val kind: Kind, val area: String?, val branch: String?) {
    enum class Kind { VALUE, AREA, BRANCH }
}

data class TreeRing(val center: Pt, val halfWidth: Float, val angle: Float)

data class TreeGeometry(
    val strokes: List<TreeStroke>,
    val leaves: List<LeafMark>,
    val clusters: List<LeafCluster>,
    val labels: List<TreeLabel>,
    val rings: List<TreeRing>,
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    val leafCount: Int,
    val branchCount: Int,
) {
    val width get() = maxX - minX
    val height get() = maxY - minY
}

data class TreeInput(
    val values: List<String>,
    val chapters: Int,
    val ageMonths: Int,
    val leaves: List<TreeLeaf>,
    val branchOrder: Map<String, List<String>> = emptyMap(),
)

data class TreeLeaf(val id: Long, val type: String, val area: String, val branch: String?, val decidedAt: Long, val bright: Boolean = false)

object TreeLayout {
    val LEFT = listOf("eq", "mindset", "character", "habits")
    val RIGHT = listOf("studies_career", "health", "relationships", "money", "meaning", "rest_joy")

    private val ATTACH = mapOf(
        "studies_career" to 0.24f, "eq" to 0.35f, "health" to 0.38f, "mindset" to 0.52f,
        "relationships" to 0.54f, "character" to 0.68f, "money" to 0.68f, "meaning" to 0.80f,
        "habits" to 0.86f, "rest_joy" to 0.92f,
    )
    val AREA_NAMES = mapOf(
        "eq" to "Emotions", "habits" to "Habits", "mindset" to "Mindset", "character" to "Character",
        "studies_career" to "Studies and career", "health" to "Health", "relationships" to "Relationships",
        "money" to "Money", "meaning" to "Meaning", "rest_joy" to "Rest and joy",
    )

    const val CLUSTER_OVER = 12
    private const val CLUSTER_SHOWN = 7
    private const val DEG = (PI / 180).toFloat()

    /** Above this many leaves on one twig the foliage is thinned and shrunk so a dense branch reads as a canopy. */
    private const val DENSE_LEAVES = 15

    /** The floor a crowded node's foliage is scaled to, so a cluster still reads as a canopy and never vanishes. */
    private const val MIN_DENSITY_SCALE = 0.65f

    /**
     * UPDATE-21: level of detail. At or above [DETAIL_ZOOM] the tree shows every name and the leaf-count badges;
     * below it, only the main life-area headers, so a dense crown stays legible when it's zoomed out.
     * UPDATE-22: raised to 1.35 so the sub-branch names and junction badges only fade in once he has really
     * zoomed into the crown.
     */
    const val DETAIL_ZOOM = 1.35f

    /** The labels worth drawing at a given zoom. */
    fun labelsForZoom(g: TreeGeometry, zoom: Float): List<TreeLabel> =
        if (zoom >= DETAIL_ZOOM) g.labels else g.labels.filter { it.kind == TreeLabel.Kind.AREA }

    fun layout(input: TreeInput): TreeGeometry {
        val strokes = mutableListOf<TreeStroke>()
        val leaves = mutableListOf<LeafMark>()
        val clusters = mutableListOf<LeafCluster>()
        val labels = mutableListOf<TreeLabel>()
        val byArea = input.leaves.groupBy { it.area }.filterKeys { it in ATTACH }

        val trunkRng = rng("trunk")
        val seedling = byArea.isEmpty()
        val height = if (seedling) 46f
        else 238f + 14f * min(input.ageMonths, 120).toFloat().pow(0.8f) + 11f * sqrt(input.leaves.size.toFloat())

        val lean = (trunkRng.nextFloat() - 0.5f) * 0.08f * height
        val sway = (if (trunkRng.nextBoolean()) 1f else -1f) * (0.04f + trunkRng.nextFloat() * 0.025f) * height
        val trunk = Curve(
            Pt(0f, 0f),
            Pt(sway, -height * 0.32f),
            Pt(lean - sway * 0.65f, -height * 0.66f),
            Pt(lean, -height),
        )

        val trunkW0 = if (seedling) 2.4f else min(16.5f, 7.0f + 0.34f * sqrt(input.leaves.size.toFloat()) + 0.08f * min(input.chapters, 24))
        val trunkW1 = if (seedling) 0.9f else 1.8f
        strokes += TreeStroke("trunk", trunk, trunkW0, trunkW1, if (seedling) TreeStroke.Kind.SEEDLING else TreeStroke.Kind.TRUNK)

        if (seedling) {
            val top = trunk.at(1f)
            strokes += TreeStroke("seedling:a", Curve(top, top + Pt(-3f, -4f), top + Pt(-8f, -7f), top + Pt(-12f, -7f)), 1.0f, 0.5f, TreeStroke.Kind.SEEDLING)
            strokes += TreeStroke("seedling:b", Curve(top + Pt(0f, 2f), top + Pt(3f, -2f), top + Pt(7f, -4f), top + Pt(10f, -3f)), 0.9f, 0.5f, TreeStroke.Kind.SEEDLING)
        } else {
            val top = trunk.at(1f)
            val up = trunk.tangent(1f)
            val cr = rng("crown")
            for (k in listOf(-1f, 1f)) {
                val d = up.rotated(k * (22f + cr.nextFloat() * 10f) * DEG)
                val len = height * (0.08f + cr.nextFloat() * 0.025f)
                strokes += TreeStroke(
                    "crown:${if (k < 0) "l" else "r"}",
                    Curve(top, top + d * (len * 0.42f), top + d * (len * 0.76f) + Pt(0f, -len * 0.08f), top + d * len + Pt(0f, -len * 0.12f)),
                    trunkW1, 0.55f, TreeStroke.Kind.TWIG,
                )
            }
        }

        val rings = if (seedling) emptyList() else (1..min(input.chapters, 24)).map { k ->
            val n = min(input.chapters, 24)
            val t = 0.82f * k / (n + 1)
            val dir = trunk.tangent(t)
            TreeRing(trunk.at(t), (trunkW0 + (trunkW1 - trunkW0) * t) / 2f + 1.6f, atan2(dir.y, dir.x) + (PI / 2).toFloat())
        }

        val values = input.values.take(7)
        values.forEachIndexed { i, v ->
            val r = rng("root:$v")
            val spread = if (values.size == 1) 0.5f else i / (values.size - 1).toFloat()
            val angle = (182f + spread * 176f + (r.nextFloat() - 0.5f) * 6f) * DEG
            val grow = if (seedling) 1f else sqrt(max(1f, height / 300f))
            val stagger = when (i % 3) { 0 -> 1.05f; 1 -> 0.72f; else -> 0.90f }
            val len = ((if (seedling) 30f else 68f) + r.nextFloat() * (if (seedling) 8f else 14f)) * grow * stagger
            val dir = Pt(cos(angle), -sin(angle))
            val start = Pt((spread - 0.5f) * trunkW0 * 0.9f, 1.2f)
            val end = start + dir * len + Pt(0f, len * 0.18f)
            val c1 = start + Pt(dir.x * len * 0.25f, len * 0.22f)
            val c2 = start + dir * (len * 0.7f) + Pt((r.nextFloat() - 0.5f) * 8f, len * 0.05f)
            strokes += TreeStroke("root:$i", Curve(start, c1, c2, end), if (seedling) 1.2f else trunkW0 * 0.42f, 0.4f, TreeStroke.Kind.ROOT)
            val yOffset = when (i % 3) { 0 -> 8f; 1 -> 24f; else -> 40f }
            labels += TreeLabel(v, end + dir * 6f + Pt(if (dir.x < 0) -2f else 2f, yOffset), alignEnd = dir.x < -0.2f, kind = TreeLabel.Kind.VALUE, area = null, branch = null)
        }

        val leafSize = LEAF_SIZE
        for ((area, areaLeaves) in byArea.entries.sortedBy { ATTACH.getValue(it.key) }) {
            val side = if (area in LEFT) -1f else 1f
            val r = rng("area:$area")
            val ta = (ATTACH.getValue(area) + (r.nextFloat() - 0.5f) * 0.015f).coerceIn(0.24f, 0.94f)
            val from = trunk.at(ta)
            val count = areaLeaves.size

            val reachScale = when (area) {
                "studies_career" -> 1.20f
                "health" -> 1.10f
                "relationships" -> 0.92f
                "money" -> 0.88f
                "meaning" -> 0.96f
                else -> 1.0f
            }
            val reach = (112f + 35f * sqrt(count.toFloat())).coerceAtMost(315f) * (1.26f - 0.56f * ta) * reachScale * (0.94f + r.nextFloat() * 0.12f)
            val theta = side * (84f - 52f * ta + (r.nextFloat() - 0.5f) * 6f) * DEG
            val dir = Pt(sin(theta), -cos(theta))
            val lift = reach * (0.24f + r.nextFloat() * 0.08f)
            val end = from + dir * reach + Pt(0f, -lift)
            val sag = reach * 0.06f * (1f - ta)
            val c1 = from + dir.rotated(side * 14f * DEG) * (reach * 0.42f) + Pt(0f, sag)
            val c2 = from + dir * (reach * 0.78f) + Pt(0f, -lift * 0.35f)
            val trunkWidth = trunkW0 + (trunkW1 - trunkW0) * ta
            val w0 = min(trunkWidth * 0.72f, 1.8f + 0.55f * sqrt(count.toFloat()))
            val branch = TreeStroke("area:$area", Curve(from, c1, c2, end), w0, 0.9f, TreeStroke.Kind.BRANCH)
            strokes += branch
            labels += TreeLabel(
                AREA_NAMES[area] ?: area, end + Pt(0f, -24f), alignEnd = side > 0, kind = TreeLabel.Kind.AREA, area = area, branch = null,
            )

            val named = areaLeaves.mapNotNull { it.branch }.distinct()
            val order = (input.branchOrder[area].orEmpty().filter { it in named } + named).distinct()
            order.forEachIndexed { i, name ->
                val br = rng("branch:$area/$name")
                val tb = (0.34f + (i + 0.5f) / order.size * 0.5f + (br.nextFloat() - 0.5f) * 0.04f).coerceIn(0.28f, 0.88f)
                val at = branch.curve.at(tb)
                val tan = branch.curve.tangent(tb)
                val upFirst = if ((tan.normal() * side).y < 0) 1f else -1f
                val turn = (if (i % 2 == 0) upFirst else -upFirst) * side * (24f + br.nextFloat() * 10f) * DEG
                val k = areaLeaves.count { it.branch == name }
                val len = (42f + 15f * sqrt(k.toFloat())).coerceAtMost(130f) * (0.88f + br.nextFloat() * 0.24f)
                val d = tan.rotated(turn)
                val tip = at + d * len + Pt(0f, -len * 0.2f)
                val bend = d.normal() * (len * 0.08f * (if (br.nextBoolean()) 1f else -1f))
                val twig = TreeStroke(
                    "branch:$area/$name",
                    Curve(at, at + d * (len * 0.38f) + bend, at + d * (len * 0.74f) + Pt(0f, -len * 0.14f), tip),
                    max(1.3f, branch.widthAt(tb) * 0.7f), 0.6f, TreeStroke.Kind.TWIG,
                )
                strokes += twig
                labels += TreeLabel(name, tip + d * 14f + Pt(0f, -3f), alignEnd = d.x < 0, kind = TreeLabel.Kind.BRANCH, area = area, branch = name)
                placeLeaves(twig, areaLeaves.filter { it.branch == name }.sortedWith(compareBy({ it.decidedAt }, { it.id })), leafSize, 0.28f, area, name, leaves, clusters)
            }
            placeLeaves(branch, areaLeaves.filter { it.branch == null }.sortedWith(compareBy({ it.decidedAt }, { it.id })), leafSize, 0.22f, area, null, leaves, clusters)
        }

        // Separate the entire crown, not just attachment slots on each individual twig.
        val spaced = separateLeaves(leaves)
        leaves.clear()
        leaves.addAll(spaced)

        val halfW = if (seedling) SEEDLING_HALF_WIDTH else MIN_HALF_WIDTH
        var minX = -halfW; var minY = -(if (seedling) SEEDLING_HEIGHT else MIN_HEIGHT); var maxX = halfW; var maxY = if (seedling) 60f else 90f
        fun take(p: Pt, pad: Float = 0f) {
            minX = min(minX, p.x - pad); maxX = max(maxX, p.x + pad); minY = min(minY, p.y - pad); maxY = max(maxY, p.y + pad)
        }
        strokes.forEach { s -> for (i in 0..12) take(s.curve.at(i / 12f), s.w0) }
        leaves.forEach { take(it.center, it.size * 0.82f + 3f) }
        labels.forEach { l ->
            val w = l.text.length * LABEL_CHAR + 22f
            take(Pt(if (l.alignEnd) l.at.x - w else l.at.x, l.at.y - 9f))
            take(Pt(if (l.alignEnd) l.at.x else l.at.x + w, l.at.y + 4f))
        }
        return TreeGeometry(
            strokes = strokes, leaves = leaves, clusters = clusters, labels = labels, rings = rings,
            minX = minX, minY = minY, maxX = maxX, maxY = maxY,
            leafCount = input.leaves.count { it.area in ATTACH },
            branchCount = byArea.size,
        )
    }

    const val LABEL_CHAR = 8.5f
    private const val MIN_HALF_WIDTH = 190f
    private const val MIN_HEIGHT = 380f
    private const val SEEDLING_HALF_WIDTH = 120f
    private const val SEEDLING_HEIGHT = 120f
    /** The base leaf blade length. Kept here so the label fallback can clear the same foliage envelope. */
    private const val LEAF_SIZE = 19f
    /** How far past the foliage a label is pushed when no clear space is found near its tip. */
    private const val FOLIAGE_PUSH = LEAF_SIZE * 2.2f

    data class PlacedLabel(val label: TreeLabel, val at: Pt)

    data class ViewportFit(val scale: Float, val center: Pt)

    /** Fit actual wood, foliage and measured overview labels, rather than estimated hidden text. */
    fun fitViewport(
        g: TreeGeometry, width: Float, height: Float, padding: Float,
        measure: (TreeLabel) -> Pair<Float, Float>,
        showLabels: Boolean = true,
        maxScale: Float = 3.2f,
    ): ViewportFit {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        fun include(p: Pt, radius: Float = 0f) {
            left = min(left, p.x - radius); right = max(right, p.x + radius)
            top = min(top, p.y - radius); bottom = max(bottom, p.y + radius)
        }
        g.strokes.forEach { stroke ->
            // Control points conservatively contain the whole Bezier curve.
            listOf(stroke.curve.p0, stroke.curve.c1, stroke.curve.c2, stroke.curve.p3)
                .forEach { include(it, stroke.w0) }
        }
        g.leaves.forEach { include(it.center, it.size * 0.82f + 3f) }
        if (!left.isFinite()) { include(Pt(0f, 0f)); include(Pt(0f, -46f)) }
        val availableW = (width - padding * 2f).coerceAtLeast(1f)
        val availableH = (height - padding * 2f).coerceAtLeast(1f)
        fun scale() = min(availableW / (right - left).coerceAtLeast(1f),
            availableH / (bottom - top).coerceAtLeast(1f)).coerceIn(0.001f, maxScale)
        var fit = scale()
        val bodyLeft = left; val bodyTop = top; val bodyRight = right; val bodyBottom = bottom
        // Reflow at the new scale: do not retain obsolete positions from earlier iterations.
        if (showLabels) repeat(20) {
            left = bodyLeft; top = bodyTop; right = bodyRight; bottom = bodyBottom
            visibleLabels(g, fit, measure, labels = labelsForZoom(g, 1f)).forEach { p ->
                val (w, h) = measure(p.label)
                val x = p.at.x - if (p.label.alignEnd) w / fit else 0f
                include(Pt(x, p.at.y - h * 0.8f / fit), 6f / fit)
                include(Pt(x + w / fit, p.at.y + h * 0.25f / fit), 6f / fit)
            }
            fit = scale()
        }
        return ViewportFit(fit, Pt((left + right) / 2f, (top + bottom) / 2f))
    }

    fun visibleLabels(
        g: TreeGeometry,
        scale: Float,
        measure: (TreeLabel) -> Pair<Float, Float>,
        gapPx: Float = 4f,
        /** The labels eligible to be placed; zoom level-of-detail passes a subset here. */
        labels: List<TreeLabel> = g.labels,
    ): List<PlacedLabel> {
        data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
            fun hits(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
        }
        val placed = mutableListOf<Box>()
        val out = mutableListOf<PlacedLabel>()

        val clusterBoxes = g.clusters.map { c ->
            val r = 18f
            Box(c.at.x - r, c.at.y - r, c.at.x + r, c.at.y + r)
        }
        // UPDATE-22: the branch's foliage is an obstacle too. A label must never be placed on top of leaves.
        val leafBoxes = g.leaves.map { leaf ->
            val c = leaf.center
            val r = leaf.size * 0.7f
            Box(c.x - r, c.y - r, c.x + r, c.y + r)
        }

        fun boxAt(l: TreeLabel, at: Pt): Box {
            val (wPx, hPx) = measure(l)
            val w = wPx / scale
            val h = hPx / scale
            val gap = gapPx / scale
            val left = if (l.alignEnd) at.x - w else at.x
            return Box(left - gap, at.y - h * 0.8f - gap, left + w + gap, at.y + h * 0.25f + gap)
        }

        val order = labels.sortedWith(compareBy({ it.kind != TreeLabel.Kind.AREA }, { it.kind != TreeLabel.Kind.VALUE }, { it.at.y }))
        for (l in order) {
            val (_, hPx) = measure(l)
            val line = hPx / scale
            val tries = (if (l.kind == TreeLabel.Kind.AREA) (1..16).flatMap { listOf(-it * 1.2f to 0f, it * 1.2f to 0f) } else emptyList())
            val candidates = listOf(
                0f to 0f,
                -1.2f to 0f, 1.2f to 0f,
                0f to 1.5f, -1.2f to 1.5f, 1.2f to 1.5f,
                -2.4f to 0f, 2.4f to 0f,
                0f to 3.0f, -1.2f to 3.0f, 1.2f to 3.0f,
                -3.6f to 0f, 3.6f to 0f
            )
            var chosen: Pair<Pt, Box>? = null

            for ((dy, dx) in candidates + tries) {
                val side = if (l.kind == TreeLabel.Kind.AREA) { if (l.area in LEFT) -1f else 1f } else if (l.alignEnd) -1f else 1f
                val shiftOut = side * dx * 16f / scale
                val at = l.at + Pt(shiftOut, dy * line)
                val box = boxAt(l, at)
                val clear = placed.none { it.hits(box) } && clusterBoxes.none { it.hits(box) } && leafBoxes.none { it.hits(box) }
                if (clear) { chosen = at to box; break }
            }

            if (chosen == null) {
                // Move toward the side of this branch, not radially toward the top of the tree.
                val outward = Pt(if (l.kind == TreeLabel.Kind.AREA) { if (l.area in LEFT) -1f else 1f } else if (l.alignEnd) -1f else 1f, 0f)
                for (step in 0 until 60) {
                    val at = l.at + outward * (FOLIAGE_PUSH * (1f + step * 0.6f))
                    val box = boxAt(l, at)
                    if (placed.none { it.hits(box) } && leafBoxes.none { it.hits(box) } && clusterBoxes.none { it.hits(box) }) {
                        chosen = at to box
                        break
                    }
                }
            }
            chosen?.let { (at, box) -> placed += box; out += PlacedLabel(l, at) }
        }
        return out
    }

    /**
     * Organic canopy placement.
     *
     * - **Arc-length spacing.** Attachment points are laid out by distance along the curve, not by raw Bézier
     *   parameter, so consecutive leaves keep a minimum physical gap and never stack on top of each other on a
     *   short stem.
     * - **Terminal fan.** When a twig is too short to host every leaf, the overflow is spread into a natural
     *   rosette around the branch tip instead of piling up on the wood.
     * - **Density scaling.** Nodes with more than [DENSE_LEAVES] leaves shrink their foliage (down to
     *   [MIN_DENSITY_SCALE]×) so crowded branches don't read as one large flat blob.
     * - **Organic angles.** Lateral leaves use the branch tangent as a base and fan out at 32–52° with a subtle
     *   ±6° pseudo-random jitter; a tiny petiole (drawn by the canvas) joins each base back to the wood.
     */
    private fun placeLeaves(
        s: TreeStroke, list: List<TreeLeaf>, size: Float, tStart: Float, area: String, branch: String?,
        out: MutableList<LeafMark>, clusters: MutableList<LeafCluster>,
    ) {
        if (list.isEmpty()) return
        val n = list.size

        // Dense nodes (>DENSE_LEAVES) shrink their leaves proportionally, clamped to 0.65x, so the canopy stays airy.
        val densityScale =
            if (n > DENSE_LEAVES) (1f - (n - DENSE_LEAVES) * 0.035f).coerceIn(MIN_DENSITY_SCALE, 1f) else 1f
        val leafSize = size * densityScale

        // A very crowded node draws only its last few leaves and reports the true count in a badge.
        val crowded = n > CLUSTER_OVER
        val shown = if (crowded) list.takeLast(CLUSTER_SHOWN) else list

        val tipPt = s.curve.at(1f)
        val tipTan = s.curve.tangent(1f)

        // Cumulative arc length, so attachment points can be spaced by true distance rather than parameter t.
        val steps = 24
        val cum = FloatArray(steps + 1)
        var prev = s.curve.at(0f)
        for (i in 1..steps) {
            val p = s.curve.at(i / steps.toFloat())
            cum[i] = cum[i - 1] + (p - prev).length
            prev = p
        }
        val total = cum[steps]
        fun lenAtT(t: Float): Float {
            val x = t.coerceIn(0f, 1f) * steps
            val i = x.toInt().coerceAtMost(steps - 1)
            return cum[i] + (cum[i + 1] - cum[i]) * (x - i)
        }
        fun tAtLen(len: Float): Float {
            val target = len.coerceIn(0f, total)
            for (i in 1..steps) {
                if (cum[i] >= target) {
                    val seg = cum[i] - cum[i - 1]
                    val f = if (seg <= 0.0001f) 0f else (target - cum[i - 1]) / seg
                    return ((i - 1) + f) / steps.toFloat()
                }
            }
            return 1f
        }

        val startLen = lenAtT(tStart)
        val usable = max(0f, total - startLen)
        val spacing = (leafSize * 0.78f).coerceAtLeast(6f)
        val fitCount = max(1, (usable / spacing).toInt() + 1)
        val along = shown.size.coerceAtMost(fitCount)

        shown.forEachIndexed { j, leaf ->
            val r = rng("leaf:${leaf.id}")
            val leafAlpha = 0.85f + r.nextFloat() * 0.15f

            if (j < along) {
                // Even arc-length slots keep a minimum gap between attachment points along the stem.
                val slot = if (along > 1) j / (along - 1f) else 1f
                val t = tAtLen(startLen + usable * slot)
                val p = s.curve.at(t)
                val tan = s.curve.tangent(t)
                val baseAngle = atan2(tan.y, tan.x)
                val side = if ((j + (leaf.id and 1L).toInt()) % 2 == 0) 1f else -1f
                val lateral = (32f + r.nextFloat() * 20f + (r.nextFloat() - 0.5f) * 12f) * DEG
                val angle = baseAngle + side * lateral
                val halfW = s.widthAt(t) / 2f
                val base = p + tan.normal() * (side * (halfW + leafSize * 0.08f))
                out += LeafMark(leaf.id, leaf.type, base, angle, leafSize * (0.92f + r.nextFloat() * 0.12f), leaf.bright, s.key, t, leafAlpha)
            } else {
                // No room left on the stem: fan the overflow radially around the tip into a little rosette.
                val fanCount = max(1, shown.size - along)
                val spread = if (fanCount > 1) (j - along) / (fanCount - 1f) else 0.5f
                val fanDeg = (-72f + spread * 144f) + (r.nextFloat() - 0.5f) * 10f
                val angle = atan2(tipTan.y, tipTan.x) + fanDeg * DEG
                val dir = Pt(cos(angle), sin(angle))
                val base = tipPt + dir * (leafSize * 0.12f) + tipTan * (leafSize * 0.05f)
                out += LeafMark(leaf.id, leaf.type, base, angle, leafSize * (0.86f + r.nextFloat() * 0.12f), leaf.bright, s.key, 1f, leafAlpha)
            }
        }

        if (crowded) {
            // UPDATE-22: the count badge lives in the crook of the fork at the branch's attachment point,
            // not on the twig tip, so it never sits on top of the terminal leaf cluster.
            val fork = s.curve.at(0f)
            val forkDir = s.curve.tangent(0f)
            val badgePos = fork + forkDir * 24f + forkDir.normal() * -14f
            clusters += LeafCluster(s.key, badgePos, n, area, branch)
        }
    }

    /** Conservative blade envelopes include paired leaves and buds, with a visible gap between them. */
    fun separateLeaves(leaves: List<LeafMark>): List<LeafMark> {
        val placed = ArrayList<LeafMark>(leaves.size)
        for (leaf in leaves) {
            fun clear(candidate: LeafMark) = placed.none {
                (candidate.center - it.center).length < (candidate.size + it.size) * 0.82f + 2f
            }
            var chosen = leaf
            if (!clear(chosen)) {
                // Closest empty slot first, with deterministic angular sampling. The petiole still joins
                // the same attachment point, so tapping and growing keep the original milestone identity.
                search@ for (ring in 1..100) {
                    for (slot in 0 until 16) {
                        val angle = leaf.rotation + slot * (2f * PI.toFloat() / 16f)
                        val offset = Pt(cos(angle), sin(angle)) * (ring * leaf.size * 0.45f)
                        val candidate = leaf.copy(base = leaf.base + offset)
                        if (clear(candidate)) { chosen = candidate; break@search }
                    }
                }
            }
            placed += chosen
        }
        return placed
    }

    fun leafShape(type: String, size: Float): LeafShape {
        fun blade(len: Float, wid: Float, rot: Float, notch: Boolean): List<Pt> {
            val pts = mutableListOf<Pt>()
            val steps = 16
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = len * t
                var y = -wid * sin(PI.toFloat() * t.pow(0.85f)) * (1f - 0.18f * t)
                if (notch && t in 0.48f..0.66f) {
                    val k = 1f - abs((t - 0.57f) / 0.09f)
                    y *= 1f - 0.55f * k.coerceIn(0f, 1f)
                }
                pts += Pt(x, y)
            }
            for (i in steps - 1 downTo 1) {
                val t = i / steps.toFloat()
                pts += Pt(len * t, wid * 0.92f * sin(PI.toFloat() * t.pow(0.9f)) * (1f - 0.22f * t))
            }
            return pts.map { it.rotated(rot) }
        }
        val len = size
        val wid = size * 0.34f
        return when (type) {
            "relationship" -> LeafShape(
                listOf(blade(len * 0.86f, wid * 0.9f, -24f * DEG, false), blade(len * 0.86f, wid * 0.9f, 24f * DEG, false)),
                vein = null, bud = null,
            )
            "habit_unlearned" -> LeafShape(listOf(blade(len, wid, 0f, true)), vein = null, bud = null)
            "accomplishment" -> LeafShape(listOf(blade(len, wid, 0f, false)), vein = null, bud = Pt(len + size * 0.16f, 0f) to size * 0.11f)
            "inner_growth" -> LeafShape(listOf(blade(len, wid, 0f, false)), vein = Pt(size * 0.12f, 0f) to Pt(len * 0.86f, 0f), bud = null)
            else -> LeafShape(listOf(blade(len, wid, 0f, false)), vein = null, bud = null)
        }
    }

    data class LeafShape(val blades: List<List<Pt>>, val vein: Pair<Pt, Pt>?, val bud: Pair<Pt, Float>?)

    fun rng(name: String): Random {
        var h = 0x811C9DC5.toInt()
        for (c in name) { h = h xor c.code; h *= 16777619 }
        return Random(h)
    }
}

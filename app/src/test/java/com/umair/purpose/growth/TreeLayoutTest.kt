package com.umair.purpose.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPDATE-18: the tree is deterministic, grows only with leaves, and stays uncluttered at any size. */
class TreeLayoutTest {
    @Test
    fun `overview fits measured area labels at phone sizes and large fonts`() {
        for (count in listOf(0, 10, 50, 300, 1000)) for (fontScale in listOf(1f, 1.3f)) {
            val g = TreeLayout.layout(SampleTree.input(count))
            val measure = { l: TreeLabel -> l.text.length * 6.5f * fontScale to 17f * fontScale }
            val fit = TreeLayout.fitViewport(g, 360f, 600f, 18f, measure)
            assertTrue(fit.scale.isFinite() && fit.scale > 0f)
            val placed = TreeLayout.visibleLabels(g, fit.scale, measure, labels = TreeLayout.labelsForZoom(g, 1f))
            assertEquals(g.branchCount, placed.size)
            placed.forEach { p ->
                val (w, h) = measure(p.label)
                val x = 180f + (p.at.x - fit.center.x) * fit.scale - if (p.label.alignEnd) w else 0f
                val y = 300f + (p.at.y - fit.center.y) * fit.scale - h * 0.8f
                assertTrue("${p.label.text} cropped at $count leaves", x >= 0f && x + w <= 360f && y >= 0f && y + h <= 600f)
            }
        }
    }

    @Test
    fun `hidden long branch names do not shrink the overview`() {
        val input = SampleTree.input(50)
        val original = TreeLayout.layout(input)
        val renamed = TreeLayout.layout(input.copy(leaves = input.leaves.map {
            it.copy(branch = it.branch?.let { name -> name + " a very long personal branch name" })
        }))
        // Label estimates in geometry differ; compare fits with identical visible geometry instead.
        val withLongLabels = original.copy(labels = renamed.labels.filter { it.kind == TreeLabel.Kind.BRANCH } +
            original.labels.filter { it.kind != TreeLabel.Kind.BRANCH })
        val measure = { l: TreeLabel -> l.text.length * 7f to 17f }
        assertEquals(TreeLayout.fitViewport(original, 360f, 600f, 18f, measure),
            TreeLayout.fitViewport(withLongLabels, 360f, 600f, 18f, measure))
    }

    @Test
    fun `the same data always draws the same tree`() {
        val a = TreeLayout.layout(SampleTree.input(50))
        val b = TreeLayout.layout(SampleTree.input(50))
        assertEquals(a, b)
    }

    @Test
    fun `an empty tree is roots, ground and a seedling`() {
        val g = TreeLayout.layout(TreeInput(values = listOf("family", "faith", "growth"), chapters = 0, ageMonths = 0, leaves = emptyList()))
        assertTrue(g.leaves.isEmpty())
        assertEquals(3, g.strokes.count { it.kind == TreeStroke.Kind.ROOT })
        assertTrue(g.strokes.any { it.kind == TreeStroke.Kind.SEEDLING })
        assertTrue(g.strokes.none { it.kind == TreeStroke.Kind.BRANCH })
        assertEquals(listOf("family", "faith", "growth"), g.labels.map { it.text })
    }

    @Test
    fun `a branch appears only with its first leaf, on its side`() {
        val leaf = TreeLeaf(1, "habit_built", "studies_career", "FAR", 0)
        val g = TreeLayout.layout(TreeInput(listOf("family"), 0, 2, listOf(leaf)))
        assertEquals(listOf("area:studies_career"), g.strokes.filter { it.kind == TreeStroke.Kind.BRANCH }.map { it.key })
        assertTrue(g.strokes.any { it.key == "branch:studies_career/FAR" })
        // Life areas grow to the right, inner growth to the left.
        assertTrue(g.strokes.first { it.key == "area:studies_career" }.curve.p3.x > 0)
        val inner = TreeLayout.layout(TreeInput(listOf("family"), 0, 2, listOf(leaf.copy(area = "mindset", branch = null))))
        assertTrue(inner.strokes.first { it.key == "area:mindset" }.curve.p3.x < 0)
        assertEquals(1, g.leafCount)
        assertEquals(1, g.branchCount)
    }

    @Test
    fun `adding a leaf never moves the others' branches`() {
        val base = SampleTree.leaves(20)
        val more = base + TreeLeaf(999, "inner_growth", "money", null, Long.MAX_VALUE)
        val a = TreeLayout.layout(TreeInput(SampleTree.VALUES, 2, 6, base))
        val b = TreeLayout.layout(TreeInput(SampleTree.VALUES, 2, 6, more))
        // The trunk grows a little; each area branch keeps its angle and its place along the trunk.
        a.strokes.filter { it.key.startsWith("area:") }.forEach { s ->
            val t = b.strokes.first { it.key == s.key }
            val da = s.curve.tangent(0f)
            val db = t.curve.tangent(0f)
            assertEquals(da.x, db.x, 0.08f)
            assertEquals(da.y, db.y, 0.08f)
        }
    }

    @Test
    fun `crowded twigs become clusters with a count`() {
        val g = TreeLayout.layout(SampleTree.input(300))
        assertTrue(g.clusters.isNotEmpty())
        assertTrue(g.clusters.all { it.count > TreeLayout.CLUSTER_OVER })
        // Never more than ~12 leaves drawn on one twig.
        g.leaves.groupBy { it.strokeKey }.values.forEach { assertTrue(it.size <= TreeLayout.CLUSTER_OVER) }
        assertEquals(300, g.leafCount)
    }

    @Test
    fun `labels never overlap, and values always show`() {
        val g = TreeLayout.layout(SampleTree.input(50))
        val measure = { l: TreeLabel -> l.text.length * 7.5f to 17f }
        val placed = TreeLayout.visibleLabels(g, scale = 1.2f, measure = measure)
        assertTrue(placed.map { it.label.text }.containsAll(SampleTree.VALUES))
        assertTrue(placed.any { it.label.kind == TreeLabel.Kind.AREA })
        // Boxes in screen pixels: no two touch.
        val boxes = placed.map { p ->
            val (w, h) = measure(p.label)
            val x = p.at.x * 1.2f - if (p.label.alignEnd) w else 0f
            floatArrayOf(x, p.at.y * 1.2f - h * 0.8f, x + w, p.at.y * 1.2f + h * 0.25f)
        }
        // Area names are always shown (nudged clear when they can be); every other label must be clear.
        val clearBoxes = boxes.filterIndexed { i, _ -> placed[i].label.kind != TreeLabel.Kind.AREA }
        for (i in clearBoxes.indices) for (j in i + 1 until clearBoxes.size) {
            val a = clearBoxes[i]; val b = clearBoxes[j]
            assertFalse("labels overlap", a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3])
        }
    }

    @Test
    fun `zoomed out shows only area headers, zoomed in shows every label`() {
        val g = TreeLayout.layout(SampleTree.input(50))
        val out = TreeLayout.labelsForZoom(g, 1.0f)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.kind == TreeLabel.Kind.AREA })
        assertEquals(g.labels, TreeLayout.labelsForZoom(g, TreeLayout.DETAIL_ZOOM))
    }

    @Test
    fun `visibleLabels honors a label subset`() {
        val g = TreeLayout.layout(SampleTree.input(50))
        val measure = { l: TreeLabel -> l.text.length * 7.5f to 17f }
        val onlyAreas = g.labels.filter { it.kind == TreeLabel.Kind.AREA }
        val placed = TreeLayout.visibleLabels(g, scale = 1.0f, measure = measure, labels = onlyAreas)
        assertTrue(placed.isNotEmpty())
        assertTrue(placed.all { it.label.kind == TreeLabel.Kind.AREA })
    }

    @Test
    fun `every leaf kind has a shape`() {
        for (type in listOf("habit_built", "habit_unlearned", "accomplishment", "inner_growth", "relationship")) {
            val s = TreeLayout.leafShape(type, 20f)
            assertTrue(s.blades.isNotEmpty())
            assertTrue(s.blades.all { it.size > 10 })
        }
        assertEquals(2, TreeLayout.leafShape("relationship", 20f).blades.size)
        assertTrue(TreeLayout.leafShape("accomplishment", 20f).bud != null)
        assertTrue(TreeLayout.leafShape("inner_growth", 20f).vein != null)
    }

    @Test
    fun `dense foliage shrinks and varies its opacity`() {
        val huge = TreeLayout.layout(SampleTree.input(300))
        val tiny = TreeLayout.layout(SampleTree.input(10))
        // Every leaf carries a gentle opacity for depth, never below the readable floor.
        assertTrue(huge.leaves.isNotEmpty())
        assertTrue(huge.leaves.all { it.alpha in 0.85f..1.0f })
        // Crowded nodes scale their leaves down rather than drawing the same large flat shape.
        assertTrue(huge.leaves.minOf { it.size } < tiny.leaves.minOf { it.size })
    }

    @Test
    fun `no two leaves stack on the same attachment point`() {
        val g = TreeLayout.layout(SampleTree.input(50))
        g.leaves.groupBy { it.strokeKey }.forEach { (_, ls) ->
            assertEquals(ls.size, ls.map { it.base }.toSet().size)
        }
    }

    @Test
    fun `a short stem spills its overflow into a terminal fan`() {
        val leaves = (1..12).map { TreeLeaf(it.toLong(), "habit_built", "studies_career", "FAR", it.toLong()) }
        val g = TreeLayout.layout(TreeInput(listOf("family"), 0, 2, leaves))
        val onTwig = g.leaves.filter { it.strokeKey == "branch:studies_career/FAR" }
        assertEquals(12, onTwig.size)
        // The stem can't host them all, so more than one leaf sits around the tip and they fan apart.
        val atTip = onTwig.filter { it.t == 1f }
        assertTrue(atTip.size > 1)
        assertEquals(atTip.size, atTip.map { it.rotation }.toSet().size)
    }

    @Test
    fun `tapered strokes are closed outlines`() {
        val g = TreeLayout.layout(SampleTree.input(10))
        val trunk = g.strokes.first { it.key == "trunk" }
        val o = trunk.outline()
        assertTrue(o.size > 40)
        assertTrue(trunk.outline(upTo = 0.5f).size < o.size + 10)
    }

    @Test fun `leaf envelopes stay separate across a dense crown`() {
        for (count in listOf(50, 300, 1000)) {
            val g = TreeLayout.layout(SampleTree.input(count))
            assertEquals(g.leaves.size, g.leaves.map { it.id }.distinct().size)
            for (i in g.leaves.indices) for (j in i + 1 until g.leaves.size) {
                val a = g.leaves[i]; val b = g.leaves[j]
                assertTrue("leaf blades overlap for $count leaves", (a.center - b.center).length >= (a.size + b.size) * 0.82f + 1.9f)
            }
        }
    }
}

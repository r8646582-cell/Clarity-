package com.umair.purpose.ui.growth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.data.db.Milestone
import com.umair.purpose.growth.GrowthEngine
import com.umair.purpose.growth.MilestoneRules
import com.umair.purpose.growth.SampleTree
import com.umair.purpose.growth.TreeData
import com.umair.purpose.growth.TreeGeometry
import com.umair.purpose.growth.TreeLayout
import com.umair.purpose.growth.TreeRepository
import com.umair.purpose.system.Haptics
import com.umair.purpose.ui.common.ConfirmDialog
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PurposeSheet
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.theme.Purpose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class GrowthUi(
    val data: TreeData,
    val geometry: TreeGeometry,
    /** The growing animation's start, when he has just accepted a leaf. */
    val growFrom: Map<String, Float>?,
    val sample: Boolean,
)

@HiltViewModel
class GrowthViewModel @Inject constructor(
    saved: SavedStateHandle,
    repo: TreeRepository,
    private val growth: GrowthEngine,
) : ViewModel() {
    private val sampleCount: Int = saved.get<Int>("sample") ?: 0
    val growId: Long? = saved.get<Long>("grow")?.takeIf { it > 0 && sampleCount == 0 }

    val state: StateFlow<GrowthUi?> = (if (sampleCount > 0) flowOf(sampleData(sampleCount)) else repo.observe())
        .map { d ->
            GrowthUi(
                data = d,
                geometry = TreeLayout.layout(d.input),
                growFrom = growId?.let { TreeRepository.growFrom(d.input, it) },
                sample = sampleCount > 0,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Long-press → Remove: off the tree, never proposed again. Sample leaves aren't saved, so nothing happens. */
    fun remove(id: Long) {
        if (sampleCount > 0) return
        viewModelScope.launch { growth.remove(id) }
    }

    private fun sampleData(n: Int): TreeData {
        val input = SampleTree.input(n)
        val leaves = input.leaves.mapIndexed { i, l ->
            Milestone(
                id = l.id, type = l.type, area = l.area, branch = l.branch,
                title = "Sample leaf ${i + 1}", description = "A made-up leaf, so the drawing can be judged. Nothing is saved.",
                evidenceJson = MilestoneRules.encodeEvidence(listOf("Sample evidence line")), confidence = "high",
                proposedAt = l.decidedAt, decidedAt = l.decidedAt, status = Milestone.ACCEPTED,
            )
        }
        return TreeData(input, leaves)
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
private fun day(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DATE) } ?: ""

/**
 * UPDATE-18: the growth tree, full screen (DESIGN.md "Growth tree"). Pinch to zoom, drag to pan, double-tap to
 * reset; tap a leaf or a branch's name; long-press a leaf to remove it; Timeline shows the same leaves as a list.
 */
@Composable
fun GrowthScreen(onBack: () -> Unit, vm: GrowthViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var timeline by rememberSaveable { mutableStateOf(false) }
    var openLeaf by remember { mutableStateOf<Long?>(null) }
    var openArea by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var confirmRemove by remember { mutableStateOf<Long?>(null) }
    val view = remember { TreeView() }
    val reduceMotion = Purpose.reduceMotion
    val androidView = LocalView.current

    // The growing animation, once, when he has just accepted a leaf.
    var grown by rememberSaveable { mutableStateOf(false) }
    val branch = remember { Animatable(if (vm.growId == null || reduceMotion) 1f else 0f) }
    val leaf = remember { Animatable(if (vm.growId == null || reduceMotion) 1f else 0f) }
    val ready = ui != null
    LaunchedEffect(ready) {
        if (!ready || grown || vm.growId == null || reduceMotion) return@LaunchedEffect
        // The branch grows out first, then the new node unfurls with a springy, overshooting settle.
        branch.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow))
        // A single light haptic as the leaf opens.
        Haptics.click(androidView)
        leaf.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
        grown = true
    }

    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Your tree", onBack = onBack) {
            TextAction(if (timeline) "Tree" else "Timeline", { timeline = !timeline }, color = Purpose.colors.textMuted)
        }
        val state = ui ?: return@Column
        val g = state.geometry
        Meta(
            when {
                state.sample -> "A sample tree with ${g.leafCount} made-up leaves. Nothing here is saved."
                g.leafCount == 0 -> "Each leaf is something you really did, accepted by you."
                else -> "${g.leafCount} ${if (g.leafCount == 1) "leaf" else "leaves"}, ${g.branchCount} ${if (g.branchCount == 1) "branch" else "branches"}"
            },
            Modifier.padding(horizontal = SidePadding),
        )
        if (!timeline && g.leafCount > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = SidePadding), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Zoom out", { view.zoomBy(1f / 1.25f) }, Modifier.weight(1f), color = Purpose.colors.textMuted)
                TextAction("Zoom in", { view.zoomBy(1.25f) }, Modifier.weight(1f), color = Purpose.colors.textMuted)
                TextAction("Reset", { view.reset() }, color = Purpose.colors.textMuted)
            }
        }
        if (timeline) {
            Timeline(state.data.leaves, onOpen = { openLeaf = it })
        } else {
            Box(Modifier.fillMaxSize()) {
                val grow = vm.growId?.takeIf { !grown && !reduceMotion }?.let { id ->
                    state.growFrom?.let { GrowState(id, it, branch.value, leaf.value) }
                }
                GrowthTreeCanvas(
                    g = g,
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    interactive = true,
                    view = view,
                    grow = grow,
                    onLeaf = { openLeaf = it },
                    onLeafLongPress = { if (!state.sample) confirmRemove = it },
                    onArea = { area, br -> openArea = area to br },
                )
                if (g.leafCount == 0) {
                    Text(
                        "Your tree starts with your roots. It grows only with what you really do.",
                        style = Purpose.type.itemText.copy(fontSize = 20.sp, lineHeight = 28.sp),
                        color = Purpose.colors.textMuted,
                        modifier = Modifier.align(Alignment.TopStart).padding(horizontal = SidePadding, vertical = 24.dp),
                    )
                }
            }
        }
        val leaves = state.data.leaves
        openLeaf?.let { id ->
            leaves.firstOrNull { it.id == id }?.let { m ->
                LeafSheet(m, canRemove = !state.sample, onRemove = { openLeaf = null; confirmRemove = m.id }, onDismiss = { openLeaf = null })
            }
        }
        openArea?.let { (area, br) ->
            PurposeSheet(onDismiss = { openArea = null }) {
                Text(br ?: TreeLayout.AREA_NAMES[area] ?: area, style = Purpose.type.heading, color = Purpose.colors.text)
                if (br != null) Meta(TreeLayout.AREA_NAMES[area] ?: area)
                Spacer(Modifier.height(12.dp))
                leaves.filter { it.area == area && (br == null || it.branch == br) }.sortedBy { it.decidedAt ?: it.proposedAt }.forEach { m ->
                    Column(Modifier.fillMaxWidth().clickable { openArea = null; openLeaf = m.id }.padding(vertical = 10.dp)) {
                        Text(m.title, style = Purpose.type.itemText, color = Purpose.colors.text)
                        Meta(day(m.decidedAt))
                    }
                    Hairline()
                }
            }
        }
        confirmRemove?.let { id ->
            ConfirmDialog(
                text = "Remove this leaf from your tree? It won't be suggested again.",
                confirm = "Remove",
                onConfirm = { confirmRemove = null; vm.remove(id) },
                onDismiss = { confirmRemove = null },
            )
        }
    }
}

/** The same leaves as a simple dated list: the tree's accessible version. */
@Composable
private fun Timeline(leaves: List<Milestone>, onOpen: (Long) -> Unit) {
    if (leaves.isEmpty()) {
        Text(
            "Your tree starts with your roots. It grows only with what you really do.",
            style = Purpose.type.itemText.copy(fontSize = 20.sp, lineHeight = 28.sp),
            color = Purpose.colors.textMuted,
            modifier = Modifier.padding(horizontal = SidePadding, vertical = 24.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = SidePadding)) {
        items(leaves.sortedByDescending { it.decidedAt ?: it.proposedAt }, key = { it.id }) { m ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 12.dp)) {
                Meta(day(m.decidedAt))
                Text(m.title, style = Purpose.type.itemText, color = Purpose.colors.text)
                Meta((TreeLayout.AREA_NAMES[m.area] ?: m.area) + (m.branch?.let { ", $it" } ?: ""))
            }
            Hairline()
        }
    }
}

/** Tap a leaf: its title, date, what it is, the evidence and his own words. */
@Composable
fun LeafSheet(m: Milestone, canRemove: Boolean, onRemove: () -> Unit, onDismiss: () -> Unit) {
    PurposeSheet(onDismiss = onDismiss) {
        Text(m.title, style = Purpose.type.heading, color = Purpose.colors.text)
        Meta(day(m.decidedAt ?: m.proposedAt) + " · " + typeLabel(m.type))
        Spacer(Modifier.height(12.dp))
        Text(m.description, style = Purpose.type.itemText, color = Purpose.colors.text)
        val evidence = MilestoneRules.decodeEvidence(m.evidenceJson)
        if (evidence.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            evidence.forEach { Meta(it, Modifier.padding(vertical = 2.dp)) }
        }
        m.quote?.let {
            Spacer(Modifier.height(12.dp))
            Text("“$it”", style = Purpose.type.itemText, color = Purpose.colors.textMuted)
        }
        if (canRemove) {
            Row(Modifier.padding(top = 16.dp)) { TextAction("Remove", onRemove, color = Purpose.colors.textMuted) }
        }
    }
}

fun typeLabel(type: String) = when (type) {
    "habit_built" -> "A habit you built"
    "habit_unlearned" -> "A habit you let go"
    "accomplishment" -> "Something you did"
    "inner_growth" -> "Inner growth"
    "relationship" -> "A relationship"
    else -> type
}

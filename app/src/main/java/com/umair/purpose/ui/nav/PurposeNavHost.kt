package com.umair.purpose.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.umair.purpose.data.repo.LetterRepository
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.LocalSnackbar
import com.umair.purpose.ui.common.SnackbarController
import com.umair.purpose.ui.common.SnackbarHost
import com.umair.purpose.ui.settings.DeveloperScreen
import com.umair.purpose.ui.settings.ErrorLogScreen
import com.umair.purpose.ui.settings.HealthCheckScreen
import com.umair.purpose.ui.settings.PromptEditorScreen
import com.umair.purpose.ui.settings.PromptListScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.imePadding
import com.umair.purpose.ui.know.KnowScreen
import com.umair.purpose.ui.know.SnapshotScreen
import com.umair.purpose.ui.mirror.LetterScreen
import com.umair.purpose.ui.mirror.MirrorScreen
import com.umair.purpose.ui.onboarding.OnboardingScreen
import com.umair.purpose.ui.path.PathScreen
import com.umair.purpose.ui.settings.SettingsScreen
import com.umair.purpose.ui.settings.TestBenchScreen
import com.umair.purpose.ui.talk.TalkScreen
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TALK("talk", "Talk", PurposeIcons.Talk),
    MIRROR("mirror", "Mirror", PurposeIcons.Mirror),
    PATH("path", "Path", PurposeIcons.Path),
    KNOW("know", "What I know", PurposeIcons.Know),
}

private const val SETTINGS = "settings"
private const val LETTER = "letter/{id}"
private const val ONBOARDING = "onboarding"
private const val SNAPSHOT = "snapshot"
private const val TEST_BENCH = "test_bench"
private const val DEVELOPER = "developer"
private const val HEALTH = "health"
private const val ERROR_LOG = "error_log"
private const val PROMPTS = "prompts"
private const val PROMPT = "prompt/{name}"
/** UPDATE-18: the growth tree, full screen. [grow]: a leaf he just accepted (animated). [sample]: a preview size. */
private const val TREE = "tree?grow={grow}&sample={sample}"
private const val CHAPTERS = "chapters"
private const val CHAPTER = "chapter/{id}"

private fun treeRoute(grow: Long? = null, sample: Int = 0) = "tree?grow=${grow ?: -1}&sample=$sample"

@HiltViewModel
class NavViewModel @Inject constructor(letters: LetterRepository) : ViewModel() {
    val unread: StateFlow<Int> = letters.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

private fun NavHostController.goToTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun PurposeNavHost(vm: NavViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showTabs = current == null || current.route in Tab.entries.map { it.route }
    val unread by vm.unread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarController() }
    val snackbarLift = remember { androidx.compose.runtime.mutableStateOf(0.dp) }

    CompositionLocalProvider(LocalSnackbar provides snackbar.show, com.umair.purpose.ui.common.LocalSnackbarLift provides snackbarLift) {
    Box(Modifier.fillMaxSize()) {
    // Inner screens own their insets (header, keyboard); this Scaffold only places the tab bar.
    Scaffold(
        containerColor = Purpose.colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showTabs) {
                TabBar(
                    selected = Tab.entries.firstOrNull { tab -> current?.hierarchy?.any { it.route == tab.route } == true } ?: Tab.TALK,
                    unreadLetters = unread > 0,
                    onSelect = { nav.goToTab(it.route) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.TALK.route,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Tab.TALK.route) {
                TalkScreen(
                    onOpenSettings = { nav.navigate(SETTINGS) },
                    onOpenPromises = { nav.goToTab(Tab.PATH.route) },
                    onOpenOnboarding = { nav.navigate(ONBOARDING) { launchSingleTop = true } },
                    onOpenTree = { grow -> nav.navigate(treeRoute(grow)) },
                )
            }
            composable(Tab.MIRROR.route) { MirrorScreen(onOpen = { id -> nav.navigate("letter/$id") }, onOpenChapter = { id -> nav.navigate("chapter/$id") }) }
            composable(CHAPTERS) { com.umair.purpose.ui.mirror.ChaptersScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("chapter/$id") }) }
            composable(CHAPTER, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                com.umair.purpose.ui.mirror.ChapterScreen(onBack = { nav.popBackStack() })
            }
            composable(Tab.PATH.route) { PathScreen(onOpenTalk = { nav.goToTab(Tab.TALK.route) }, onOpenTree = { nav.navigate(treeRoute()) }) }
            composable(
                TREE,
                arguments = listOf(
                    navArgument("grow") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("sample") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { com.umair.purpose.ui.growth.GrowthScreen(onBack = { nav.popBackStack() }) }
            composable(Tab.KNOW.route) {
                KnowScreen(
                    onOpenSnapshot = { nav.navigate(SNAPSHOT) },
                    onOpenChapters = { nav.navigate(CHAPTERS) },
                    onOpenOnboarding = { nav.navigate(ONBOARDING) { launchSingleTop = true } },
                    onOpenTalk = { nav.goToTab(Tab.TALK.route) },
                )
            }
            composable(SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDeveloper = { nav.navigate(DEVELOPER) },
                )
            }
            composable(TEST_BENCH) { TestBenchScreen(onBack = { nav.popBackStack() }) }
            composable(DEVELOPER) {
                DeveloperScreen(
                    onBack = { nav.popBackStack() },
                    onOpenTestBench = { nav.navigate(TEST_BENCH) },
                    onOpenHealth = { nav.navigate(HEALTH) },
                    onOpenErrorLog = { nav.navigate(ERROR_LOG) },
                    onOpenPrompts = { nav.navigate(PROMPTS) },
                    onPreviewTree = { n -> nav.navigate(treeRoute(sample = n)) },
                )
            }
            composable(HEALTH) {
                HealthCheckScreen(
                    onBack = { nav.popBackStack() },
                    onOpenErrorLog = { nav.navigate(ERROR_LOG) },
                    onOpenSettings = { nav.popBackStack(SETTINGS, inclusive = false) },
                )
            }
            composable(ERROR_LOG) { ErrorLogScreen(onBack = { nav.popBackStack() }) }
            composable(PROMPTS) { PromptListScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("prompt/" + android.net.Uri.encode(it)) }) }
            composable(PROMPT, arguments = listOf(navArgument("name") { type = NavType.StringType })) {
                PromptEditorScreen(onBack = { nav.popBackStack() }, onOpenTestBench = { nav.navigate(TEST_BENCH) })
            }
            composable(ONBOARDING) { OnboardingScreen(onDone = { nav.popBackStack() }) }
            composable(SNAPSHOT) { SnapshotScreen(onBack = { nav.popBackStack() }) }
            composable(LETTER, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                LetterScreen(
                    onBack = { nav.popBackStack() },
                    onTalk = { nav.popBackStack(); nav.goToTab(Tab.TALK.route) },
                )
            }
        }
    }
    SnackbarHost(
        snackbar,
        Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding()
            .padding(bottom = (if (showTabs) 72.dp else 0.dp) + snackbarLift.value),
    )
    }
    }
}

/** No pill indicator, no tint: the active tab is apricot; a hairline on top. */
@Composable
private fun TabBar(selected: Tab, unreadLetters: Boolean, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Purpose.colors.background).navigationBarsPadding()) {
        Hairline()
        // Grows with a big system font instead of clipping the labels.
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).height(IntrinsicSize.Min)) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                // The active icon and label cross-fade to accent (150ms). No pill, no scaling.
                val color by animateColorAsState(
                    if (on) Purpose.colors.accent else Purpose.colors.textMuted,
                    animationSpec = tween(if (Purpose.reduceMotion) 0 else 150),
                    label = "tab",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Box {
                        Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                        if (tab == Tab.MIRROR && unreadLetters) {
                            Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).size(6.dp).clip(CircleShape).background(Purpose.colors.accent))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, style = Purpose.type.meta, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

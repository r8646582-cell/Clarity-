package com.umair.purpose.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons

/** Screen side padding (DESIGN.md). */
val SidePadding = 24.dp

/** Header: a Newsreader title, optional back arrow, optional actions on the right. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    small: Boolean = false,
    /** An icon left of the title (Talk's ☰). Ignored when [onBack] is set. */
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = if (onBack != null || leading != null) 8.dp else SidePadding, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(PurposeIcons.Back, contentDescription = "Back", tint = Purpose.colors.text) }
        } else {
            leading?.invoke()
        }
        Text(
            title,
            style = if (small) Purpose.type.screenTitle.copy(fontSize = Purpose.type.heading.fontSize, lineHeight = Purpose.type.heading.lineHeight)
            else Purpose.type.screenTitle,
            color = Purpose.colors.text,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

data class MenuAction(val label: String, val enabled: Boolean = true, val note: String? = null, val onClick: () -> Unit)

/** The ⋯ overflow menu. */
@Composable
fun OverflowMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(PurposeIcons.More, contentDescription = "More", tint = Purpose.colors.text) }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Purpose.colors.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(a.label, style = Purpose.type.label, color = if (a.enabled) Purpose.colors.text else Purpose.colors.textMuted)
                            a.note?.let { Text(it, style = Purpose.type.meta, color = Purpose.colors.textMuted) }
                        }
                    },
                    enabled = a.enabled,
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Purpose.colors.hairline))
}

@Composable
fun Meta(text: String, modifier: Modifier = Modifier, color: Color = Purpose.colors.textMuted, align: TextAlign? = null) {
    Text(text, style = Purpose.type.meta, color = color, modifier = modifier, textAlign = align)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Purpose.type.heading, color = Purpose.colors.text, modifier = modifier.padding(top = 32.dp, bottom = 8.dp))
}

/** Fully rounded, apricot. The one primary action on a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (enabled) Purpose.colors.accent else Purpose.colors.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Purpose.type.label, color = if (enabled) Purpose.colors.background else Purpose.colors.textMuted)
    }
}

/** A quiet text button. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Purpose.colors.accent,
    enabled: Boolean = true,
) {
    Text(
        text,
        style = Purpose.type.label,
        color = if (enabled) color else Purpose.colors.textMuted,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** Surface-filled text field with no outline. */
@Composable
fun PurposeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 5,
    minLines: Int = 1,
    style: TextStyle = Purpose.type.userBody,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Keep the leading/trailing icons on the bottom line when the text grows. */
    iconsAtBottom: Boolean = false,
) {
    val c = Purpose.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        textStyle = style.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = keyboardType,
            autoCorrectEnabled = keyboardType == KeyboardType.Text,
        ),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).heightIn(min = 48.dp)
                    .padding(start = if (leading != null) 4.dp else 16.dp, end = if (trailing != null) 4.dp else 16.dp),
                verticalAlignment = if (iconsAtBottom) Alignment.Bottom else Alignment.CenterVertically,
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.textMuted)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Meta(label)
        content()
    }
}

/** Options in a row; the chosen one sits on surface with accent text. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(CircleShape).border(1.dp, Purpose.colors.hairline, CircleShape)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (on) Purpose.colors.surface else Color.Transparent)
                    .clickable { onSelect(value) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Purpose.type.label, color = if (on) Purpose.colors.accent else Purpose.colors.textMuted)
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Purpose.type.label, color = Purpose.colors.text)
            note?.let { Meta(it) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Purpose.colors.background,
                checkedTrackColor = Purpose.colors.accent,
                checkedBorderColor = Purpose.colors.accent,
                uncheckedThumbColor = Purpose.colors.textMuted,
                uncheckedTrackColor = Purpose.colors.surface,
                uncheckedBorderColor = Purpose.colors.hairline,
            ),
        )
    }
}

/** A surface-colored block (radius 16), used for Talk cards. Optional 2dp bar on the left. */
@Composable
fun Block(modifier: Modifier = Modifier, bar: Color? = null, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(16.dp)).background(Purpose.colors.surface)) {
        if (bar != null) Box(Modifier.width(2.dp).fillMaxHeight().background(bar))
        Column(Modifier.weight(1f).padding(16.dp)) { content() }
    }
}

/** Bottom sheet: surface color, 24dp top corners, no tint or shadow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurposeSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Purpose.colors.surface,
        contentColor = Purpose.colors.text,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 32.dp, height = 4.dp).clip(CircleShape).background(Purpose.colors.hairline))
        },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // The sheet's own insets are off, so make room for the keyboard here, and scroll when a big system
        // font makes the content taller than the screen.
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = SidePadding).padding(bottom = 24.dp)
        ) { content() }
    }
}

/** Confirmation for things that can't be undone. Delete is in danger color. */
@Composable
fun ConfirmDialog(
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Purpose.colors.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(16.dp),
        text = { Text(text, style = Purpose.type.itemText, color = Purpose.colors.text) },
        confirmButton = { TextAction(confirm, onConfirm, color = if (danger) Purpose.colors.danger else Purpose.colors.accent) },
        dismissButton = { TextAction("Cancel", onDismiss, color = Purpose.colors.textMuted) },
    )
}

data class EditField(val label: String, val initial: String, val singleLine: Boolean = true)

/** Edit sheet: fields, Save, and Delete (which asks again, because it's final). */
@Composable
fun EditSheet(
    title: String,
    fields: List<EditField>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onDelete: (() -> Unit)? = null,
    canSave: (List<String>) -> Boolean = { it.all(String::isNotBlank) },
    extra: @Composable () -> Unit = {},
) {
    val values = remember { mutableStateListOf(*fields.map { it.initial }.toTypedArray()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    PurposeSheet(onDismiss) {
        Text(title, style = Purpose.type.heading, color = Purpose.colors.text, modifier = Modifier.padding(bottom = 16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEachIndexed { i, f ->
                LabeledField(f.label) {
                    PurposeTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        singleLine = f.singleLine,
                        minLines = if (f.singleLine) 1 else 2,
                        style = Purpose.type.itemText,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            extra()
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) TextAction("Delete", { confirmingDelete = true }, color = Purpose.colors.danger)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Save", { onSave(values.toList()) }, enabled = canSave(values.toList()))
        }
    }
    if (confirmingDelete && onDelete != null) {
        ConfirmDialog(
            text = "Delete this for good? I won't bring it back.",
            confirm = "Delete",
            onConfirm = { confirmingDelete = false; onDelete() },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** A row of small circles: [filled] done (accent), [outlined] index ringed, the rest hairline. */
@Composable
fun DotRow(total: Int, filled: Int, outlined: Int?, modifier: Modifier = Modifier) {
    val c = Purpose.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(if (i < filled) c.accent else Color.Transparent)
                    .border(1.dp, when {
                        i < filled -> c.accent
                        i == outlined -> c.text
                        else -> c.hairline
                    }, CircleShape)
            )
        }
    }
}

/** A 1-5 row of circles for answers (Big Five, pulse). */
@Composable
fun ScaleRow(selected: Int?, onSelect: (Int) -> Unit, left: String, right: String, modifier: Modifier = Modifier) {
    val c = Purpose.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..5).forEach { v ->
                val on = v == selected
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { onSelect(v) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .background(if (on) c.accent else Color.Transparent)
                            .border(1.5.dp, if (on) c.accent else c.textMuted.copy(alpha = 0.6f), CircleShape)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Meta(left, Modifier.weight(1f))
            Meta(right)
        }
    }
}

/**
 * The signature element: a thin mountain skyline (the Hindu Kush from Chitral), full width.
 * Same shape as the app icon's ridge.
 */
@Composable
fun Ridgeline(modifier: Modifier = Modifier, color: Color = Purpose.colors.textMuted.copy(alpha = 0.4f), strokeWidth: Float = 1.5f) {
    Canvas(modifier.fillMaxWidth().aspectRatio(6f)) {
        val sx = size.width / 360f
        val sy = size.height / 60f
        val path = Path()
        RIDGE.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x * sx, y * sy) else path.lineTo(x * sx, y * sy)
        }
        drawPath(path, color, style = Stroke(width = strokeWidth.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private val RIDGE = listOf(
    0f to 52f, 22f to 46f, 38f to 49f, 58f to 36f, 70f to 40f, 92f to 22f, 104f to 30f, 118f to 10f, 128f to 22f,
    140f to 18f, 160f to 34f, 176f to 30f, 196f to 42f, 214f to 33f, 232f to 38f, 250f to 26f, 266f to 34f,
    284f to 44f, 302f to 38f, 322f to 47f, 340f to 44f, 360f to 50f,
)

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Purpose.colors.textMuted, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = description, tint = tint) }
}

/** The ridgeline, small and faint, above an empty state's text, so the empty screen feels intentional. */
@Composable
fun EmptyRidge(modifier: Modifier = Modifier) {
    Ridgeline(modifier.width(140.dp), color = Purpose.colors.textMuted.copy(alpha = 0.3f))
}

/**
 * A light "click" (DESIGN.md Haptics), only for: sending, Just listen, opening "+", marking a promise kept,
 * saving the pulse, and swipe actions on promises. See [com.umair.purpose.system.Haptics].
 */
@Composable
fun rememberClickHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { com.umair.purpose.system.Haptics.click(view) } }
}

/**
 * DESIGN.md "Small feedback": a short snackbar, surface color. 2 seconds; 5 seconds when it carries an action
 * ("Undo"). Screens call `LocalSnackbar.current("Saved")` or `(…)("Promise deleted", "Undo") { … }`.
 */
class ShowSnackbar(private val show: (String, String?, (() -> Unit)?) -> Unit) {
    operator fun invoke(message: String, action: String? = null, onAction: (() -> Unit)? = null) = show(message, action, onAction)
}

val LocalSnackbar = androidx.compose.runtime.staticCompositionLocalOf { ShowSnackbar { _, _, _ -> } }

/**
 * How far above the tab bar the snackbar must sit: Talk sets it to its input area's height, so a snackbar never
 * covers the box he types in (UPDATE-12). Zero everywhere else.
 */
val LocalSnackbarLift = androidx.compose.runtime.staticCompositionLocalOf { mutableStateOf(0.dp) }

class SnackbarController {
    data class Item(val message: String, val action: String?, val onAction: (() -> Unit)?, val id: Long)

    var current by mutableStateOf<Item?>(null)
        private set
    private var next = 0L

    val show = ShowSnackbar { message, action, onAction -> current = Item(message, action, onAction, next++) }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

@Composable
fun SnackbarHost(controller: SnackbarController, modifier: Modifier = Modifier) {
    val item = controller.current ?: return
    androidx.compose.runtime.LaunchedEffect(item.id) {
        kotlinx.coroutines.delay(if (item.action != null) 5_000 else 2_000)
        controller.dismiss(item.id)
    }
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Purpose.colors.surface).padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.message, style = Purpose.type.userBody, color = Purpose.colors.text, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (item.action != null) {
            TextAction(item.action, {
                controller.dismiss(item.id)
                item.onAction?.invoke()
            })
        }
    }
}

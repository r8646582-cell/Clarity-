package com.umair.purpose.ui.talk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umair.purpose.chat.Conversation
import com.umair.purpose.chat.Conversations
import com.umair.purpose.ui.common.ConfirmDialog
import com.umair.purpose.ui.common.EditField
import com.umair.purpose.ui.common.EditSheet
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PurposeSheet
import com.umair.purpose.ui.common.PurposeTextField
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import androidx.paging.compose.itemKey
import java.time.LocalDate
import java.time.ZoneId

/**
 * The chat list (DESIGN.md "Conversations drawer"): New conversation, local search, conversations grouped by
 * date. Long-press a row to rename or forget it. Off-the-record chats never appear (they're never stored).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationsDrawer(
    conversations: androidx.paging.compose.LazyPagingItems<com.umair.purpose.chat.DrawerItem>,
    query: String,
    onQuery: (String) -> Unit,
    openId: Long?,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onForget: (Long) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    var actionsFor by remember { mutableStateOf<Conversation?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var forgetting by remember { mutableStateOf<Conversation?>(null) }

    Row(Modifier.fillMaxHeight().background(Purpose.colors.background)) {
        Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = onNew).padding(horizontal = SidePadding, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PurposeIcons.Plus, contentDescription = null, tint = Purpose.colors.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("New conversation", style = Purpose.type.label, color = Purpose.colors.text)
            }
            PurposeTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search conversations",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            val loading = conversations.loadState.refresh is androidx.paging.LoadState.Loading
            if (conversations.itemCount == 0 && !loading) {
                Meta(if (query.isNotBlank()) "Nothing found." else "Your conversations will be listed here.", Modifier.padding(horizontal = SidePadding, vertical = 16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                // UPDATE-15: a page at a time; the group labels come in between as separators.
                items(count = conversations.itemCount, key = conversations.itemKey { it.key }) { index ->
                    when (val item = conversations[index]) {
                        null -> Unit
                        is com.umair.purpose.chat.DrawerItem.Header ->
                            Text(item.label, style = Purpose.type.label, color = Purpose.colors.textMuted, modifier = Modifier.padding(start = SidePadding, top = 20.dp, bottom = 4.dp))
                        is com.umair.purpose.chat.DrawerItem.Row -> {
                        val c = item.conversation
                        val open = c.session.id == openId
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                                .combinedClickable(onClick = { onOpen(c.session.id) }, onLongClick = { actionsFor = c }),
                        ) {
                            // The open conversation: a 2dp accent bar on the left.
                            Box(Modifier.width(2.dp).fillMaxHeight().background(if (open) Purpose.colors.accent else Purpose.colors.background))
                            Column(Modifier.weight(1f).padding(start = SidePadding - 2.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
                                Text(
                                    Conversations.title(c.session),
                                    style = Purpose.type.itemText,
                                    color = Purpose.colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Meta(Conversations.meta(c, today, zone))
                            }
                        }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        // Hairline right edge, no shadow.
        Box(Modifier.width(1.dp).fillMaxHeight().background(Purpose.colors.hairline))
    }

    actionsFor?.let { c ->
        PurposeSheet(onDismiss = { actionsFor = null }) {
            Text(Conversations.title(c.session), style = Purpose.type.itemText, color = Purpose.colors.text, modifier = Modifier.padding(bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextAction("Rename", { renaming = c; actionsFor = null }, color = Purpose.colors.text)
                TextAction("Forget this conversation", { forgetting = c; actionsFor = null }, color = Purpose.colors.danger)
            }
        }
    }
    renaming?.let { c ->
        EditSheet(
            title = "Rename",
            fields = listOf(EditField("Title", Conversations.title(c.session))),
            onDismiss = { renaming = null },
            onSave = { v -> onRename(c.session.id, v[0]); renaming = null },
        )
    }
    forgetting?.let { c ->
        ConfirmDialog(
            text = "Forget this conversation? It's deleted, with the notes, quotes, moments and promises I learned only from it. Letters, chapters and growth-tree leaves already written from it stay; delete those yourself if you want them gone. This can't be undone.",
            confirm = "Forget",
            onConfirm = { onForget(c.session.id); forgetting = null },
            onDismiss = { forgetting = null },
        )
    }
}

package aismo.sms.ui

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import aismo.sms.container
import aismo.sms.domain.CategoryOrder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import aismo.sms.data.db.ConversationEntity
import aismo.sms.domain.SmsCategory
import aismo.sms.ui.theme.categoryColor

data class InboxActions(
    val openThread: (String) -> Unit,
    val newMessage: () -> Unit,
    val search: () -> Unit,
    val finance: () -> Unit,
    val reminders: () -> Unit,
    val protection: () -> Unit,
    val blocked: () -> Unit,
    val starred: () -> Unit,
    val settings: () -> Unit,
    val about: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(actions: InboxActions) {
    val context = LocalContext.current
    val vm = containerViewModel { InboxViewModel(it) }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val custom by vm.customCategories.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val unreadTotal by vm.unreadTotal.collectAsStateWithLifecycle()
    val customLabels = remember(custom) { custom.associate { it.key to it.name } }
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val unlocked by context.container.security.unlocked.collectAsStateWithLifecycle()
    val barCategories = remember(appSettings?.categoryOrder, custom) {
        val labels = SmsCategory.entries.associate { it.key to it.label } + custom.associate { it.key to it.name }
        CategoryOrder.arrange(appSettings?.categoryOrder.orEmpty(), CategoryOrder.DEFAULT + custom.map { it.key }).map { it to (labels[it] ?: it) }
    }

    var isDefault by remember { mutableStateOf(context.isDefaultSmsApp()) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = context.isDefaultSmsApp()
    }
    LaunchedEffect(Unit) { vm.importIfNeeded() }

    val selection: SnapshotStateList<String> = remember { mutableStateListOf() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var lockedTap by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = { selection.clear() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel selection") } },
                    actions = {
                        IconButton(onClick = { vm.markAllRead(selection.toList()); selection.clear() }) { Icon(Icons.Filled.DoneAll, "Mark read") }
                        IconButton(onClick = { confirmDeleteSelection = true }) { Icon(Icons.Filled.Delete, "Delete") }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Messages")
                            if (unreadTotal > 0) Text("$unreadTotal unread", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = actions.search) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                // Everything the current view shows, so on a category tab it only touches that category.
                                MenuEntry(Icons.Filled.DoneAll, "Mark all as read") {
                                    menuOpen = false
                                    vm.markAllRead(vm.conversations.value.filter { it.unreadCount > 0 }.map { it.key })
                                }
                                HorizontalDivider()
                                MenuEntry(Icons.Filled.AccountBalanceWallet, "Finance") { menuOpen = false; actions.finance() }
                                MenuEntry(Icons.Filled.Alarm, "Reminders") { menuOpen = false; actions.reminders() }
                                MenuEntry(Icons.Filled.GppMaybe, "Spam and scam alerts") { menuOpen = false; actions.protection() }
                                MenuEntry(Icons.Filled.Star, "Starred messages") { menuOpen = false; actions.starred() }
                                MenuEntry(Icons.Filled.Block, "Blocked senders") { menuOpen = false; actions.blocked() }
                                HorizontalDivider()
                                MenuEntry(Icons.Filled.Settings, "Settings") { menuOpen = false; actions.settings() }
                                MenuEntry(Icons.AutoMirrored.Filled.HelpOutline, "Help and about") { menuOpen = false; actions.about() }
                                HorizontalDivider()
                                // Opens in the browser; like everything else in this menu, it only happens when tapped.
                                MenuEntry(Icons.Filled.LocalCafe, "Buy me a coffee") {
                                    menuOpen = false
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/aismo")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = { FloatingActionButton(onClick = actions.newMessage) { Icon(Icons.Filled.Add, "New message") } },
        bottomBar = {
            CategoryBottomBar(
                selected, stats.mapValues { it.value.unread to it.value.conversations }, barCategories,
                appSettings?.securedCategories.orEmpty(), unlocked, vm::select,
                onLockedTap = { lockedTap = it },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            progress?.let {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Organizing your messages… $it", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (!isDefault) {
                DefaultAppBanner {
                    val rm = context.getSystemService(RoleManager::class.java)
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                }
            }
            if (conversations.isEmpty() && progress == null) {
                EmptyState(if (selected == null) "No messages yet" else "Nothing in ${categoryLabel(selected!!, customLabels)}")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.key }) { c ->
                        SwipeableConversation(
                            c = c, customLabels = customLabels, showCategory = selected == null,
                            selected = c.key in selection, selecting = selection.isNotEmpty(),
                            onClick = { if (selection.isNotEmpty()) toggle(selection, c.key) else actions.openThread(c.key) },
                            onLongClick = { toggle(selection, c.key) },
                            onToggleRead = { if (c.unreadCount > 0) vm.markRead(c.key) else vm.markUnread(c.key) },
                            onDelete = { vm.delete(c.key) },
                        )
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    if (confirmDeleteSelection) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelection = false },
            title = { Text("Delete ${selection.size} conversations?") },
            text = { Text("The messages are removed from your phone and can't be recovered.") },
            confirmButton = { TextButton(onClick = { vm.deleteAll(selection.toList()); selection.clear(); confirmDeleteSelection = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteSelection = false }) { Text("Cancel") } },
        )
    }

    // A locked category chip asks for the fingerprint or the PIN before opening; the unlock keeps it open for the chosen window.
    lockedTap?.let { key ->
        SecurityPromptDialog(
            onUnlock = { vm.select(key); lockedTap = null },
            onDismiss = { lockedTap = null },
        )
    }
}

private fun toggle(list: SnapshotStateList<String>, key: String) { if (!list.remove(key)) list.add(key) }

@Composable
private fun MenuEntry(icon: ImageVector, text: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = onClick)

@Composable
private fun DefaultAppBanner(onSetDefault: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Set as your default SMS app to send, receive and block.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSetDefault) { Text("Set") }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The category switcher along the bottom of the inbox: an icon and a short name per category, in the order the user
 * chose. A secured category stays in its place but shows a padlock and no badge while it is locked; tapping it asks
 * for the fingerprint or the PIN before its messages load.
 */
@Composable
private fun CategoryBottomBar(
    selected: String?,
    stats: Map<String, Pair<Int, Int>>,
    categories: List<Pair<String, String>>,
    secured: Set<String>,
    securedUnlocked: Boolean,
    onSelect: (String?) -> Unit,
    onLockedTap: (String) -> Unit,
) {
    // A category with nothing in it is left out of this row; a hidden one keeps its tab (its messages only leave the All view).
    val labels = categories.toMap()
    val withMessages = stats.filterValues { it.second > 0 }.keys
    val shown = CategoryOrder.shown(categories.map { it.first }, withMessages, selected).map { it to (labels[it] ?: it) }
    val listState = rememberLazyListState()
    val selectedIndex = if (selected == null) 0 else 1 + shown.indexOfFirst { it.first == selected }
    // Opened from a notification the chosen category may be off screen: bring it into view so the highlight is visible.
    LaunchedEffect(selected, shown.size) {
        if (selectedIndex >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) listState.animateScrollToItem(selectedIndex)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = Modifier.navigationBarsPadding()) {
        LazyRow(
            Modifier.padding(vertical = 6.dp),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "all") { CategoryTab("All", Icons.Filled.AllInbox, selected == null, 0) { onSelect(null) } }
            items(shown, key = { it.first }) { (key, label) ->
                if (key in secured && !securedUnlocked) {
                    // Locked: a padlock and no badge, nothing that gives the contents away.
                    CategoryTab(label, Icons.Filled.Lock, false, 0) { onLockedTap(key) }
                } else {
                    CategoryTab(label, categoryIcon(key), selected == key, stats[key]?.first ?: 0) { onSelect(key) }
                }
            }
        }
    }
}

@Composable
private fun CategoryTab(label: String, icon: ImageVector, selected: Boolean, unread: Int, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableConversation(
    c: ConversationEntity,
    customLabels: Map<String, String>,
    showCategory: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleRead: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> { onToggleRead(); false }
            SwipeToDismissBoxValue.EndToStart -> { confirmDelete = true; false }
            SwipeToDismissBoxValue.Settled -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = !selecting,
        backgroundContent = {
            val toDelete = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (toDelete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 20.dp),
                contentAlignment = if (toDelete) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(if (toDelete) Icons.Filled.Delete else Icons.Filled.DoneAll, contentDescription = if (toDelete) "Delete" else "Mark read")
            }
        },
    ) {
        ConversationRow(c, customLabels, showCategory, selected, onClick, onLongClick)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("All its messages are removed from your phone and can't be recovered.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: ConversationEntity,
    customLabels: Map<String, String>,
    showCategory: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val title by rememberConversationTitle(c.address, c.title)
    val unread = c.unreadCount > 0
    val color = categoryColor(c.category)
    // The highlight goes on the ListItem's own background: a .background modifier on it would be painted
    // underneath and the selection would not be visible.
    ListItem(
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = { CategoryBadge(c.category, size = 44.dp) },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
        },
        supportingContent = {
            Column {
                Text(
                    (if (c.latestType == 2 || c.latestType == 4) "You: " else "") + c.latestBody.replace('\n', ' '),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                )
                if (showCategory && c.category != SmsCategory.PERSONAL.key) {
                    Text(categoryLabel(c.category, customLabels), style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // MMS arrives as several parts in the thread; this says at a glance that the newest one carries media.
                    if (c.latestAttachmentCount > 0) MmsBadge()
                    Text(formatListTime(c.latestDate), style = MaterialTheme.typography.labelSmall, color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (unread) Badge { Text(c.unreadCount.toString()) }
                if (c.isPinned) Text("📌", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

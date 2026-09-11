package com.bit.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import com.bit.ui.components.BottomBlurScrim
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.vault.ChatInfo
import com.bit.global.formatRelativeTime
import com.bit.state.AppStateManager
import com.bit.ui.components.ActionButton
import com.bit.viewmodel.ChatListViewModel
import kotlinx.coroutines.delay
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDrawerScreen(
    onChatSelected: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onVaultManagerClick: () -> Unit,
    onStoreClick: () -> Unit,
    onWorkspaceClick: () -> Unit = {},
    chatViewModel: com.bit.viewmodel.ChatViewModel,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isDialogOpen by viewModel.isDialogOpen.collectAsStateWithLifecycle()

    val isChatRefreshed by AppStateManager.isChatRefreshed.collectAsStateWithLifecycle()
    val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    LaunchedEffect(isChatRefreshed) {
        if (isChatRefreshed) {
            viewModel.loadChats()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isDialogOpen) Modifier.blur(6.dp) else Modifier
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.bit.R.drawable.ic_logo),
                                contentDescription = "BIT Logo",
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "BIT",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        Surface(
                            onClick = {
                                haptics.action()
                                viewModel.createNewChat { chatId ->
                                    onChatSelected(chatId)
                                }
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                            shadowElevation = 2.dp,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = TnIcons.Plus,
                                    contentDescription = "New Chat",
                                    modifier = Modifier.size(16.dp),
                                    tint = androidx.compose.ui.graphics.Color.White
                                )
                                Text(
                                    text = "New Chat",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(Standards.SpacingSm)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(Standards.SpacingXs))

                // Settings Navigation Item (Pinned at bottom of sidebar)
                Surface(
                    onClick = {
                        haptics.pop()
                        onSettingsClick()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = TnIcons.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = TnIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "BIT · Local Agentic Harness",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Standards.SpacingMd)
        ) {
            Spacer(modifier = Modifier.height(Standards.SpacingXs))

            // ── Primary Navigation Hub (Compact Linear / Notion Style) ──
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Linux PRoot Workspace
                DrawerNavTile(
                    title = "Linux Workspace",
                    icon = TnIcons.Terminal,
                    onClick = {
                        haptics.pop()
                        onWorkspaceClick()
                    }
                )

                // Memory Vault
                DrawerNavTile(
                    title = "Memory Vault",
                    icon = TnIcons.Brain,
                    onClick = {
                        haptics.pop()
                        onVaultManagerClick()
                    }
                )

                // Model Store
                DrawerNavTile(
                    title = "Model Store",
                    icon = TnIcons.StoreFront,
                    onClick = {
                        haptics.pop()
                        onStoreClick()
                    }
                )
            }

            Spacer(modifier = Modifier.height(Standards.SpacingXs))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(Standards.SpacingXs))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    isLoading && chats.isEmpty() -> {
                        LoadingState()
                    }

                    chats.isEmpty() -> {
                        EmptyState()
                    }

                    else -> {
                        ChatList(
                            chats = chats,
                            isRefreshing = isLoading,
                            onRefresh = { viewModel.loadChats() },
                            onChatClick = onChatSelected,
                            onDeleteChat = { chatId ->
                                viewModel.deleteChat(chatId)
                                if (chatId == chatState.currentChatId) {
                                    chatViewModel.startNewConversation()
                                }
                            },
                            onRenameChat = { chatId, newTitle ->
                                viewModel.renameChat(chatId, newTitle)
                            },
                            chatViewModel = chatViewModel
                        )
                    }
                }

                // Polished Pop-up Error Dialog
                error?.let { errorMessage ->
                    com.bit.ui.components.BitErrorDialog(
                        message = errorMessage,
                        title = "Chat Error",
                        onDismiss = { viewModel.clearError() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerNavTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (subtitle != null) 54.dp else 42.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )

                if (subtitle != null) {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Icon(
                imageVector = TnIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

private fun getChatDateGroup(timestamp: Long): String {
    if (timestamp <= 0L) return "Older"
    val now = java.util.Calendar.getInstance()
    val chatTime = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }

    val isSameYear = now.get(java.util.Calendar.YEAR) == chatTime.get(java.util.Calendar.YEAR)
    val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - chatTime.get(java.util.Calendar.DAY_OF_YEAR)

    return if (isSameYear && dayDiff == 0) {
        "Today"
    } else if (isSameYear && dayDiff == 1) {
        "Yesterday"
    } else {
        val diffMs = now.timeInMillis - timestamp
        val diffDays = diffMs / (1000L * 60 * 60 * 24)
        when {
            diffDays <= 7 -> "Previous 7 Days"
            diffDays <= 30 -> "Previous 30 Days"
            else -> "Older"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatList(
    chats: List<ChatInfo>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onChatClick: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    chatViewModel: com.bit.viewmodel.ChatViewModel
) {
    var isManualRefreshing by remember { mutableStateOf(false) }
    val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
    val currentChatId = chatState.currentChatId

    // 1. Drop empty sessions: only show chats with actual messages, or the currently active conversation
    val nonEmptyChats = remember(chats, currentChatId) {
        chats.distinctBy { it.chatId }
            .filter { it.messageCount > 0 || it.chatId == currentChatId }
    }

    // 2. Group chats chronologically (Today / Yesterday / Previous 7 Days / Older)
    val groupedChats = remember(nonEmptyChats) {
        val groups = linkedMapOf<String, MutableList<ChatInfo>>()
        for (chat in nonEmptyChats) {
            val groupKey = getChatDateGroup(chat.lastMessageTime ?: chat.createdAt)
            groups.getOrPut(groupKey) { mutableListOf() }.add(chat)
        }
        groups
    }

    val listState = rememberLazyListState()

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && isManualRefreshing) {
            delay(300)
            isManualRefreshing = false
        }
    }

    if (nonEmptyChats.isEmpty()) {
        EmptyState()
        return
    }

    PullToRefreshBox(
        isRefreshing = isManualRefreshing,
        onRefresh = {
            isManualRefreshing = true
            onRefresh()
        },
        indicator = {
            AnimatedVisibility(isManualRefreshing, modifier = Modifier.align(Alignment.Center)) {
                LoadingIndicator()
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isManualRefreshing) Modifier.blur(24.dp) else Modifier
                    ),
                contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                groupedChats.forEach { (groupTitle, chatsInGroup) ->
                    item(key = "header_$groupTitle") {
                        Text(
                            text = groupTitle,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)
                        )
                    }
                    items(
                        items = chatsInGroup,
                        key = { it.chatId }
                    ) { chat ->
                        val isActive = chat.chatId == currentChatId
                        ChatListItem(
                            chat = chat,
                            isActive = isActive,
                            onClick = { onChatClick(chat.chatId) },
                            onDelete = { onDeleteChat(chat.chatId) },
                            onRename = { newTitle -> onRenameChat(chat.chatId, newTitle) },
                            onFold = { chatViewModel.foldOlderMessages() }
                        )
                    }
                }
            }

            // Down the chat make some blur if there is more chats
            val canScrollDown by remember {
                derivedStateOf { listState.canScrollForward }
            }
            AnimatedVisibility(
                visible = canScrollDown,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(250)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BottomBlurScrim(
                    height = 56.dp,
                    scrimColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    chat: ChatInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onFold: () -> Unit
) {
    var isDeleting by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            delay(5000)
            isDeleting = false
        }
    }

    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(Standards.RadiusSm),
            color = if (isActive) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
            border = if (isActive) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            } else null,
            contentColor = if (isActive) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        haptics.selection()
                        onClick()
                    },
                    onLongClick = {
                        haptics.buildup()
                        showMenu = true
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        text = if (!chat.title.isNullOrBlank()) chat.title else "New Chat",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else if (chat.title.isNullOrBlank() || chat.title == "New Chat") {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isDeleting) {
                    LoadingIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    showMenu = false
                    showRenameDialog = true
                },
                leadingIcon = {
                    Icon(
                        TnIcons.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    showDeleteConfirm = true
                },
                leadingIcon = {
                    Icon(
                        TnIcons.Trash,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }

        if (showRenameDialog) {
            var newTitle by remember { mutableStateOf(chat.title ?: "") }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Chat") },
                text = {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter new chat title") }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newTitle.isNotBlank()) {
                                onRename(newTitle.trim())
                            }
                            showRenameDialog = false
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Chat") },
                text = { Text("Are you sure you want to delete this chat? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            isDeleting = true
                            onDelete()
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            Icon(
                imageVector = TnIcons.Messages,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.secondary
            )

            Text(
                "No chats yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                "Tap + to start a new conversation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            LoadingIndicator()
            Text(
                "Loading chats...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorSnackbar(
    message: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Standards.SpacingLg),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(Standards.RadiusMd)),
            shape = RoundedCornerShape(Standards.RadiusMd),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Standards.SpacingMd),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        TnIcons.X,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

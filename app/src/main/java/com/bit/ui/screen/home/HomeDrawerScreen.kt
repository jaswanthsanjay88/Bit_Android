package com.bit.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.bit.R.drawable.ic_logo),
                                contentDescription = "BIT Logo",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    actions = {
                        TextButton(
                            onClick = {
                                haptics.pop()
                                onStoreClick()
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = TnIcons.StoreFront,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Store",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = {
                                haptics.pop()
                                onSettingsClick()
                            },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Icon(
                                imageVector = TnIcons.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Standards.SpacingMd)
        ) {
            // New Chat button using Material 3 Primary Container
            Card(
                onClick = {
                    haptics.action()
                    viewModel.createNewChat { chatId ->
                        onChatSelected(chatId)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Standards.SpacingSm),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                shape = RoundedCornerShape(Standards.RadiusMd)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TnIcons.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "New Chat",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

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

                error?.let { errorMessage ->
                    ErrorSnackbar(
                        message = errorMessage,
                        onDismiss = { viewModel.clearError() }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Spacer(modifier = Modifier.height(Standards.SpacingSm))
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
    val dedupedChats = remember(chats) { chats.distinctBy { it.chatId } }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && isManualRefreshing) {
            delay(300)
            isManualRefreshing = false
        }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isManualRefreshing) Modifier.blur(24.dp) else Modifier
                ),
            contentPadding = PaddingValues(vertical = Standards.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = dedupedChats,
                key = { it.chatId }
            ) { chat ->
                val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
                val isActive = chat.chatId == chatState.currentChatId
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

    Box {
        Surface(
            shape = RoundedCornerShape(Standards.RadiusMd),
            color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
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
                    .heightIn(min = 52.dp)
                    .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (!chat.title.isNullOrBlank()) chat.title else "New Chat",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
    
                    Row(
                        modifier = Modifier.wrapContentWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
        
                        Text(
                            text = "${chat.messageCount} msgs",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
        
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
        
                        Text(
                            text = formatRelativeTime(chat.lastMessageTime ?: chat.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
    
                if (isDeleting) {
                    LoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            TnIcons.DotsVertical,
                            contentDescription = "Options",
                            tint = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
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

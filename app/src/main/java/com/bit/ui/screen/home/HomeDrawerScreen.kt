package com.bit.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.vault.ChatInfo
import com.bit.global.formatRelativeTime
import com.bit.state.AppStateManager
import com.bit.ui.components.ActionButton
import com.bit.ui.components.GlassCard
import com.bit.ui.theme.Glass
import com.bit.viewmodel.ChatListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bit.ui.icons.TnIcons
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.bit.global.Standards
import com.bit.viewmodel.ChatUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDrawerScreen(
    onChatSelected: (String) -> Unit,
    onVaultManagerClick: () -> Unit,
    chatViewModel: com.bit.viewmodel.ChatViewModel,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isDialogOpen by viewModel.isDialogOpen.collectAsStateWithLifecycle()

    val isChatRefreshed by AppStateManager.isChatRefreshed.collectAsStateWithLifecycle()
    val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()

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
        containerColor = androidx.compose.ui.graphics.Color(0xCC050505),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Chats",
                            style = MaterialTheme.typography.titleLarge,
                            color = Glass.TextPrimary
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Glass.Surface,
                        titleContentColor = Glass.TextPrimary
                    ),
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionButton(
                                onClickListener = {
                                    viewModel.createNewChat { chatId ->
                                        onChatSelected(chatId)
                                    }
                                },
                                icon = TnIcons.Plus,
                                contentDescription = "Create new chat",
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                )
                HorizontalDivider(color = Glass.BorderSubtle, thickness = 1.dp)
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                            // If deleting the currently loaded chat, start a new conversation
                            if (chatId == chatState.currentChatId) {
                                chatViewModel.startNewConversation()
                            }
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
    chatViewModel: com.bit.viewmodel.ChatViewModel
) {
    var isManualRefreshing by remember { mutableStateOf(false) }
    val dedupedChats = remember(chats) { chats.distinctBy { it.chatId } }

    // Reset manual refresh flag when real loading completes
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && isManualRefreshing) {
            delay(300) // Brief visual delay so spinner doesn't vanish instantly
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
            contentPadding = PaddingValues(vertical = Standards.SpacingSm)
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
                    onFold = { chatViewModel.foldOlderMessages() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatListItem(
    chat: ChatInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFold: () -> Unit
) {
    var isDeleting by remember { mutableStateOf(false) }

    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            kotlinx.coroutines.delay(5000)
            isDeleting = false
        }
    }

    GlassCard(
        onClick = onClick,
        cornerRadius = Standards.RadiusMd,
        borderWidth = 0.8.dp,
        backgroundColor = Glass.Surface,
        borderColor = Glass.BorderSubtle,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingXs)
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
                    text = "Chat ${chat.chatId.take(8)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Glass.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary
                )

                Text(
                    text = "${chat.messageCount} msgs",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary
                )

                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary
                )

                Text(
                    text = formatRelativeTime(chat.lastMessageTime ?: chat.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary
                )
            }

            if (isDeleting) {
                LoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Glass.StatusError
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isActive && chat.messageCount >= 6) {
                        IconButton(
                            onClick = onFold,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                TnIcons.Eraser,
                                contentDescription = "Prune chat history",
                                tint = Glass.AccentSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            isDeleting = true
                            onDelete()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            TnIcons.Trash,
                            contentDescription = "Delete chat",
                            tint = Glass.StatusError,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
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
                tint = Glass.AccentSecondary
            )

            Text(
                "No chats yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = Glass.TextPrimary
            )

            Text(
                "Tap + to start a new conversation",
                style = MaterialTheme.typography.bodySmall,
                color = Glass.TextMuted
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
                color = Glass.TextSecondary
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
                .border(1.dp, Glass.StatusError, RoundedCornerShape(Standards.RadiusMd)),
            shape = RoundedCornerShape(Standards.RadiusMd),
            color = Glass.StatusErrorSurface
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
                    color = Glass.StatusError,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        TnIcons.X,
                        contentDescription = "Dismiss",
                        tint = Glass.StatusError,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}


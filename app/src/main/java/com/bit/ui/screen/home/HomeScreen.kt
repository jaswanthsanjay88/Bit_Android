package com.bit.ui.screen.home

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.data.AppSettingsDataStore
import com.bit.models.table_schema.Model
import com.bit.ui.components.LocalCodeHighlightEnabled
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.LLMModelViewModel
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.launch

// ── HomeScreen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    onStoreButtonClicked: () -> Unit,
    onSettingsClick: () -> Unit,
    onVaultManagerClick: () -> Unit,
    onImageGenSetupNeeded: () -> Unit,
    onModelSelectedNavigate: (Model) -> Unit = {},
    chatViewModel: ChatViewModel,
    llmModelViewModel: LLMModelViewModel
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appSettings = remember { AppSettingsDataStore(context) }
    val codeHighlightEnabled by appSettings.codeHighlightEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val toolCallingEnabled by appSettings.toolCallingEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val liquidState = rememberLiquidState()

    // Navigate to QNN setup when a diffusion model needs it
    val needsQnnSetup by llmModelViewModel.needsQnnSetup.collectAsStateWithLifecycle()
    LaunchedEffect(needsQnnSetup) {
        if (needsQnnSetup) onImageGenSetupNeeded()
    }



    CompositionLocalProvider(LocalCodeHighlightEnabled provides codeHighlightEnabled) {
        Box(modifier = Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState, drawerContent = {
                    ModalDrawerSheet(
                        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                        drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        drawerContentColor = MaterialTheme.colorScheme.onSurface,
                        drawerTonalElevation = 0.dp
                    ) {
                        HomeDrawerScreen(
                            onVaultManagerClick = onVaultManagerClick,
                            onChatSelected = {
                                chatViewModel.loadChat(it)
                                scope.launch {
                                    drawerState.close()
                                }
                            },
                            chatViewModel = chatViewModel
                        )
                    }
                }) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopBar(
                            onStoreButtonClicked = onStoreButtonClicked,
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onSettingsClick = onSettingsClick,
                            showDynamicWindow = { chatViewModel.showDynamicWindow() },
                            liquidState = liquidState
                        )
                    },
                    bottomBar = {
                        BottomBar(
                            chatViewModel = chatViewModel,
                            llmModelViewModel = llmModelViewModel,
                            toolCallingEnabled = toolCallingEnabled,
                            onModelSelectedNavigate = onModelSelectedNavigate,
                            liquidState = liquidState
                        )
                    }) { paddingValues ->
                    BodyContent(
                        paddingValues = paddingValues,
                        chatViewModel = chatViewModel,
                        llmModelViewModel = llmModelViewModel,
                        liquidState = liquidState,
                        onModelSelectedNavigate = onModelSelectedNavigate
                    )
                }
            }

            // Floating TTS Player Capsule
            val ttsIsPlaying by chatViewModel.ttsIsPlaying.collectAsStateWithLifecycle()
            val ttsSynthesizing by chatViewModel.ttsSynthesizing.collectAsStateWithLifecycle()

            AnimatedVisibility(
                visible = ttsIsPlaying || ttsSynthesizing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
            ) {
                FloatingTtsPlayer(
                    isPlaying = ttsIsPlaying,
                    isSynthesizing = ttsSynthesizing,
                    onPlayPauseToggle = {
                        // Toggling while playing stops it
                        chatViewModel.stopTTS()
                    },
                    onClose = {
                        chatViewModel.stopTTS()
                    }
                )
            }
        }
    } // CompositionLocalProvider
}

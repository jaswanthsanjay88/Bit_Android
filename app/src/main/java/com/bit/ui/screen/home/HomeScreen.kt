package com.bit.ui.screen.home

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.data.AppSettingsDataStore
import com.bit.models.table_schema.Model
import com.bit.ui.components.LocalCodeHighlightEnabled
import com.bit.ui.components.rememberRevealDrawerState
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.LLMModelViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.launch

// ── HomeScreen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onStoreButtonClicked: (String?) -> Unit,
    onSettingsClick: () -> Unit,
    onVaultManagerClick: () -> Unit,
    onImageGenSetupNeeded: () -> Unit,
    onModelSelectedNavigate: (Model) -> Unit = {},
    chatViewModel: ChatViewModel,
    llmModelViewModel: LLMModelViewModel
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appSettings = remember { AppSettingsDataStore(context) }
    val codeHighlightEnabled by appSettings.codeHighlightEnabled
         .collectAsStateWithLifecycle(initialValue = true)
    val toolCallingEnabled by appSettings.toolCallingEnabled
         .collectAsStateWithLifecycle(initialValue = true)
    val liquidState = rememberLiquidState()
    val hazeState = rememberHazeState()

    val density = LocalDensity.current
    val drawerWidth = 280.dp
    val maxOffsetPx = with(density) { drawerWidth.toPx() }
    val drawerState = rememberRevealDrawerState(maxOffsetPx = maxOffsetPx, coroutineScope = scope)

    CompositionLocalProvider(LocalCodeHighlightEnabled provides codeHighlightEnabled) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ── LAYER 1: Navigation Drawer Sidebar (Parallax Reveal, Scales & Fades) ──
            Box(
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val currentProgress = (drawerState.offsetX.value / maxOffsetPx).coerceIn(0f, 1f)
                        // Slide-in parallax effect (emerges from -60dp to 0dp)
                        translationX = (currentProgress - 1f) * 60.dp.toPx()
                        // 3D scaling depth effect (grows from 0.93f to 1f)
                        scaleX = 0.93f + (currentProgress * 0.07f)
                        scaleY = 0.93f + (currentProgress * 0.07f)
                        // Fade in as revealed
                        alpha = 0.4f + (currentProgress * 0.6f)
                    }
            ) {
                HomeDrawerScreen(
                    onVaultManagerClick = onVaultManagerClick,
                    onSettingsClick = onSettingsClick,
                    onChatSelected = {
                        chatViewModel.loadChat(it)
                        drawerState.close()
                    },
                    onStoreClick = {
                        drawerState.close()
                        onStoreButtonClicked("models")
                    },
                    chatViewModel = chatViewModel
                )

                // Darkening Scrim for Sidebar based on slide progress (fades to black as closed)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val currentProgress = (drawerState.offsetX.value / maxOffsetPx).coerceIn(0f, 1f)
                            alpha = (1f - currentProgress) * 0.6f
                        }
                        .background(Color.Black)
                )
            }

            // ── LAYER 2: Main Content Container (Slides, Scales & Snaps) ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val currentProgress = (drawerState.offsetX.value / maxOffsetPx).coerceIn(0f, 1f)
                        translationX = drawerState.offsetX.value
                        scaleX = 1f - (currentProgress * 0.15f)
                        scaleY = 1f - (currentProgress * 0.15f)
                        shape = RoundedCornerShape((currentProgress * 24f).dp)
                        clip = true
                        shadowElevation = 16f
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    drawerState.offsetX.snapTo(
                                        (drawerState.offsetX.value + dragAmount).coerceIn(0f, maxOffsetPx)
                                    )
                                }
                            },
                            onDragEnd = {
                                // Direct read of drawerState.offsetX.value avoids stale composition lambda captures
                                val finalProgress = (drawerState.offsetX.value / maxOffsetPx).coerceIn(0f, 1f)
                                if (finalProgress > 0.3f) {
                                    drawerState.open()
                                } else {
                                    drawerState.close()
                                }
                            }
                        )
                    }
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        BottomBar(
                            chatViewModel = chatViewModel,
                            llmModelViewModel = llmModelViewModel,
                            toolCallingEnabled = toolCallingEnabled,
                            onModelSelectedNavigate = onModelSelectedNavigate,
                            liquidState = liquidState,
                            hazeState = hazeState
                        )
                    }) { paddingValues ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        BodyContent(
                            paddingValues = paddingValues,
                            chatViewModel = chatViewModel,
                            llmModelViewModel = llmModelViewModel,
                            liquidState = liquidState,
                            hazeState = hazeState,
                            onModelSelectedNavigate = onModelSelectedNavigate
                        )

                        // Top Blur Scrim: dynamic progressive vertical blur zone
                        com.bit.ui.components.TopBlurScrim(
                            hazeState = hazeState,
                            modifier = Modifier.align(Alignment.TopCenter),
                            height = 100.dp + with(LocalDensity.current) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            },
                            blurRadius = 26.dp,
                            tintColor = Color.Black,
                            tintAlpha = 0.55f
                        )

                        // Top bar floats on top
                        TopBar(
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onStoreButtonClicked = onStoreButtonClicked,
                            onMenuClick = { drawerState.toggle() },
                            onSettingsClick = onSettingsClick,
                            showDynamicWindow = { chatViewModel.showDynamicWindow() }
                        )
                    }
                }

                // Scrim overlay to intercept input and close when drawer is open
                if (drawerState.isOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                drawerState.close()
                            }
                    )
                }
            }

            // Floating TTS Player removed by user request
        }
    } // CompositionLocalProvider
}

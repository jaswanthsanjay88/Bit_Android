package com.bit.ui.screen.home

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bit.activity.ModelPickerActivity
import com.bit.global.Standards
import com.bit.ui.components.ActionButton
import com.bit.ui.components.AnimatedTitle
import com.bit.ui.icons.TnIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope

// ── TopBar ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun TopBar(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showDynamicWindow: () -> Unit,
    onStoreButtonClicked: (String?) -> Unit,
    hazeState: HazeState? = null
) {
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val background = MaterialTheme.colorScheme.background

    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
        title = {
            with(sharedTransitionScope) {
                androidx.compose.material3.Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(32.dp),
                    modifier = Modifier
                        .sharedBounds(
                            sharedTransitionScope.rememberSharedContentState(key = "chat_header"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                        .then(
                            if (hazeState != null) {
                                Modifier.hazeEffect(state = hazeState) {
                                    style = dev.chrisbanes.haze.HazeStyle(
                                        backgroundColor = surfaceContainer,
                                        tint = dev.chrisbanes.haze.HazeTint(Color.Black.copy(alpha = 0.45f)),
                                        blurRadius = 20.dp,
                                        noiseFactor = 0.05f
                                    )
                                }
                            } else Modifier
                        )
                        .border(
                            width = 0.5.dp,
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(32.dp)
                        )
                ) {
                    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                        AnimatedTitle(
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onShowDynamicWindow = { showDynamicWindow() }
                        )
                    }
                }
            }
        },
        navigationIcon = {
            androidx.compose.material3.IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = TnIcons.Menu,
                    contentDescription = "Menu",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        modifier = Modifier
            .statusBarsPadding()
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        style = dev.chrisbanes.haze.HazeStyle(
                            backgroundColor = background,
                            tint = dev.chrisbanes.haze.HazeTint(Color.Black.copy(alpha = 0.25f)),
                            blurRadius = 24.dp,
                            noiseFactor = 0.05f
                        )
                        progressive = dev.chrisbanes.haze.HazeProgressive.verticalGradient(
                            androidx.compose.animation.core.EaseIn, 1f, 0f
                        )
                    }
                } else Modifier
            )
    )
}

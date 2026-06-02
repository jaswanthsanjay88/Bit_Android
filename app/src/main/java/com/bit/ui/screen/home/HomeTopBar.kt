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
import com.bit.ui.theme.Glass
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.material3.VerticalDivider

// ── TopBar ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopBar(
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showDynamicWindow: () -> Unit,
    onStoreButtonClicked: () -> Unit,
    liquidState: LiquidState? = null
) {
    val context = LocalContext.current

    CenterAlignedTopAppBar(
        modifier = Modifier.then(if (liquidState != null) Modifier.liquid(liquidState) else Modifier),
        title = {
            AnimatedTitle(
                modifier = Modifier,
                onShowDynamicWindow = { showDynamicWindow() }
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        navigationIcon = {
            ActionButton(
                onClickListener = onMenuClick,
                icon = TnIcons.Menu,
                contentDescription = "Open navigation menu",
                modifier = Modifier.padding(start = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Glass.Surface,
                    contentColor = Glass.TextPrimary
                )
            )
        },
        actions = {
            Row(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x1CFFFFFF), RoundedCornerShape(16.dp)) // 11% white glass
                    .border(0.8.dp, Color(0x15FFFFFF), RoundedCornerShape(16.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Action 1: Model Store (Download)
                ActionButton(
                    onClickListener = { onStoreButtonClicked() },
                    icon = TnIcons.Download,
                    contentDescription = "Open model store",
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Glass.TextPrimary
                    )
                )

                // Vertical Divider between actions
                VerticalDivider(
                    modifier = Modifier.height(16.dp),
                    thickness = 0.8.dp,
                    color = Color(0x22FFFFFF)
                )

                // Action 2: Model Picker (Upload)
                ActionButton(
                    onClickListener = {
                        context.startActivity(Intent(context, ModelPickerActivity::class.java))
                    },
                    icon = TnIcons.Upload,
                    contentDescription = "Open local model picker",
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Glass.TextPrimary
                    )
                )

                // Vertical Divider between actions
                VerticalDivider(
                    modifier = Modifier.height(16.dp),
                    thickness = 0.8.dp,
                    color = Color(0x22FFFFFF)
                )

                // Action 3: Settings
                ActionButton(
                    onClickListener = onSettingsClick,
                    icon = TnIcons.Settings,
                    contentDescription = "Open settings",
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Glass.TextPrimary
                    )
                )
            }
        }
    )
}

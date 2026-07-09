package com.bit.ui.screen.guide

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.ui.theme.BitColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onContinue: () -> Unit
) {
    val totalSlides = 3
    val pagerState = rememberPagerState(pageCount = { totalSlides })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            // ── Top Header Brand with status bar padding ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(vertical = 12.dp)
            ) {
                with(sharedTransitionScope) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                            .sharedElement(
                                sharedTransitionScope.rememberSharedContentState(key = "bit_mark"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                    ) {
                        com.bit.ui.screen.intro.AnimatedLogo(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Skip button top-right (only if not on the last slide) with 48dp target padding
                if (pagerState.currentPage < totalSlides - 1) {
                    TextButton(
                        onClick = onContinue,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .minimumInteractiveComponentSize()
                    ) {
                        Text(
                            text = "Skip",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.W500,
                                letterSpacing = 0.08.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Spacer to balance the top header
                Spacer(modifier = Modifier.height(72.dp))

                // ── Hero Visual (Fixed, morphing on page change) ──
                Box(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = pagerState.currentPage,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(500, easing = EaseOutCubic)) +
                                    scaleIn(initialScale = 0.85f, animationSpec = tween(500, easing = EaseOutBack))) togetherWith
                                    (fadeOut(animationSpec = tween(250, easing = EaseInCubic)) +
                                            scaleOut(targetScale = 0.85f, animationSpec = tween(250, easing = EaseInCubic)))
                        },
                        label = "heroMorph"
                    ) { targetPage ->
                        OnboardingHero(
                            slideIndex = targetPage,
                            modifier = Modifier.size(240.dp)
                        )
                    }
                }

                // ── Pager text content ──
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxWidth()
                ) { page ->
                    val slide = getOnboardingSlide(page)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = slide.title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = slide.description,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // ── Bottom Action Row ──
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Page Indicator: 3 dashes with dynamic animated width and colors
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(totalSlides) { index ->
                            val isActive = index == pagerState.currentPage
                            val width by animateDpAsState(
                                targetValue = if (isActive) 24.dp else 8.dp,
                                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                                label = "indicatorWidth"
                            )
                            val color by animateColorAsState(
                                targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                animationSpec = tween(durationMillis = 300),
                                label = "indicatorColor"
                            )
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(width)
                                    .background(
                                        color = color,
                                        shape = RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }

                    // Primary CTA Button with spring press scale haptic-like effect
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.96f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "buttonScale"
                    )

                    Button(
                        onClick = {
                            if (pagerState.currentPage < totalSlides - 1) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            } else {
                                onContinue()
                            }
                        },
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .graphicsLayer {
                                scaleX = buttonScale
                                scaleY = buttonScale
                            }
                    ) {
                        Text(
                            text = if (pagerState.currentPage == totalSlides - 1) "Get Started" else "Continue",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

private data class OnboardingSlide(
    val title: String,
    val description: String
)

private fun getOnboardingSlide(index: Int): OnboardingSlide = when (index) {
    0 -> OnboardingSlide(
        title = "Runs fully offline",
        description = "Local AI models run entirely on your phone. No cloud, no trackers, and zero subscriptions."
    )
    1 -> OnboardingSlide(
        title = "Private by design",
        description = "Everything stays encrypted. Your chats, documents, and memories never leave your device."
    )
    else -> OnboardingSlide(
        title = "Tailored to you",
        description = "Select the optimal offline model configured for your device memory and performance."
    )
}

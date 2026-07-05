package com.bit.ui.screen.guide

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BitColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        // ── Top Header Brand ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            with(sharedTransitionScope) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.bit.R.drawable.ic_logo),
                    contentDescription = "BIT Logo",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .sharedElement(
                            sharedTransitionScope.rememberSharedContentState(key = "bit_mark"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                )
            }

            // Skip button top-right (only if not on the last slide)
            if (pagerState.currentPage < totalSlides - 1) {
                TextButton(
                    onClick = onContinue,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.W500,
                            letterSpacing = 0.08.sp
                        ),
                        color = BitColors.TextTertiary
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
            Spacer(modifier = Modifier.height(64.dp))

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
                        (fadeIn(animationSpec = tween(350)) + scaleIn(initialScale = 0.9f, animationSpec = tween(350))) togetherWith
                                (fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f, animationSpec = tween(200)))
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
                        style = androidx.compose.ui.text.TextStyle(
                            color = BitColors.TextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 36.sp,
                            letterSpacing = (-0.5).sp,
                            textAlign = TextAlign.Center
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = slide.description,
                        style = androidx.compose.ui.text.TextStyle(
                            color = BitColors.TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 22.sp,
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
                // Page Indicator: 3 dashes
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(totalSlides) { index ->
                        val isActive = index == pagerState.currentPage
                        val width = if (isActive) 24.dp else 8.dp
                        val color = if (isActive) BitColors.TextPrimary else BitColors.Border
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

                // Primary CTA Button
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitColors.Inverse,
                        contentColor = BitColors.OnInverse
                    )
                ) {
                    Text(
                        text = if (pagerState.currentPage == totalSlides - 1) "Get Started" else "Continue",
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
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

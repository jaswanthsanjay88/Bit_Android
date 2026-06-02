package com.bit.ui.screen.guide

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GuideScreen(
    onContinue: () -> Unit
) {
    var currentSlide by remember { mutableStateOf(0) }
    val totalSlides = 5

    // Dynamic background orbs color based on slide index for beautiful premium visual shift
    val accentColor = when (currentSlide) {
        0 -> MaterialTheme.colorScheme.primary // Violet / Indigo
        1 -> MaterialTheme.colorScheme.tertiary // Teal
        2 -> MaterialTheme.colorScheme.error // Orange-Rose
        3 -> MaterialTheme.colorScheme.secondary // Emerald-Green
        else -> MaterialTheme.colorScheme.primary // Cyan
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                )
            )
    ) {
        // Decorative glowing ambient mesh (glowing orb behind container card)
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.Center)
                .offset(y = (-40).dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Standards.SpacingLg)
                .padding(bottom = Standards.SpacingLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top Header Brand ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Standards.SpacingLg),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TnIcons.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = accentColor
                )
                Spacer(Modifier.width(Standards.SpacingXs))
                Text(
                    text = "BIT",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // ── Animated Slide Content ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentSlide,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(initialOffsetX = { it }) + fadeIn()) togetherWith
                                    (slideOutHorizontally(targetOffsetX = { -it }) + fadeOut())
                        } else {
                            (slideInHorizontally(initialOffsetX = { -it }) + fadeIn()) togetherWith
                                    (slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
                        }
                    },
                    label = "onboardingSlideTransition"
                ) { slideIndex ->
                    val slide = getOnboardingSlide(slideIndex)
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Standards.SpacingSm),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                        tonalElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingXl),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Icon Container
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .background(
                                        color = accentColor.copy(alpha = 0.12f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = slide.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = accentColor
                                )
                            }

                            Spacer(Modifier.height(Standards.SpacingXl))

                            // Slide Title
                            Text(
                                text = slide.title,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 32.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(Standards.SpacingLg))

                            // Slide Description
                            Text(
                                text = slide.description,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 26.sp,
                                    letterSpacing = 0.2.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = Standards.SpacingXs)
                            )

                            if (slideIndex == 4) {
                                Spacer(modifier = Modifier.height(Standards.SpacingLg))
                                val context = androidx.compose.ui.platform.LocalContext.current
                                val ramGb = remember {
                                    try {
                                        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                        val memoryInfo = android.app.ActivityManager.MemoryInfo()
                                        activityManager.getMemoryInfo(memoryInfo)
                                        memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                                    } catch (e: Exception) { 8.0 }
                                }
                                val (modelName, sizeStr, ramStr) = when {
                                    ramGb < 4.0 -> Triple("Qwen 2.5 0.5B (Q4_K_M)", "~0.39 GB", "Fits under 4GB RAM")
                                    ramGb < 8.0 -> Triple("Llama 3.2 3B (Q4_K_M)", "~2.02 GB", "Optimized for 4-6GB RAM")
                                    ramGb < 12.0 -> Triple("Llama 3 8B (Q4_K_M)", "~4.78 GB", "Recommended for 8-12GB RAM")
                                    else -> Triple("Qwen 2.5 14B (Q4_K_M)", "~9.05 GB", "Best for 12GB+ premium RAM")
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = Standards.SpacingXs),
                                    shape = RoundedCornerShape(12.dp),
                                    color = accentColor.copy(alpha = 0.1f),
                                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(Standards.SpacingMd),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "⚡ PREFERRED LLM SUGGESTION",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = accentColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = modelName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Size: $sizeStr  ·  $ramStr",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Bottom Navigation Controls ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Standards.SpacingMd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingLg)
            ) {
                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(totalSlides) { index ->
                        val isActive = index == currentSlide
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (isActive) 18.dp else 6.dp)
                                .background(
                                    color = if (isActive) accentColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                        )
                    }
                }

                // Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back Button
                    if (currentSlide > 0) {
                        TextButton(
                            onClick = { currentSlide-- },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(
                                text = "Back",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Spacer to maintain alignment when Back is hidden
                        Spacer(modifier = Modifier.width(48.dp))
                    }

                    // Next / Start Button
                    if (currentSlide < totalSlides - 1) {
                        FilledTonalButton(
                            onClick = { currentSlide++ },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Next",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    } else {
                        Button(
                            onClick = onContinue,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = Standards.SpacingLg)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = "Get Started",
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
}

private data class OnboardingSlide(
    val icon: ImageVector,
    val title: String,
    val description: String
)

private fun getOnboardingSlide(index: Int): OnboardingSlide = when (index) {
    0 -> OnboardingSlide(
        icon = TnIcons.Sparkles,
        title = "Privacy-First Local AI",
        description = "BIT runs advanced Artificial Intelligence models entirely on your phone. No cloud servers, no trackers, and zero subscriptions. Your data never leaves your device."
    )
    1 -> OnboardingSlide(
        icon = TnIcons.Brain,
        title = "Offline Text Models",
        description = "Load any open GGUF model—such as Llama, Qwen, or Phi. Stream responsive text generation for creative writing, planning, coding, or quick Q&As completely offline."
    )
    2 -> OnboardingSlide(
        icon = TnIcons.Palette,
        title = "Image & Doc Intelligence",
        description = "Generate high-quality images locally via Stable Diffusion. Securely ingest PDFs and files to execute offline, private document intelligence using hybrid vector search (RAG)."
    )
    3 -> OnboardingSlide(
        icon = TnIcons.ShieldLock,
        title = "Hardware-Backed Security",
        description = "Every conversation, custom persona card, and AI memory is stored with military-grade AES-256-GCM native encryption backed by the Android KeyStore."
    )
    else -> OnboardingSlide(
        icon = TnIcons.Bolt,
        title = "Optimized for Your Device",
        description = "We analyzed your device hardware memory. Based on your available system RAM, we have selected the perfect offline LLM model for your phone to ensure smooth performance."
    )
}

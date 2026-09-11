package com.bit.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.ui.icons.TnIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native Android PDF Viewer composable.
 *
 * Uses [PdfRenderer] to rasterize high-fidelity PDF pages directly from disk.
 * Supports:
 * - Lazy vertical scrolling of multi-page documents
 * - Crisp device-DPI bitmap rasterization
 * - In-memory LRU page cache for fluid 120Hz scrolling
 * - Pinch-to-zoom & double-tap to reset
 * - Floating "Page X of Y" counter
 */
@Composable
fun PdfPageViewer(
    file: File,
    modifier: Modifier = Modifier
) {
    var pfd by remember(file) { mutableStateOf<ParcelFileDescriptor?>(null) }
    var renderer by remember(file) { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember(file) { mutableStateOf(0) }
    var loadError by remember(file) { mutableStateOf<String?>(null) }

    // Synchronize access to PdfRenderer because it is strictly single-threaded
    val rendererLock = remember(file) { Any() }

    // Page bitmap LRU cache (caches up to 12 rendered pages in memory)
    val pageBitmapCache = remember(file) {
        object : LruCache<Int, Bitmap>(12) {
            override fun entryRemoved(evicted: Boolean, key: Int?, oldValue: Bitmap?, newValue: Bitmap?) {
                // Keep entries around for Compose draw without immediate recycle
            }
        }
    }

    // Initialize ParcelFileDescriptor and PdfRenderer
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists() || file.length() == 0L) {
                    loadError = "PDF file does not exist or is empty."
                    return@withContext
                }
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfRenderer = PdfRenderer(descriptor)
                pfd = descriptor
                renderer = pdfRenderer
                pageCount = pdfRenderer.pageCount
                loadError = null
            } catch (e: Exception) {
                loadError = "Unable to open PDF: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    DisposableEffect(file) {
        onDispose {
            try {
                renderer?.close()
                pfd?.close()
                pageBitmapCache.evictAll()
            } catch (_: Exception) {}
        }
    }

    if (loadError != null) {
        PdfErrorCard(errorMessage = loadError ?: "Failed to load PDF", modifier = modifier)
        return
    }

    if (renderer == null || pageCount <= 0) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = "Rendering PDF pages...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val lazyListState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    // Pinch-to-zoom & pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 3.5f)
        if (scale > 1f) {
            val maxOffsetX = (scale - 1f) * screenWidthPx / 2f
            val newX = (offset.x + offsetChange.x * scale).coerceIn(-maxOffsetX, maxOffsetX)
            val newY = offset.y + offsetChange.y * scale
            offset = Offset(newX, newY)
        } else {
            offset = Offset.Zero
        }
    }

    val currentPage by remember {
        derivedStateOf {
            (lazyListState.firstVisibleItemIndex + 1).coerceAtMost(pageCount)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .transformable(state = transformableState)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                            }
                        }
                    )
                }
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(pageCount, key = { it }) { index ->
                    PdfPageItem(
                        renderer = renderer!!,
                        rendererLock = rendererLock,
                        pageIndex = index,
                        screenWidthPx = screenWidthPx,
                        bitmapCache = pageBitmapCache
                    )
                }
            }
        }

        // Floating Page Counter & Zoom Reset Pill
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .shadow(8.dp, CircleShape),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    TnIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "$currentPage / $pageCount",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (scale > 1.05f) {
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            TnIcons.Restore,
                            contentDescription = "Reset Zoom",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    renderer: PdfRenderer,
    rendererLock: Any,
    pageIndex: Int,
    screenWidthPx: Int,
    bitmapCache: LruCache<Int, Bitmap>
) {
    var pageBitmap by remember(pageIndex) { mutableStateOf(bitmapCache.get(pageIndex)) }
    var pageAspectRatio by remember(pageIndex) { mutableFloatStateOf(1f / 1.414f) } // Default A4 ratio

    LaunchedEffect(pageIndex, renderer) {
        if (pageBitmap != null) return@LaunchedEffect

        withContext(Dispatchers.Default) {
            val bmp = synchronized(rendererLock) {
                try {
                    val page = renderer.openPage(pageIndex)
                    val pw = page.width
                    val ph = page.height
                    pageAspectRatio = pw.toFloat() / ph.toFloat().coerceAtLeast(1f)

                    // Target crisp rasterization width (up to screen width, clamped safely)
                    val targetW = screenWidthPx.coerceIn(400, 2160)
                    val scale = targetW.toFloat() / pw.toFloat()
                    val targetH = (ph * scale).toInt().coerceIn(400, 4320)

                    val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap
                } catch (e: Exception) {
                    null
                }
            }

            if (bmp != null) {
                bitmapCache.put(pageIndex, bmp)
                pageBitmap = bmp
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(pageAspectRatio)
            .shadow(4.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (pageBitmap != null) {
            Image(
                bitmap = pageBitmap!!.asImageBitmap(),
                contentDescription = "PDF Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Page ${pageIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun PdfErrorCard(
    errorMessage: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    TnIcons.AlertTriangle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "PDF Preview Unavailable",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "You can still view and edit the extracted document content in Edit mode.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

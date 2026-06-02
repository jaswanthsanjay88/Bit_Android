package com.bit.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import com.bit.plugins.EnhancedWebSearchPipelineResult
import com.bit.plugins.EnhancedWebSearchResult
import com.bit.plugins.WebSearchError
import com.bit.ui.icons.TnIcons

@Composable
fun WebSearchResultsDisplay(
    result: EnhancedWebSearchPipelineResult,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(Standards.RadiusLg)
            )
            .padding(Standards.SpacingMd)
    ) {
        // Header with status
        WebSearchResultsHeader(result)
        
        Spacer(modifier = Modifier.height(Standards.SpacingMd))
        
        // Results or error state
        when {
            result.results.isEmpty() && result.status == EnhancedWebSearchPipelineResult.SearchStatus.NO_RESULTS -> {
                WebSearchNoResults(result.query)
            }
            result.error != null && result.results.isEmpty() -> {
                WebSearchErrorState(result.status, result.error)
            }
            result.results.isNotEmpty() -> {
                WebSearchResultsList(
                    results = result.results,
                    onUrlClick = { url -> uriHandler.openUri(url) }
                )
            }
        }
        
        // Footer with metrics
        WebSearchResultsFooter(result)
    }
}

@Composable
private fun WebSearchResultsHeader(result: EnhancedWebSearchPipelineResult) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Web Search Results",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "\"${result.query}\"",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            StatusBadge(status = result.status)
        }
        
        Spacer(modifier = Modifier.height(Standards.SpacingMd))
        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
    }
}

@Composable
private fun StatusBadge(status: EnhancedWebSearchPipelineResult.SearchStatus) {
    val (backgroundColor, contentColor, label) = when (status) {
        EnhancedWebSearchPipelineResult.SearchStatus.SUCCESS -> {
            Triple(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                "✓ Success"
            )
        }
        EnhancedWebSearchPipelineResult.SearchStatus.PARTIAL_SUCCESS -> {
            Triple(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
                "⚠️ Partial"
            )
        }
        EnhancedWebSearchPipelineResult.SearchStatus.NO_RESULTS -> {
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                "No Results"
            )
        }
        EnhancedWebSearchPipelineResult.SearchStatus.RATE_LIMITED -> {
            Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                "⏱️ Rate Limit"
            )
        }
        EnhancedWebSearchPipelineResult.SearchStatus.BLOCKED -> {
            Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                "🚫 Blocked"
            )
        }
        EnhancedWebSearchPipelineResult.SearchStatus.NETWORK_ERROR -> {
            Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                "📡 Error"
            )
        }
        EnhancedWebSearchPipelineResult.SearchStatus.TIMEOUT -> {
            Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                "⏰ Timeout"
            )
        }
        else -> {
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                "Error"
            )
        }
    }
    
    Surface(
        color = backgroundColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun WebSearchNoResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Standards.SpacingLg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = TnIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(Standards.SpacingMd))
        
        Text(
            text = "No results found",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        
        Text(
            text = "Try a different search query",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WebSearchErrorState(
    status: EnhancedWebSearchPipelineResult.SearchStatus,
    error: WebSearchError?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                shape = RoundedCornerShape(Standards.RadiusMd)
            )
            .padding(Standards.SpacingMd)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = TnIcons.AlertCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Search Failed",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                
                Text(
                    text = when (status) {
                        EnhancedWebSearchPipelineResult.SearchStatus.RATE_LIMITED -> 
                            "Search provider rate limited. Try again in a few moments."
                        EnhancedWebSearchPipelineResult.SearchStatus.BLOCKED -> 
                            "Search provider blocked the request (CAPTCHA/unusual traffic)."
                        EnhancedWebSearchPipelineResult.SearchStatus.NETWORK_ERROR -> 
                            "Network error. Check your connection and try again."
                        EnhancedWebSearchPipelineResult.SearchStatus.TIMEOUT -> 
                            "Search request timed out. Try a simpler query."
                        EnhancedWebSearchPipelineResult.SearchStatus.INVALID_QUERY -> 
                            "Invalid search query. Try different keywords."
                        else -> error?.message ?: "An unknown error occurred."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun WebSearchResultsList(
    results: List<EnhancedWebSearchResult>,
    onUrlClick: (String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
    ) {
        items(results, key = { it.url }) { result ->
            WebSearchResultCard(
                result = result,
                onUrlClick = { onUrlClick(result.url) }
            )
        }
    }
}

@Composable
private fun WebSearchResultCard(
    result: EnhancedWebSearchResult,
    onUrlClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Standards.RadiusMd)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd)
        ) {
            // Title and source
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Text(
                        text = result.metadata.domain,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                
                IconButton(
                    onClick = onUrlClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = TnIcons.ExternalLink,
                        contentDescription = "Open in browser",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(Standards.SpacingSm))
            
            // Snippet
            Text(
                text = result.snippet,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            
            // Source type badge
            if (result.scraped) {
                Spacer(modifier = Modifier.height(Standards.SpacingSm))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "📄 Full content scraped (${result.scrapeLengthChars} chars)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            
            // Expanded content
            AnimatedVisibility(
                visible = expanded && result.content.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(Standards.SpacingMd))
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(Standards.SpacingMd))
                    
                    Text(
                        text = result.content,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun WebSearchResultsFooter(result: EnhancedWebSearchPipelineResult) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
        
        Spacer(modifier = Modifier.height(Standards.SpacingSm))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${result.totalResults} results",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (result.successfulScrapes > 0) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    
                    Text(
                        text = "${result.successfulScrapes} scraped",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Text(
                text = "${result.searchTimeMs + result.scrapingTimeMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

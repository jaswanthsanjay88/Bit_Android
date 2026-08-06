package com.bit.ui.screen.model_store

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import com.bit.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import com.bit.repo.RepositoryValidator
import com.bit.repo.ValidationResult
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.models.data.HFModelRepository
import com.bit.models.data.ModelCategory
import com.bit.models.data.ModelType
import com.bit.models.data.RepositorySource
import com.bit.models.ui.ActionIcon
import com.bit.models.ui.ActionItem
import com.bit.repo.HuggingFaceExplorerRepo
import com.bit.ui.components.ActionButton
import com.bit.ui.components.ActionSwitch
import com.bit.ui.components.CaptionText
import com.bit.ui.components.MultiActionButton
import com.bit.ui.components.SectionHeader
import com.bit.ui.components.StandardCard
import com.bit.ui.components.StatusBadge
import com.bit.ui.theme.maple
import com.bit.viewmodel.ModelStoreViewModel
import com.bit.ui.icons.TnIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── AdvancedTab ──

@Composable
internal fun AdvancedTab(
    deviceInfo: Map<String, String>, viewModel: ModelStoreViewModel
) {
    val repositories by viewModel.repositories.collectAsStateWithLifecycle(emptyList())
    val validationResults by viewModel.validationResults.collectAsStateWithLifecycle()
    val explorerQuery by viewModel.explorerQuery.collectAsStateWithLifecycle()
    val explorerResults by viewModel.explorerResults.collectAsStateWithLifecycle()
    val isExplorerLoading by viewModel.isExplorerLoading.collectAsStateWithLifecycle()
    val explorerError by viewModel.explorerError.collectAsStateWithLifecycle()
    val existingRepoPaths = repositories.map { it.repoPath.lowercase() }.toSet()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRepository by remember { mutableStateOf<HFModelRepository?>(null) }
    val uniqueRepositories = remember(repositories) { repositories.distinctBy { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Standards.SpacingLg, vertical = Standards.SpacingSm),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
    ) {
        // Device Info Section
        item {
            DeviceInfoCard(deviceInfo)
        }

        item {
            ExplorerRepositoriesCard(
                query = explorerQuery,
                results = explorerResults,
                isLoading = isExplorerLoading,
                error = explorerError,
                existingRepoPaths = existingRepoPaths,
                onQueryChange = viewModel::setExplorerQuery,
                onSearch = viewModel::searchExplorerRepositories,
                onAdd = viewModel::addExplorerRepository
            )
        }

        // Repositories Section
        item {
            SectionHeader(
                title = "Hugging Face Models",
                action = {
                    ActionButton(
                        onClickListener = { showAddDialog = true },
                        icon = TnIcons.Plus,
                        contentDescription = "Add Model"
                    )
                }
            )
        }

        items(uniqueRepositories, key = { it.id }) { repo ->
            RepositoryCard(
                repository = repo,
                validationResult = validationResults[repo.id],
                onToggle = { viewModel.toggleRepository(repo.id) },
                onEdit = { editingRepository = repo },
                onValidate = { viewModel.validateRepository(repo) },
                onDelete = { viewModel.removeRepository(repo.id) }
            )
        }
    }

    if (showAddDialog) {
        AddRepositoryDialog(onDismiss = { showAddDialog = false }, onAdd = { repo ->
            viewModel.addRepository(repo)
            showAddDialog = false
        })
    }

    editingRepository?.let { repo ->
        EditRepositoryDialog(
            repository = repo,
            onDismiss = { editingRepository = null },
            onSave = { updatedRepo ->
                viewModel.updateRepository(updatedRepo)
                editingRepository = null
            }
        )
    }
}

// ── ExplorerRepositoriesCard ──

@Composable
internal fun ExplorerRepositoriesCard(
    query: String,
    results: List<HuggingFaceExplorerRepo>,
    isLoading: Boolean,
    error: String?,
    existingRepoPaths: Set<String>,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: (HuggingFaceExplorerRepo) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    StandardCard(
        title = "HuggingFace Model Explorer",
        icon = TnIcons.Search,
        trailing = {
            ActionButton(
                onClickListener = { expanded = !expanded },
                icon = if (expanded) TnIcons.ChevronUp else TnIcons.ChevronDown,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
        }
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = Motion.Enter,
            exit = Motion.Exit
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search HuggingFace models") },
                    placeholder = { Text("e.g. qwen, stable-diffusion, mistral, whisper") },
                    singleLine = true,
                    trailingIcon = {
                        ActionButton(
                            onClickListener = onSearch,
                            icon = TnIcons.Search,
                            contentDescription = "Search"
                        )
                    }
                )

                // ── Status Row ──
                AnimatedContent(
                    targetState = Triple(isLoading, error, results.size),
                    transitionSpec = {
                        fadeIn(Motion.entrance()) togetherWith fadeOut(Motion.exit())
                    },
                    label = "explorer_status"
                ) { (loading, err, count) ->
                    when {
                        loading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                CaptionText(text = "Searching HuggingFace...")
                            }
                        }
                        !err.isNullOrBlank() -> {
                            CaptionText(
                                text = err,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        count > 0 -> {
                            CaptionText(text = "$count result${if (count != 1) "s" else ""} found")
                        }
                        else -> Spacer(modifier = Modifier.height(0.dp))
                    }
                }

                // ── Results ──
                val displayedResults = results.take(8)
                displayedResults.forEachIndexed { index, repo ->
                    val isAdded = existingRepoPaths.contains(repo.id.lowercase())

                    var visible by remember(repo.id) { mutableStateOf(false) }
                    LaunchedEffect(repo.id) {
                        delay(index * 60L)
                        visible = true
                    }

                    AnimatedVisibility(
                        visible = visible,
                        enter = slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = Motion.content()
                        ) + fadeIn(Motion.content())
                    ) {
                        Column {
                            ExplorerResultRow(
                                repo = repo,
                                isAdded = isAdded,
                                onAdd = { onAdd(repo) }
                            )
                            if (index < displayedResults.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── ExplorerResultRow ──

@Composable
internal fun ExplorerResultRow(
    repo: HuggingFaceExplorerRepo,
    isAdded: Boolean,
    onAdd: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(Standards.CardSmallCornerRadius)
    ) {
        Row(
            modifier = Modifier.padding(Standards.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repo.id,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaptionText(text = "${repo.downloads} downloads")
                    CaptionText(text = "·")
                    CaptionText(text = "${repo.likes} likes")
                    if (repo.gated) {
                        CaptionText(text = "·")
                        StatusBadge(text = "Gated", isActive = true)
                    }
                }
            }

            if (isAdded) {
                Icon(
                    imageVector = TnIcons.CircleCheck,
                    contentDescription = "Added",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(Standards.ActionIconSize)
                )
            } else {
                ActionButton(
                    onClickListener = onAdd,
                    icon = TnIcons.Plus,
                    contentDescription = "Add repository"
                )
            }
        }
    }
}

// ── RepositoryCard ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RepositoryCard(
    repository: HFModelRepository,
    validationResult: ValidationResult?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onValidate: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(Standards.CardSmallCornerRadius),
        onClick = onValidate
    ) {
        Column(
            modifier = Modifier.padding(Standards.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
        ) {
            // Row 1: validation dot + name + actions + switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                // Validation status dot
                val dotColor = when (validationResult) {
                    is ValidationResult.Valid -> MaterialTheme.colorScheme.primary
                    is ValidationResult.Invalid -> MaterialTheme.colorScheme.error
                    is ValidationResult.Checking -> MaterialTheme.colorScheme.tertiary
                    null -> MaterialTheme.colorScheme.outlineVariant
                }
                if (validationResult is ValidationResult.Checking) {
                    LoadingIndicator(
                        modifier = Modifier.size(10.dp),
                        color = dotColor
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(dotColor, RoundedCornerShape(50))
                    )
                }

                // Repo name
                Text(
                    text = repository.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (repository.isEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )

                // Grouped Edit + Delete
                MultiActionButton(
                    actions = listOf(
                        ActionItem(
                            icon = ActionIcon.Vector(TnIcons.Edit),
                            onClick = onEdit,
                            contentDescription = "Edit"
                        ),
                        ActionItem(
                            icon = ActionIcon.Vector(TnIcons.Trash),
                            onClick = onDelete,
                            contentDescription = "Delete"
                        )
                    )
                )

                // Toggle
                ActionSwitch(
                    checked = repository.isEnabled,
                    onCheckedChange = { onToggle() }
                )
            }

            // Row 2: repo path + category + GGUF count (inline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = repository.repoPath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = maple,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                CaptionText(text = "·")
                CaptionText(text = if (repository.source == RepositorySource.CUSTOM_API) "API" else repository.category.displayName)

                if (validationResult is ValidationResult.Valid) {
                    CaptionText(text = "·")
                    CaptionText(
                        text = "${validationResult.ggufFileCount} ${validationResult.label}",
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (validationResult is ValidationResult.Invalid) {
                    CaptionText(text = "·")
                    CaptionText(
                        text = validationResult.reason,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ── AddRepositoryDialog ──

private fun generateRepositoryKeyFromName(name: String): String {
    val slug = name.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    return if (slug.isNotBlank()) slug else "custom-api"
}

@Composable
internal fun AddRepositoryDialog(
    onDismiss: () -> Unit, onAdd: (HFModelRepository) -> Unit
) {
    var repoName by remember { mutableStateOf("") }
    var repoPath by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(RepositorySource.HUGGING_FACE) }
    var apiBaseUrl by remember { mutableStateOf("") }
    var apiEndpoint by remember { mutableStateOf("/api/v1/models") }
    var apiAuthToken by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ModelType.GGUF) }

    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf("Custom") }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add Repository") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)) {
            OutlinedTextField(
                value = repoName,
                onValueChange = { repoName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = repoPath,
                onValueChange = { repoPath = it },
                label = { Text(if (source == RepositorySource.HUGGING_FACE) "Repository Path" else "Repository Key (optional)") },
                placeholder = { Text(if (source == RepositorySource.HUGGING_FACE) "username/repo-name" else "auto-generated-from-name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Source",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                FilterChip(
                    selected = source == RepositorySource.HUGGING_FACE,
                    onClick = { source = RepositorySource.HUGGING_FACE },
                    label = { Text("HuggingFace") }
                )
                FilterChip(
                    selected = source == RepositorySource.CUSTOM_API,
                    onClick = { source = RepositorySource.CUSTOM_API },
                    label = { Text("Custom API") }
                )
            }

            if (source == RepositorySource.CUSTOM_API) {
                Text(
                    text = "API Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    listOf("Custom", "OpenRouter", "OpenAI", "Gemini", "Groq", "NVIDIA NIM").forEach { name ->
                        FilterChip(
                            selected = preset == name,
                            onClick = {
                                preset = name
                                when (name) {
                                    "OpenRouter" -> {
                                        apiBaseUrl = "https://openrouter.ai/api/v1/"
                                        apiEndpoint = "models"
                                    }
                                    "OpenAI" -> {
                                        apiBaseUrl = "https://api.openai.com/v1/"
                                        apiEndpoint = "models"
                                    }
                                    "Gemini" -> {
                                        apiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                                        apiEndpoint = "models"
                                    }
                                    "Groq" -> {
                                        apiBaseUrl = "https://api.groq.com/openai/v1/"
                                        apiEndpoint = "models"
                                    }
                                    "NVIDIA NIM" -> {
                                        apiBaseUrl = "https://integrate.api.nvidia.com/v1/"
                                        apiEndpoint = "models"
                                    }
                                }
                                testStatus = null
                            },
                            label = { Text(name) }
                        )
                    }
                }

                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { 
                        apiBaseUrl = it 
                        preset = "Custom"
                        testStatus = null
                    },
                    label = { Text("API Base URL") },
                    placeholder = { Text("https://api.example.com/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiEndpoint,
                    onValueChange = { 
                        apiEndpoint = it 
                        preset = "Custom"
                        testStatus = null
                    },
                    label = { Text("Catalog Endpoint") },
                    placeholder = { Text("/api/v1/models") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiAuthToken,
                    onValueChange = { 
                        apiAuthToken = it 
                        testStatus = null
                    },
                    label = { Text("Authorization Header (optional)") },
                    placeholder = { Text("Bearer <token>") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            isTesting = true
                            testStatus = "Connecting..."
                            scope.launch {
                                try {
                                    val tempRepo = HFModelRepository(
                                        id = "test-repo",
                                        name = repoName.ifBlank { "test" },
                                        repoPath = "test",
                                        modelType = selectedType,
                                        source = source,
                                        apiBaseUrl = apiBaseUrl.trim(),
                                        apiEndpoint = apiEndpoint.trim(),
                                        apiAuthToken = apiAuthToken.trim()
                                    )
                                    val validator = RepositoryValidator()
                                    val res = validator.validateRepository(tempRepo)
                                    testStatus = when (res) {
                                        is ValidationResult.Valid -> "Success! Found ${res.ggufFileCount} models."
                                        is ValidationResult.Invalid -> "Error: ${res.reason}"
                                        else -> "Failed"
                                    }
                                } catch (e: Exception) {
                                    testStatus = "Error: ${e.message}"
                                } finally {
                                    isTesting = false
                                }
                            }
                        },
                        enabled = apiBaseUrl.isNotBlank() && !isTesting
                    ) {
                        Text(if (isTesting) "Testing..." else "Test Connection")
                    }

                    testStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.startsWith("Success")) MaterialTheme.colorScheme.primary 
                                    else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                FilterChip(
                    selected = selectedType == ModelType.GGUF,
                    onClick = { selectedType = ModelType.GGUF },
                    label = { Text("GGUF") })
                FilterChip(
                    selected = selectedType == ModelType.TTS,
                    onClick = { selectedType = ModelType.TTS },
                    label = { Text("TTS") })
            }
        }
    }, confirmButton = {
        Button(
            onClick = {
                val resolvedRepoPath = if (source == RepositorySource.CUSTOM_API) {
                    repoPath.trim().ifBlank { generateRepositoryKeyFromName(repoName) }
                } else {
                    repoPath.trim()
                }

                if (repoName.isNotBlank() && resolvedRepoPath.isNotBlank() && (source == RepositorySource.HUGGING_FACE || apiBaseUrl.isNotBlank())) {
                    onAdd(
                        HFModelRepository(
                            id = resolvedRepoPath.replace("/", "-"),
                            name = repoName,
                            repoPath = resolvedRepoPath,
                            modelType = selectedType,
                            source = source,
                            apiBaseUrl = apiBaseUrl.trim(),
                            apiEndpoint = apiEndpoint.trim().ifBlank { "/api/v1/models" },
                            apiAuthToken = apiAuthToken.trim()
                        )
                    )
                }
            }, enabled = repoName.isNotBlank() && (if (source == RepositorySource.HUGGING_FACE) repoPath.isNotBlank() else true) && (source == RepositorySource.HUGGING_FACE || apiBaseUrl.isNotBlank())
        ) {
            Text("Add")
        }
    }, dismissButton = {
        TextButton(onClick = onDismiss) {
            Text("Cancel")
        }
    })
}

// ── EditRepositoryDialog ──

@Composable
internal fun EditRepositoryDialog(
    repository: HFModelRepository,
    onDismiss: () -> Unit,
    onSave: (HFModelRepository) -> Unit
) {
    var repoName by remember { mutableStateOf(repository.name) }
    var repoPath by remember { mutableStateOf(repository.repoPath) }
    var source by remember { mutableStateOf(repository.source) }
    var apiBaseUrl by remember { mutableStateOf(repository.apiBaseUrl) }
    var apiEndpoint by remember { mutableStateOf(repository.apiEndpoint) }
    var apiAuthToken by remember { mutableStateOf(repository.apiAuthToken) }
    var selectedType by remember { mutableStateOf(repository.modelType) }
    var selectedCategory by remember { mutableStateOf(repository.category) }
    var showCategoryDropdown by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val initialPreset = remember(repository) {
        when {
            repository.apiBaseUrl == "https://openrouter.ai/api/v1/" && repository.apiEndpoint == "models" -> "OpenRouter"
            repository.apiBaseUrl == "https://api.openai.com/v1/" && repository.apiEndpoint == "models" -> "OpenAI"
            repository.apiBaseUrl == "https://generativelanguage.googleapis.com/v1beta/openai/" && repository.apiEndpoint == "models" -> "Gemini"
            repository.apiBaseUrl == "https://api.groq.com/openai/v1/" && repository.apiEndpoint == "models" -> "Groq"
            repository.apiBaseUrl == "https://integrate.api.nvidia.com/v1/" && repository.apiEndpoint == "models" -> "NVIDIA NIM"
            else -> "Custom"
        }
    }
    var preset by remember { mutableStateOf(initialPreset) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)) {
                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = repoPath,
                    onValueChange = { repoPath = it },
                    label = { Text(if (source == RepositorySource.HUGGING_FACE) "Repository Path" else "Repository Key (optional)") },
                    placeholder = { Text(if (source == RepositorySource.HUGGING_FACE) "username/repo-name" else "auto-generated-from-name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Source",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    FilterChip(
                        selected = source == RepositorySource.HUGGING_FACE,
                        onClick = { source = RepositorySource.HUGGING_FACE },
                        label = { Text("HuggingFace") }
                    )
                    FilterChip(
                        selected = source == RepositorySource.CUSTOM_API,
                        onClick = { source = RepositorySource.CUSTOM_API },
                        label = { Text("Custom API") }
                    )
                }

                if (source == RepositorySource.CUSTOM_API) {
                    Text(
                        text = "API Presets",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        listOf("Custom", "OpenRouter", "OpenAI", "Gemini", "Groq", "NVIDIA NIM").forEach { name ->
                            FilterChip(
                                selected = preset == name,
                                onClick = {
                                    preset = name
                                    when (name) {
                                        "OpenRouter" -> {
                                            apiBaseUrl = "https://openrouter.ai/api/v1/"
                                            apiEndpoint = "models"
                                        }
                                        "OpenAI" -> {
                                            apiBaseUrl = "https://api.openai.com/v1/"
                                            apiEndpoint = "models"
                                        }
                                        "Gemini" -> {
                                            apiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                                            apiEndpoint = "models"
                                        }
                                        "Groq" -> {
                                            apiBaseUrl = "https://api.groq.com/openai/v1/"
                                            apiEndpoint = "models"
                                        }
                                        "NVIDIA NIM" -> {
                                            apiBaseUrl = "https://integrate.api.nvidia.com/v1/"
                                            apiEndpoint = "models"
                                        }
                                    }
                                    testStatus = null
                                },
                                label = { Text(name) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = apiBaseUrl,
                        onValueChange = { 
                            apiBaseUrl = it 
                            preset = "Custom"
                            testStatus = null
                        },
                        label = { Text("API Base URL") },
                        placeholder = { Text("https://api.example.com/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = apiEndpoint,
                        onValueChange = { 
                            apiEndpoint = it 
                            preset = "Custom"
                            testStatus = null
                        },
                        label = { Text("Catalog Endpoint") },
                        placeholder = { Text("/api/v1/models") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = apiAuthToken,
                        onValueChange = { 
                            apiAuthToken = it 
                            testStatus = null
                        },
                        label = { Text("Authorization Header (optional)") },
                        placeholder = { Text("Bearer <token>") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                isTesting = true
                                testStatus = "Connecting..."
                                scope.launch {
                                    try {
                                        val tempRepo = HFModelRepository(
                                            id = repository.id.ifBlank { "test-repo" },
                                            name = repoName.ifBlank { "test" },
                                            repoPath = repoPath.ifBlank { "test" },
                                            modelType = selectedType,
                                            source = source,
                                            apiBaseUrl = apiBaseUrl.trim(),
                                            apiEndpoint = apiEndpoint.trim(),
                                            apiAuthToken = apiAuthToken.trim()
                                        )
                                        val validator = RepositoryValidator()
                                        val res = validator.validateRepository(tempRepo)
                                        testStatus = when (res) {
                                            is ValidationResult.Valid -> "Success! Found ${res.ggufFileCount} models."
                                            is ValidationResult.Invalid -> "Error: ${res.reason}"
                                            else -> "Failed"
                                        }
                                    } catch (e: Exception) {
                                        testStatus = "Error: ${e.message}"
                                    } finally {
                                        isTesting = false
                                    }
                                }
                            },
                            enabled = apiBaseUrl.isNotBlank() && !isTesting
                        ) {
                            Text(if (isTesting) "Testing..." else "Test Connection")
                        }

                        testStatus?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.startsWith("Success")) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                        }
                    }
                }

                // Model Type
                Text(
                    text = "Model Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    FilterChip(
                        selected = selectedType == ModelType.GGUF,
                        onClick = { selectedType = ModelType.GGUF },
                        label = { Text("GGUF") }
                    )
                    // FilterChip(
                    //     selected = selectedType == ModelType.SD,
                    //     onClick = { selectedType = ModelType.SD },
                    //     label = { Text("Stable Diffusion") }
                    // )
                    FilterChip(
                        selected = selectedType == ModelType.TTS,
                        onClick = { selectedType = ModelType.TTS },
                        label = { Text("TTS") }
                    )
                }

                // Category Dropdown
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Category chips displayed as a grid
                Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm), modifier = Modifier.scrollable(rememberScrollState(), orientation = Orientation.Horizontal)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        FilterChip(
                            selected = selectedCategory == ModelCategory.GENERAL,
                            onClick = { selectedCategory = ModelCategory.GENERAL },
                            label = { Text("General") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedCategory == ModelCategory.MEDICAL,
                            onClick = { selectedCategory = ModelCategory.MEDICAL },
                            label = { Text("Medical") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        FilterChip(
                            selected = selectedCategory == ModelCategory.RESEARCH,
                            onClick = { selectedCategory = ModelCategory.RESEARCH },
                            label = { Text("Research") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedCategory == ModelCategory.CODING,
                            onClick = { selectedCategory = ModelCategory.CODING },
                            label = { Text("Coding") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        FilterChip(
                            selected = selectedCategory == ModelCategory.UNCENSORED,
                            onClick = { selectedCategory = ModelCategory.UNCENSORED },
                            label = { Text("Uncensored") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedCategory == ModelCategory.BUSINESS,
                            onClick = { selectedCategory = ModelCategory.BUSINESS },
                            label = { Text("Business") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        FilterChip(
                            selected = selectedCategory == ModelCategory.CYBERSECURITY,
                            onClick = { selectedCategory = ModelCategory.CYBERSECURITY },
                            label = { Text("Cybersecurity") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val resolvedRepoPath = if (source == RepositorySource.CUSTOM_API) {
                        repoPath.trim().ifBlank { repository.repoPath.ifBlank { generateRepositoryKeyFromName(repoName) } }
                    } else {
                        repoPath.trim()
                    }

                    if (repoName.isNotBlank() && resolvedRepoPath.isNotBlank() && (source == RepositorySource.HUGGING_FACE || apiBaseUrl.isNotBlank())) {
                        onSave(
                            repository.copy(
                                name = repoName,
                                repoPath = resolvedRepoPath,
                                modelType = selectedType,
                                category = selectedCategory,
                                source = source,
                                apiBaseUrl = apiBaseUrl.trim(),
                                apiEndpoint = apiEndpoint.trim().ifBlank { "/api/v1/models" },
                                apiAuthToken = apiAuthToken.trim()
                            )
                        )
                    }
                },
                enabled = repoName.isNotBlank() && (if (source == RepositorySource.HUGGING_FACE) repoPath.isNotBlank() else true) && (source == RepositorySource.HUGGING_FACE || apiBaseUrl.isNotBlank())
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

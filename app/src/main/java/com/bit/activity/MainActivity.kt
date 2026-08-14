package com.bit.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bit.data.AppSettingsDataStore
import com.bit.data.SetupDataStore
import com.bit.data.TermsDataStore
import com.bit.data.VaultManager
import com.bit.di.AppContainer
import com.bit.models.enums.ProviderType
import com.bit.ui.screen.guide.GuideScreen
import com.bit.ui.screen.guide.TermsAndConditionsScreen
import com.bit.ui.screen.home.HomeScreen
import com.bit.ui.screen.memory.AiMemoryScreen
import com.bit.ui.screen.memory.MemoryVaultScreen
import com.bit.ui.screen.model_config.ModelConfigEditorScreen
import com.bit.ui.screen.model_store.ModelStoreScreen
import com.bit.ui.screen.settings.SettingsScreen
import com.bit.ui.screen.setup.ImageGenSetupScreen
import com.bit.ui.screen.setup.EmbeddingSetupScreen
import com.bit.ui.screen.setup.SetupScreen
import com.bit.ui.theme.NeuroVerseTheme
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.LLMModelViewModel
import com.bit.worker.LlmModelWorker
import com.bit.worker.NotificationPermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bit.global.AppPaths
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var ragRepository: com.bit.repo.RagRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Bind LLM service after activity is created (Android 14+ requirement)
        LlmModelWorker.bindService(applicationContext)

        if (!NotificationPermissionHelper.hasNotificationPermission(this)) {
            NotificationPermissionHelper.requestNotificationPermission(this) {
                if (it) {
                    Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        setContent {
            NeuroVerseTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.bit.ui.theme.LocalBitHaptics provides com.bit.ui.theme.rememberBitHaptics(enabled = true)
                ) {
                val context = this@MainActivity

                // Compute target destination from onboarding state + installed models
                var targetDestination by remember { mutableStateOf<String?>(null) }
                var hasModelsInstalled by remember { mutableStateOf(false) }
                var needsMigration by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        // Parallelize DataStore reads — each opens a separate file
                        val (termsAccepted, setupDone, guideSeen) = coroutineScope {
                            val t = async { TermsDataStore(context).hasAcceptedTerms.first() }
                            val s = async { SetupDataStore(context).isSetupDone.first() }
                            val g = async { AppSettingsDataStore(context).guideSeen.first() }
                            Triple(t.await(), s.await(), g.await())
                        }

                        // Auto-init vault for returning users (exists on disk but not yet opened)
                        if (!VaultManager.isReady.value && VaultManager.exists(context)) {
                            VaultManager.initPlaintext(context)
                            AppContainer.ensureVaultInitialized()
                        }

                        val vaultReady = VaultManager.isReady.value

                        // Check for legacy data that needs migration
                        val roomDb = context.getDatabasePath("llm_models_database").exists()
                        val vault = AppPaths.vaultFile(context).exists()
                        needsMigration = roomDb || vault

                        // Only check models if vault is ready
                        val hasModel = if (vaultReady) {
                            try {
                                val modelRepository = AppContainer.getModelRepository()
                                val models = modelRepository.getAllModels().first()
                                models.any {
                                    it.providerType == ProviderType.GGUF ||
                                        it.providerType == ProviderType.API
                                }
                            } catch (_: Exception) { false }
                        } else false
                        hasModelsInstalled = hasModel

                        targetDestination = when {
                            // Returning user: terms accepted + (setup done or has model)
                            termsAccepted && (setupDone || hasModel) -> Screen.Chat.route

                            // First launch: show guide
                            !guideSeen -> Screen.Guide.route

                            // Guide seen but terms not accepted
                            !termsAccepted -> Screen.Terms.route

                            // Fallback: go to setup (which handles vault init if needed)
                            else -> Screen.OnboardingSetup.route
                        }
                        android.util.Log.d("MainActivity", "Onboarding check: termsAccepted=$termsAccepted, setupDone=$setupDone, guideSeen=$guideSeen, vaultReady=$vaultReady, hasModel=$hasModel -> targetDestination=$targetDestination")
                    }
                }

                // Non-blocking GitHub update check on every app start
                val updateChecker = remember { com.bit.update.UpdateChecker(context) }
                var pendingUpdate by remember { mutableStateOf<com.bit.update.UpdateInfo?>(null) }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        when (val result = updateChecker.checkForUpdate()) {
                            is com.bit.update.UpdateCheckResult.UpdateAvailable -> {
                                withContext(Dispatchers.Main) {
                                    pendingUpdate = result.info
                                }
                            }
                            else -> {}
                        }
                    }
                }

                AppNavigation(
                    startDestination = Screen.Intro.route,
                    targetDestination = targetDestination,
                    hasModelsInstalled = hasModelsInstalled,
                    needsMigration = needsMigration
                )

                pendingUpdate?.let { info ->
                    com.bit.update.UpdateBottomSheet(
                        update = info,
                        updateChecker = updateChecker,
                        onDismiss = { pendingUpdate = null }
                    )
                }
            }
        }
    }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear password cache when app terminates
        ragRepository.clearPasswordCache()
        LlmModelWorker.unbindService()
        AppContainer.shutdown()
    }
}

sealed class Screen(val route: String) {
    // Onboarding (flat routes so any can be used as startDestination)
    object Intro : Screen("intro")
    object Guide : Screen("guide")
    object Terms : Screen("terms")
    object OnboardingSetup : Screen("setup")

    // Main app
    object Chat : Screen("chat")
    object Store : Screen("store")
    object Editor : Screen("editor")
    object Settings : Screen("settings")
    object AiMemory : Screen("ai_memory")
    object Update : Screen("update")
    object ImageGenSetup : Screen("image_gen_setup")
    object EmbeddingSetup : Screen("embedding_setup")
    object MemoryVault : Screen("memory_vault")
    object NoteDetail : Screen("note_detail?noteId={noteId}&defaultType={defaultType}") {
        fun createRoute(noteId: String? = null, defaultType: String = "note") =
            "note_detail?noteId=${noteId ?: ""}&defaultType=$defaultType"
    }
    object NotesList : Screen("notes_list")
    object AiMemoryList : Screen("ai_memory_list")
    object DocumentsRag : Screen("documents_rag")
    object TaskList : Screen("task_list")
    object ConflictReview : Screen("conflict_review")
    object BackupSettings : Screen("backup_settings")
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation(
    startDestination: String,
    targetDestination: String?,
    hasModelsInstalled: Boolean,
    needsMigration: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    // Activity-scoped ViewModels for shared state between Chat and Personas
    val chatViewModel: ChatViewModel = hiltViewModel()
    val llmModelViewModel: LLMModelViewModel = hiltViewModel()

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {

            // ============ ONBOARDING SCREENS ============

            composable(
                route = Screen.Intro.route,
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                com.bit.ui.screen.intro.IntroScreen(
                    innerPadding = PaddingValues(0.dp),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    targetDestination = targetDestination,
                    onFinish = { target ->
                        navController.navigate(target) {
                            popUpTo(Screen.Intro.route) { inclusive = true }
                        }
                    }
                )
            }



            composable(Screen.Guide.route) {
                val appSettings = remember { AppSettingsDataStore(context) }
                GuideScreen(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onContinue = {
                        scope.launch {
                            appSettings.saveGuideSeen(true)
                            withContext(Dispatchers.Main) {
                                navController.navigate(Screen.Terms.route) {
                                    popUpTo(Screen.Guide.route) { inclusive = true }
                                }
                            }
                        }
                    }
                )
            }

            composable(Screen.Terms.route) {
                val termsDataStore = remember { TermsDataStore(context) }
                TermsAndConditionsScreen(
                    onAccept = {
                        scope.launch {
                            termsDataStore.acceptTerms()
                            withContext(Dispatchers.Main) {
                                if (hasModelsInstalled) {
                                    // Returning user: skip setup, go to chat
                                    navController.navigate(Screen.Chat.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                } else {
                                    // New user: proceed to setup
                                    navController.navigate(Screen.OnboardingSetup.route) {
                                        popUpTo(Screen.Terms.route) { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                )
            }

            composable(Screen.OnboardingSetup.route) {
                SetupScreen(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onSetupComplete = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            // ============ MAIN APP ROUTES ============
            composable(Screen.Chat.route) { _ ->
                HomeScreen(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onStoreButtonClicked = { tab ->
                        val route = if (tab != null) "store?tab=$tab" else "store"
                        navController.navigate(route)
                    },
                    onVaultManagerClick = {
                        navController.navigate(Screen.MemoryVault.route)
                    },
                onImageGenSetupNeeded = {
                    navController.navigate(Screen.ImageGenSetup.route)
                },
                onModelSelectedNavigate = { model ->
                    val targetRoute = Screen.Chat.route
                    if (navController.currentDestination?.route != targetRoute) {
                        navController.navigate(targetRoute) {
                            launchSingleTop = true
                        }
                    }
                },
                chatViewModel = chatViewModel,
                llmModelViewModel = llmModelViewModel
            )
        }

        composable(Screen.Editor.route) {
            ModelConfigEditorScreen(onBackClick = {
                navController.popBackStack()
            })
        }

        composable(
            route = "store?tab={tab}",
            arguments = listOf(
                androidx.navigation.navArgument("tab") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            ModelStoreScreen(
                onNavigateBack = { navController.popBackStack() },
                llmModelViewModel = llmModelViewModel
            )
        }

        composable(Screen.Settings.route) {
            val context = LocalContext.current
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onModelEditor = { navController.navigate(Screen.Editor.route) },
                onAiMemoryClick = { navController.navigate(Screen.AiMemory.route) },
                onEmbeddingSetupClick = { navController.navigate(Screen.EmbeddingSetup.route) },
                onDiagnosticsClick = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) },
                onCheckForUpdates = { navController.navigate(Screen.Update.route) }
            )
        }


        composable(Screen.AiMemory.route) {
            AiMemoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ImageGenSetup.route) {
            ImageGenSetupScreen(
                onComplete = {
                    llmModelViewModel.onQnnSetupComplete()
                    navController.popBackStack()
                },
                onSkip = {
                    llmModelViewModel.onQnnSetupDismissed()
                    navController.popBackStack()
                },
                onBack = {
                    llmModelViewModel.onQnnSetupDismissed()
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.EmbeddingSetup.route) {
            EmbeddingSetupScreen(
                onSetupComplete = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.MemoryVault.route) {
            MemoryVaultScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNoteClick = { noteId, defaultType ->
                    navController.navigate(Screen.NoteDetail.createRoute(noteId, defaultType))
                },
                onNotesListClick = { navController.navigate(Screen.NotesList.route) },
                onAiMemoryListClick = { navController.navigate(Screen.AiMemoryList.route) },
                onBackupSettingsClick = { navController.navigate(Screen.BackupSettings.route) }
            )
        }
        composable(
            route = "note_detail?noteId={noteId}&defaultType={defaultType}",
            arguments = listOf(
                androidx.navigation.navArgument("noteId") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                androidx.navigation.navArgument("defaultType") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = "note"
                }
            )
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")
            val defaultType = backStackEntry.arguments?.getString("defaultType") ?: "note"
            com.bit.ui.screen.memory.NoteDetailScreen(
                noteId = noteId,
                defaultType = defaultType,
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Screen.NotesList.route) {
            com.bit.ui.screen.memory.NotesListScreen(
                onBackClick = { navController.popBackStack() },
                onNoteClick = { noteId, defaultType ->
                    navController.navigate(Screen.NoteDetail.createRoute(noteId, defaultType))
                }
            )
        }
        composable(Screen.AiMemoryList.route) {
            com.bit.ui.screen.memory.AiMemoryListScreen(
                onBackClick = { navController.popBackStack() },
                onConflictBannerClick = { navController.navigate(Screen.ConflictReview.route) },
                onNoteClick = { noteId, defaultType ->
                    navController.navigate(Screen.NoteDetail.createRoute(noteId, defaultType))
                }
            )
        }

        composable(Screen.TaskList.route) {
            com.bit.ui.screen.memory.TaskListView(
                onBackClick = { navController.popBackStack() },
                onNoteClick = { noteId, defaultType ->
                    navController.navigate(Screen.NoteDetail.createRoute(noteId, defaultType))
                }
            )
        }
        composable(Screen.ConflictReview.route) {
            com.bit.ui.screen.memory.ConflictReviewScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Screen.Update.route) {
            com.bit.ui.screen.settings.UpdateScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.BackupSettings.route) {
            com.bit.ui.screen.memory.BackupSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
    }
}

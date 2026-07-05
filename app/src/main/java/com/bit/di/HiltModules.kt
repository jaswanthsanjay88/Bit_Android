    package com.bit.di

    import android.content.Context
    import com.bit.data.AppSettingsDataStore
    import com.bit.database.AppDatabase
    import com.bit.engine.EmbeddingEngine
    import com.bit.repo.ChatRepository
    import com.bit.repo.RagRepository
    import com.bit.tts.TTSDataStore
    import com.bit.worker.ChatManager
    import com.bit.worker.RagVaultIntegration
    import dagger.Module
    import dagger.Provides
    import dagger.hilt.InstallIn
    import dagger.hilt.android.qualifiers.ApplicationContext
    import dagger.hilt.components.SingletonComponent
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.SupervisorJob
    import javax.inject.Qualifier
    import javax.inject.Singleton

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class ApplicationScope

    @Module
    @InstallIn(SingletonComponent::class)
    object DatabaseModule {

        @Provides
        @Singleton
        fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
            return AppDatabase.getDatabase(context)
        }
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object DataStoreModule {

        @Provides
        @Singleton
        fun provideAppSettingsDataStore(
            @ApplicationContext context: Context
        ): AppSettingsDataStore = AppSettingsDataStore(context)

        @Provides
        @Singleton
        fun provideTTSDataStore(
            @ApplicationContext context: Context
        ): TTSDataStore = TTSDataStore(context)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object RepositoryModule {

        @Provides
        @Singleton
        fun provideChatRepository(): ChatRepository {
            return ChatRepository()
        }

        @Provides
        @Singleton
        fun provideRagRepository(
            database: AppDatabase,
            @ApplicationContext context: Context
        ): RagRepository {
            return RagRepository(
                ragDao = database.ragDao(),
                context = context
            )
        }
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object EmbeddingModule {

        @Provides
        @Singleton
        fun provideEmbeddingEngine(): EmbeddingEngine {
            return EmbeddingEngine()
        }
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object WorkerModule {

        @Provides
        @Singleton
        fun provideChatManager(): ChatManager {
            return ChatManager()
        }

        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope {
            return CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        @Provides
        @Singleton
        fun provideRagVaultIntegration(
            @ApplicationContext context: Context,
            ragRepository: RagRepository,
            embeddingEngine: EmbeddingEngine
        ): RagVaultIntegration {
            return RagVaultIntegration(
                context = context,
                ragRepository = ragRepository,
                embeddingEngine = embeddingEngine
            )
        }
    }

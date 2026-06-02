package com.bit.di

import com.bit.domain.repository.ChatRepositoryContract
import com.bit.domain.repository.HuggingFaceExplorerContract
import com.bit.repo.ChatRepository
import com.bit.repo.HuggingFaceExplorerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for repository interfaces → implementations.
 * Enables constructor injection of interfaces instead of concrete classes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModules {

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepository): ChatRepositoryContract

    @Binds
    @Singleton
    abstract fun bindHuggingFaceExplorer(impl: HuggingFaceExplorerRepository): HuggingFaceExplorerContract
}

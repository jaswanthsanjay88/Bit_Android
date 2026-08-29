package com.bit.agent.harness.di

import android.content.Context
import com.bit.agent.harness.AndroidHarnessLogger
import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.engine.AgentHarnessEngine
import com.bit.agent.harness.engine.LlmGoalPlanner
import com.bit.agent.harness.engine.PlanGenerator
import com.bit.agent.harness.gate.SelfCorrectionPlanner
import com.bit.agent.harness.gate.StepGateChecker
import com.bit.agent.harness.tools.AgentToolBridge
import com.bit.agent.harness.tools.AgentToolRegistry
import com.bit.database.dao.MemoryNoteDao
import com.bit.mcp.McpManager
import com.bit.worker.GlobalRagOrchestrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HarnessModule {

    @Provides
    @Singleton
    fun provideHarnessLogger(): HarnessLogger {
        return AndroidHarnessLogger()
    }

    @Provides
    @Singleton
    fun provideAgentToolBridge(): AgentToolBridge {
        return AgentToolBridge()
    }

    @Provides
    @Singleton
    fun provideStepGateChecker(): StepGateChecker {
        return StepGateChecker()
    }

    @Provides
    @Singleton
    fun provideSelfCorrectionPlanner(): SelfCorrectionPlanner {
        return SelfCorrectionPlanner()
    }

    @Provides
    @Singleton
    fun provideAgentToolRegistry(
        @ApplicationContext context: Context,
        bridge: AgentToolBridge,
        ragOrchestrator: GlobalRagOrchestrator,
        memoryNoteDao: MemoryNoteDao,
        mcpManager: McpManager,
        logger: HarnessLogger
    ): AgentToolRegistry {
        val registry = AgentToolRegistry(
            context = context,
            bridge = bridge,
            ragOrchestrator = ragOrchestrator,
            memoryNoteDao = memoryNoteDao,
            mcpManager = mcpManager,
            logger = logger
        )
        // Real multi-agent execution: subagents run isolated LLM+tool loops.
        registry.subagentExecutor = com.bit.agent.harness.engine.SubagentRunner(logger)
        return registry
    }

    @Provides
    @Singleton
    fun providePlanGenerator(registry: AgentToolRegistry): PlanGenerator {
        return LlmGoalPlanner(toolRegistry = registry)
    }

    @Provides
    @Singleton
    fun provideHarnessSynthesizer(logger: HarnessLogger): com.bit.agent.harness.engine.HarnessSynthesizer {
        return com.bit.agent.harness.engine.HarnessSynthesizer(logger)
    }

    @Provides
    @Singleton
    fun provideAgentHarnessEngine(
        @ApplicationContext context: Context,
        toolBridge: AgentToolBridge,
        toolRegistry: AgentToolRegistry,
        gateChecker: StepGateChecker,
        correctionPlanner: SelfCorrectionPlanner,
        logger: HarnessLogger,
        planGenerator: PlanGenerator,
        synthesizer: com.bit.agent.harness.engine.HarnessSynthesizer
    ): AgentHarnessEngine {
        return AgentHarnessEngine(
            context = context,
            toolBridge = toolBridge,
            toolRegistry = toolRegistry,
            gateChecker = gateChecker,
            correctionPlanner = correctionPlanner,
            logger = logger,
            planGenerator = planGenerator,
            synthesizer = synthesizer
        )
    }
}

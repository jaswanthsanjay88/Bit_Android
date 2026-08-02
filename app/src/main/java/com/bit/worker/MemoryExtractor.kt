package com.bit.worker

import android.util.Log
import com.bit.models.table_schema.AiMemory
import com.bit.repo.ums.UmsMemoryRepository
import kotlin.math.exp
import kotlin.math.min

/**
 * Manages the AI memory lifecycle: staleness detection and cleanup.
 */
class MemoryExtractor(
    private val memoryRepo: UmsMemoryRepository
) {
    companion object {
        private const val TAG = "MemoryExtractor"
        private const val DEFAULT_DECAY_RATE = 0.01f

        private val DECAY_RATE_BY_CATEGORY = mapOf(
            com.bit.models.table_schema.MemoryCategory.PERSONAL   to 0.002f, // ~347-day half-life: identity & personal facts
            com.bit.models.table_schema.MemoryCategory.PREFERENCE to 0.005f, // ~139-day half-life: preferences
            com.bit.models.table_schema.MemoryCategory.WORK       to 0.010f, // ~69-day half-life: work/project context
            com.bit.models.table_schema.MemoryCategory.INTEREST   to 0.015f, // ~46-day half-life: hobbies & interests
            com.bit.models.table_schema.MemoryCategory.GENERAL    to 0.030f  // ~23-day half-life: transient chat details
        )
    }

    /**
     * Compute memory strength for display/pruning purposes.
     * strength = recency_factor * access_factor
     */
    fun computeStrength(memory: AiMemory): Float {
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val daysSinceAccess = ((now - memory.lastAccessedAt.coerceAtLeast(memory.updatedAt)).toFloat() / dayMs).coerceAtLeast(0f)
        val daysSinceCreated = ((now - memory.createdAt).toFloat() / dayMs).coerceAtLeast(0f)
        
        val lambda = DECAY_RATE_BY_CATEGORY[memory.category] ?: DEFAULT_DECAY_RATE
        val recencyFactor = exp(-lambda * daysSinceAccess)
        
        // Brand new memories (< 7 days) receive a 1.0 access multiplier grace period
        val accessFactor = if (daysSinceCreated < 7f && memory.accessCount == 0) {
            1.0f
        } else {
            min(1f, memory.accessCount / 5f).coerceAtLeast(0.3f)
        }
        
        return recencyFactor * accessFactor
    }

    /**
     * Check if a memory is considered stale (strength < 0.2).
     */
    fun isStale(memory: AiMemory): Boolean {
        return computeStrength(memory) < 0.2f
    }

    /**
     * Delete all stale memories (strength < 0.2).
     * Returns count of deleted memories.
     */
    suspend fun clearStaleMemories(): Int {
        val allMemories = memoryRepo.getAllOnce()
        val stale = allMemories.filter { isStale(it) }
        for (memory in stale) {
            memoryRepo.delete(memory)
        }
        Log.d(TAG, "Cleared ${stale.size} stale memories")
        return stale.size
    }
}

package com.urlinspector.data.reputation

import com.urlinspector.core.model.ReputationResult
import java.time.Instant

class ReputationCache(
    private val maxSize: Int = 200,
    private val ttlMillis: Long = 10 * 60 * 1000,
) {
    private data class Entry(val result: ReputationResult, val cachedAt: Instant)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(key: String, now: Instant): ReputationResult? {
        val entry = entries[key] ?: return null
        val age = now.toEpochMilli() - entry.cachedAt.toEpochMilli()
        if (age > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.result
    }

    @Synchronized
    fun put(key: String, result: ReputationResult, now: Instant) {
        entries[key] = Entry(result, now)
        if (entries.size > maxSize) {
            val oldestKey = entries.keys.iterator().next()
            entries.remove(oldestKey)
        }
    }
}

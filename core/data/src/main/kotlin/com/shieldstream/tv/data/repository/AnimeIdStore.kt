package com.shieldstream.tv.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight persisted index of Douban subject IDs known to belong to Anime / Animation
 * so the Anime tab can surface its own Continue Watching, Up Next, New Episodes, and My List rows
 * without changing the underlying WatchProgress storage format.
 */
@Singleton
class AnimeIdStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ids: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(prefs.getStringSet(KEY_IDS, emptySet()).orEmpty())
    }

    fun contains(id: String): Boolean = id in ids

    fun mark(id: String) {
        if (id.isBlank()) return
        if (ids.add(id)) persist()
    }

    fun markAll(newIds: Iterable<String>) {
        var changed = false
        for (id in newIds) {
            if (id.isNotBlank() && ids.add(id)) changed = true
        }
        if (changed) persist()
    }

    private fun persist() {
        runCatching {
            // Snapshot to HashSet because SharedPreferences expects an immutable copy.
            prefs.edit().putStringSet(KEY_IDS, HashSet(ids)).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "anime_ids"
        const val KEY_IDS = "ids"
    }
}

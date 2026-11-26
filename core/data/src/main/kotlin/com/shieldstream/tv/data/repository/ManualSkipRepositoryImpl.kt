package com.shieldstream.tv.data.repository

import android.content.Context
import com.shieldstream.tv.domain.ManualSkipRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** SharedPreferences-backed [ManualSkipRepository]. */
@Singleton
class ManualSkipRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ManualSkipRepository {
    private val prefs = context.getSharedPreferences("manual_skip", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    override fun get(key: String): ManualSkipRepository.Config {
        val resolvedKey = resolveStoredKey(key)
        return ManualSkipRepository.Config(
            enabled = prefs.getBoolean("${resolvedKey}_enabled", false),
            introSec = prefs.getInt("${resolvedKey}_intro", 0),
            outroSec = prefs.getInt("${resolvedKey}_outro", 0),
        )
    }

    private fun resolveStoredKey(normalizedKey: String): String {
        if (prefs.contains("${normalizedKey}_enabled") ||
            prefs.contains("${normalizedKey}_intro") ||
            prefs.contains("${normalizedKey}_outro")
        ) {
            return normalizedKey
        }
        val colon = normalizedKey.indexOf(':')
        if (colon <= 0) return normalizedKey
        val type = normalizedKey.substring(0, colon)
        val normTitle = ManualSkipRepository.normalizeTitle(normalizedKey.substring(colon + 1))
        for (rawKey in prefs.all.keys) {
            val base = when {
                rawKey.endsWith("_enabled") -> rawKey.removeSuffix("_enabled")
                rawKey.endsWith("_intro") -> rawKey.removeSuffix("_intro")
                rawKey.endsWith("_outro") -> rawKey.removeSuffix("_outro")
                else -> continue
            }
            val rawColon = base.indexOf(':')
            if (rawColon <= 0) continue
            if (base.substring(0, rawColon) == type &&
                ManualSkipRepository.normalizeTitle(base.substring(rawColon + 1)) == normTitle
            ) {
                return base
            }
        }
        return normalizedKey
    }

    override fun save(key: String, config: ManualSkipRepository.Config) {
        prefs.edit()
            .putBoolean("${key}_enabled", config.enabled)
            .putInt("${key}_intro", config.introSec)
            .putInt("${key}_outro", config.outroSec)
            .commit()
        _changes.value += 1
    }
}

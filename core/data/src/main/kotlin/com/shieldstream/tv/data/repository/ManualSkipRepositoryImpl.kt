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

    override fun get(key: String): ManualSkipRepository.Config = ManualSkipRepository.Config(
        enabled = prefs.getBoolean("${key}_enabled", false),
        introSec = prefs.getInt("${key}_intro", 0),
        outroSec = prefs.getInt("${key}_outro", 0),
    )

    override fun save(key: String, config: ManualSkipRepository.Config) {
        prefs.edit()
            .putBoolean("${key}_enabled", config.enabled)
            .putInt("${key}_intro", config.introSec)
            .putInt("${key}_outro", config.outroSec)
            .apply()
        _changes.value += 1
    }
}

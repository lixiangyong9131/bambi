package com.shieldstream.tv.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Modified build: per-show manual intro/outro skip configuration.
 * One intro length and one outro length apply to every episode of a show,
 * for catalogs that have no chapter metadata (Douban/CMS).
 */
interface ManualSkipRepository {
    data class Config(val enabled: Boolean = false, val introSec: Int = 0, val outroSec: Int = 0) {
        val active: Boolean get() = enabled && (introSec > 0 || outroSec > 0)
    }

    val changes: StateFlow<Int>
    fun get(key: String): Config
    fun save(key: String, config: Config)

    companion object {
        fun keyFor(title: String, type: String): String = "${type}:${title}"
    }
}

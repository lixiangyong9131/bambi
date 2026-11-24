package com.shieldstream.tv.home

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Offline pinyin IME engine for the TV search keyboard.
 *
 * The dictionary (assets/pinyin_dict.txt) is generated offline from
 * pinyin-data (kMandarin_8105 + phrase-pinyin-data) joined with jieba word
 * frequencies, so every entry fits on one TSV line:
 *
 *   S <syllable> <char>,<char>,...   single characters, most frequent first
 *   W <pinyinkey> <word>,<word>,...  words, most frequent first
 *
 * Letters typed on the keyboard form a pinyin buffer ("guyu"). [candidates]
 * segments the buffer into syllables and produces Chinese candidates, exactly
 * like a phone IME: words first (exact full-pinyin match), then the last
 * syllable's characters, then a greedy per-syllable composition.
 */
internal object PinyinEngine {

    private const val TAG = "PinyinEngine"

    /** Longest syllable in the dictionary ("zhuang"/"chuang"). */
    private const val MAX_SYLLABLE = 6

    /** Whole-dictionary cap for one buffer; a TV remote never needs more. */
    private const val MAX_CANDIDATES = 60

    @Volatile
    private var loaded = false

    /** syllable -> characters, ordered by frequency. */
    private val syllables = HashMap<String, List<String>>(512)

    /** full pinyin key -> words, ordered by frequency. */
    private val words = HashMap<String, List<String>>(48_000)

    /** key length -> word keys, so prefix lookup never scans all 47k entries. */
    private val keysByLength = HashMap<Int, List<String>>(8)

    /** Sorted list of all syllable keys, used for greedy segmentation. */
    @Volatile
    private var syllableKeys: List<String> = emptyList()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { load(context) }
                .onFailure { android.util.Log.e(TAG, "load failed", it) }
            loaded = true
        }
    }

    private fun load(context: Context) {
        val t0 = System.currentTimeMillis()
        var chars = 0
        var wordCount = 0
        context.assets.open("pinyin_dict.txt").use { raw ->
            BufferedReader(InputStreamReader(raw, Charsets.UTF_8), 1 shl 16).forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val t1 = line.indexOf('\t')
                if (t1 < 0) return@forEachLine
                val t2 = line.indexOf('\t', t1 + 1)
                if (t2 < 0) return@forEachLine
                val key = line.substring(t1 + 1, t2)
                val payload = line.substring(t2 + 1)
                if (payload.isEmpty()) return@forEachLine
                val items = payload.split(',')
                when (line[0]) {
                    'S' -> { syllables[key] = items; chars += items.size }
                    'W' -> { words[key] = items; wordCount += items.size }
                }
            }
        }
        syllableKeys = syllables.keys.sortedByDescending { it.length }
        val grouped = words.keys.groupBy { it.length }
        keysByLength.putAll(grouped)
        android.util.Log.i(
            TAG,
            "loaded ${syllables.size} syllables / $chars chars, " +
                "${words.size} word keys / $wordCount words in ${System.currentTimeMillis() - t0}ms",
        )
    }

    /**
     * Candidates for a raw letter buffer ("guyu"). Returns Chinese strings; an
     * empty list means the buffer cannot be segmented into known syllables.
     */
    fun candidates(context: Context, buffer: String): List<String> {
        ensureLoaded(context)
        val q = buffer.lowercase().filter { it in 'a'..'z' }
        if (q.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()

        // A complete single syllable ("gu") is almost always the user wanting one
        // character, so single chars go first. For multi-syllable input ("guyu") the
        // word match is the better guess and words come first.
        val isSingleSyllable = syllables.containsKey(q)

        if (isSingleSyllable) {
            syllables[q]?.let { out += it }
            words[q]?.let { out += it }
        } else {
            // 1. Exact full-pinyin word match: "guyu" -> 谷雨.
            words[q]?.let { out += it }
        }

        // 2. Prefix word matches ("guyu" while typing "guyuan"), words first.
        // Scan only keys of plausible length: a prefix match must have length in
        // [q.length, q.length + 3], otherwise it is not a useful completion.
        if (out.size < MAX_CANDIDATES / 2) {
            outer@ for (len in q.length + 1..q.length + 3) {
                val bucket = keysByLength[len] ?: continue
                for (key in bucket) {
                    if (key.startsWith(q)) {
                        out += words[key].orEmpty()
                        if (out.size >= MAX_CANDIDATES / 2) break@outer
                    }
                }
            }
        }

        // 3. The trailing syllable's characters: typing "guyu" still offers 雨...
        if (!isSingleSyllable) {
            val lastSyllable = longestSuffix(q)
            if (lastSyllable != null) {
                syllables[lastSyllable]?.let { out += it }
            }
        }

        // 4. Greedy segmentation of the whole buffer -> one char per syllable.
        segmented(q)?.let { parts ->
            if (parts.size > 1) {
                val composed = parts.mapNotNull { syllables[it]?.firstOrNull() }.joinToString("")
                if (composed.length == parts.size) out += composed
                // Per-syllable alternatives are offered separately by the caller
                // as the buffer grows, so don't explode the list here.
            }
        }

        return out.take(MAX_CANDIDATES)
    }

    /** Longest dictionary syllable that [q] ends with, e.g. "xiaoxiang" -> "xiang". */
    private fun longestSuffix(q: String): String? {
        val max = minOf(MAX_SYLLABLE, q.length)
        for (len in max downTo 1) {
            val tail = q.substring(q.length - len)
            if (syllables.containsKey(tail)) return tail
        }
        return null
    }

    /** Greedy longest-match segmentation; null if any part is unknown. */
    private fun segmented(q: String): List<String>? {
        val parts = ArrayList<String>(4)
        var i = 0
        while (i < q.length) {
            var matched: String? = null
            val limit = minOf(MAX_SYLLABLE, q.length - i)
            for (len in limit downTo 1) {
                val sub = q.substring(i, i + len)
                if (syllables.containsKey(sub)) { matched = sub; break }
            }
            if (matched == null) return null
            parts += matched
            i += matched.length
        }
        return parts
    }

    /** True when [text] contains any CJK character. */
    fun hasChinese(text: String): Boolean = text.any { it.code in 0x4E00..0x9FFF }
}

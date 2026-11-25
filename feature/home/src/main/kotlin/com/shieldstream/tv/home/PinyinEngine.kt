package com.shieldstream.tv.home

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Arrays

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

    /** Maximum word key length stored in O(log N) length buckets. */
    private const val MAX_BUCKET_LEN = 32

    /** Whole-dictionary cap for one buffer; a TV remote never needs more. */
    private const val MAX_CANDIDATES = 60

    private const val CACHE_SIZE = 64

    @Volatile
    private var loaded = false

    /** syllable -> characters, ordered by frequency. */
    private val syllables = HashMap<String, List<String>>(512)

    /** Sorted keys + raw comma-separated payloads grouped by key length for O(log N) prefix search. */
    private class WordBucket(val keys: Array<String>, val payloads: Array<String>)

    @Volatile
    private var wordBuckets: Array<WordBucket?> = arrayOfNulls(MAX_BUCKET_LEN + 1)

    /** Recent buffer -> candidate cache so backspacing and repeated typing are instant. */
    private val recentCache = object : LinkedHashMap<String, List<String>>(CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean = size > CACHE_SIZE
    }

    fun isLoaded(): Boolean = loaded

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                context.assets.open("pinyin_dict.txt").use { raw -> loadFromStream(raw) }
            }.onFailure { android.util.Log.e(TAG, "load failed", it) }
            loaded = true
        }
    }

    internal fun loadFromStream(raw: InputStream) {
        val t0 = System.currentTimeMillis()
        var chars = 0
        var wordKeysCount = 0
        val tempSyllables = HashMap<String, List<String>>(512)
        val byLen = Array<ArrayList<Pair<String, String>>?>(MAX_BUCKET_LEN + 1) { null }

        BufferedReader(InputStreamReader(raw, Charsets.UTF_8), 1 shl 16).forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            val t1 = line.indexOf('\t')
            if (t1 < 0) return@forEachLine
            val t2 = line.indexOf('\t', t1 + 1)
            if (t2 < 0) return@forEachLine
            val key = line.substring(t1 + 1, t2)
            val payload = line.substring(t2 + 1)
            if (payload.isEmpty()) return@forEachLine
            when (line[0]) {
                'S' -> {
                    val items = payload.split(',')
                    tempSyllables[key] = items
                    chars += items.size
                }
                'W' -> {
                    val len = key.length
                    if (len in 1..MAX_BUCKET_LEN) {
                        val list = byLen[len] ?: ArrayList<Pair<String, String>>(64).also { byLen[len] = it }
                        list.add(key to payload)
                        wordKeysCount++
                    }
                }
            }
        }

        val builtBuckets = arrayOfNulls<WordBucket>(MAX_BUCKET_LEN + 1)
        for (len in 1..MAX_BUCKET_LEN) {
            val list = byLen[len] ?: continue
            list.sortWith(compareBy { it.first })
            val keys = Array(list.size) { list[it].first }
            val payloads = Array(list.size) { list[it].second }
            builtBuckets[len] = WordBucket(keys, payloads)
        }

        synchronized(recentCache) { recentCache.clear() }
        syllables.clear()
        syllables.putAll(tempSyllables)
        wordBuckets = builtBuckets
        loaded = true

        android.util.Log.i(
            TAG,
            "loaded ${syllables.size} syllables / $chars chars, " +
                "$wordKeysCount word keys in ${System.currentTimeMillis() - t0}ms",
        )
    }

    private fun lookupExactWords(key: String): List<String>? {
        val bucket = wordBuckets.getOrNull(key.length) ?: return null
        val idx = Arrays.binarySearch(bucket.keys, key)
        if (idx < 0) return null
        return bucket.payloads[idx].split(',')
    }

    /**
     * Candidates for a raw letter buffer ("guyu"). Returns Chinese strings; an
     * empty list means the buffer cannot be segmented into known syllables.
     */
    fun candidates(context: Context, buffer: String): List<String> {
        ensureLoaded(context)
        return candidatesFromLoaded(buffer)
    }

    internal fun candidatesFromLoaded(buffer: String): List<String> {
        val q = normalizeLetters(buffer)
        if (q.isEmpty()) return emptyList()

        synchronized(recentCache) {
            recentCache[q]?.let { return it }
        }

        val out = LinkedHashSet<String>()

        // A complete single syllable ("gu") is almost always the user wanting one
        // character, so single chars go first. For multi-syllable input ("guyu") the
        // word match is the better guess and words come first.
        val isSingleSyllable = syllables.containsKey(q)

        if (isSingleSyllable) {
            syllables[q]?.let { out += it }
            lookupExactWords(q)?.let { out += it }
        } else {
            // 1. Exact full-pinyin word match: "guyu" -> 谷雨.
            lookupExactWords(q)?.let { out += it }
        }

        // 2. Prefix word matches ("guyu" while typing "guyuan"), words first.
        // Binary-search only buckets of plausible length [q.length + 1, q.length + 3] in O(log N).
        if (out.size < MAX_CANDIDATES / 2) {
            val maxLen = minOf(q.length + 3, MAX_BUCKET_LEN)
            outer@ for (len in (q.length + 1)..maxLen) {
                val bucket = wordBuckets.getOrNull(len) ?: continue
                var pos = Arrays.binarySearch(bucket.keys, q)
                if (pos < 0) pos = -pos - 1
                while (pos < bucket.keys.size) {
                    val key = bucket.keys[pos]
                    if (!key.startsWith(q)) break
                    out += bucket.payloads[pos].split(',')
                    if (out.size >= MAX_CANDIDATES / 2) break@outer
                    pos++
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
            }
        }

        val result = out.take(MAX_CANDIDATES)
        synchronized(recentCache) {
            recentCache[q] = result
        }
        return result
    }

    private fun normalizeLetters(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val low = ch.lowercaseChar()
            if (low in 'a'..'z') sb.append(low)
        }
        return sb.toString()
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

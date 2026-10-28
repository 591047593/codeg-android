package app.codeg.android.core.designsystem.markdown

/**
 * Bounded LRU cache for parsed Markdown blocks, used ONLY by the persisted node-build
 * path. A long assistant message is split into per-block timeline nodes at build time,
 * and the transcript re-builds whenever `ui.turns` changes (send / completion /
 * reattach) — without a cache that means re-scanning every persisted message's markdown
 * on the main thread each time. Keyed by the message text (stable → String.hashCode is
 * JVM-cached after the first lookup), so unchanged messages are parsed once.
 *
 * The growing *live* text is deliberately NOT cached here (it flows through
 * [LiveBlockParser] instead); a per-flush key would churn and evict useful persisted
 * entries. Access is single-threaded in practice (the main-thread node build), guarded
 * with `@Synchronized` as cheap insurance.
 */
object MarkdownCache {
    /**
     * Floor on the entry count — but only while the character budget allows it.
     *
     * A handful of tiny messages should never be evicted just to make room for nothing;
     * on the other hand the floor must not be able to keep the cache over budget, so once
     * the characters exceed [MAX_CHARS] even the floor yields.
     */
    private const val MIN_ENTRIES = 32

    /**
     * Ceiling on the *characters* held by cached keys.
     *
     * The cache is keyed by the full message text, and each value repeats that text
     * inside its [MarkdownBlock]s, so the real cost is roughly twice the key length.
     * Bounding by entry count alone meant 256 stored replies of 200 KB held ~100 MB for
     * the life of the process — on a heap this app has already had to raise once for
     * transcripts. 4 MB of keys (~8 MB with the block copies) is far more than the
     * visible window needs while staying well inside the budget.
     */
    private const val MAX_CHARS = 4 * 1024 * 1024

    // Plain access-ordered map: eviction is driven entirely by [prune], which is the only
    // place that can keep the character total accurate. Letting `removeEldestEntry` evict
    // as well would drop entries whose length was never subtracted from the total.
    private val blockCache = LinkedHashMap<String, List<MarkdownBlock>>(16, 0.75f, /* accessOrder = */ true)

    /** Running total of key lengths, so eviction does not have to re-walk the map. */
    private var cachedChars = 0

    /** Block-split [raw], memoized. For stable (persisted) text only — NOT the live tail. */
    @Synchronized
    fun blocks(raw: String): List<MarkdownBlock> {
        blockCache[raw]?.let { return it }
        val parsed = parseMarkdownBlocks(raw)
        blockCache[raw] = parsed
        cachedChars += raw.length
        // Checked after every insert, so a single very large message cannot sit over the
        // budget until some later entry happens to trigger eviction.
        prune()
        return parsed
    }

    /**
     * Drop least-recently-used entries until the character budget is satisfied.
     *
     * The [MIN_ENTRIES] floor applies only while it can be honoured without staying over
     * budget: with a handful of very large messages the floor has to give way, otherwise
     * a cache holding only 20 entries could sit at several times the intended size.
     */
    private fun prune() {
        if (cachedChars <= MAX_CHARS) return
        val it = blockCache.entries.iterator()
        while (cachedChars > MAX_CHARS && blockCache.size > MIN_ENTRIES && it.hasNext()) {
            val entry = it.next()
            cachedChars -= entry.key.length
            it.remove()
        }
        // Still over budget with the floor reached: keep evicting, down to a single entry.
        val it2 = blockCache.entries.iterator()
        while (cachedChars > MAX_CHARS && blockCache.size > 1 && it2.hasNext()) {
            val entry = it2.next()
            cachedChars -= entry.key.length
            it2.remove()
        }
    }
}

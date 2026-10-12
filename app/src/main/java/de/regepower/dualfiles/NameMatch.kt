package de.regepower.dualfiles

/**
 * Name filter: plain text is found anywhere in the name; with * (any run) or ? (one character) the
 * pattern must cover the whole name, like "*.jpg" or "IMG_20??*". Fuzzy: the text may appear with a few
 * typos (one per four letters, at most two), e.g. "fotto" finds "Fotos". Names come lower-cased.
 */
internal object NameMatch {
    fun matcher(query: String, fuzzy: Boolean): (String) -> Boolean {
        val q = query.lowercase()
        if (q.isEmpty()) return { true }
        if ('*' in q || '?' in q) {
            val re = buildString {
                var plain = StringBuilder()
                fun flush() {
                    if (plain.isNotEmpty()) append(Regex.escape(plain.toString()))
                    plain = StringBuilder()
                }
                for (c in q) when (c) {
                    '*' -> { flush(); append(".*") }
                    '?' -> { flush(); append('.') }
                    else -> plain.append(c)
                }
                flush()
            }.toRegex(RegexOption.DOT_MATCHES_ALL)
            return { re.matches(it) }
        }
        val typos = if (fuzzy) minOf(2, q.length / 4) else 0
        if (typos == 0) return { it.contains(q) }
        return { near(q, it, typos) }
    }

    /** Whether [q] occurs somewhere in [s] with at most [k] edits (Sellers: edit distance to any substring). */
    private fun near(q: String, s: String, k: Int): Boolean {
        val m = q.length
        var prev = IntArray(m + 1) { it }   // column for the empty prefix of s: free start anywhere
        var cur = IntArray(m + 1)
        if (prev[m] <= k) return true
        for (c in s) {
            cur[0] = 0
            for (i in 1..m) {
                val sub = prev[i - 1] + if (q[i - 1] == c) 0 else 1
                cur[i] = minOf(sub, prev[i] + 1, cur[i - 1] + 1)
            }
            if (cur[m] <= k) return true
            val t = prev; prev = cur; cur = t
        }
        return false
    }
}

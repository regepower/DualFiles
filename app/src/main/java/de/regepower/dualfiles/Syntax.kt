package de.regepower.dualfiles

/**
 * Small syntax colouring for the viewer, one line at a time (a comment or string that spans several lines
 * is coloured only where it starts or ends on the line): keywords, strings, numbers, comments, tags and
 * keys for the most common text formats. No library, a few KB.
 */
internal object Syntax {
    const val KEYWORD = 0
    const val STRING = 1
    const val NUMBER = 2
    const val COMMENT = 3
    const val TAG = 4
    const val KEY = 5

    enum class Lang { C, CSS, PY, SH, JSON, XML, INI, YAML, SQL, MD }

    fun langFor(name: String): Lang? = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java", "c", "h", "cpp", "cc", "hpp", "js", "mjs", "ts", "tsx", "jsx", "cs", "go", "rs",
        "swift", "dart", "gradle", "groovy", "scala", "php" -> Lang.C
        "css", "scss", "less" -> Lang.CSS
        "py" -> Lang.PY
        "sh", "bash", "zsh" -> Lang.SH
        "json" -> Lang.JSON
        "xml", "html", "htm", "xhtml", "svg", "plist" -> Lang.XML
        "ini", "conf", "cfg", "properties", "prop", "toml", "env" -> Lang.INI
        "yml", "yaml" -> Lang.YAML
        "sql" -> Lang.SQL
        "md", "markdown" -> Lang.MD
        else -> null
    }

    private val C_WORDS = (
        "abstract as async await break case catch class const continue data default do else enum export extends " +
            "false final finally for fun func function go if impl implements import in interface internal is let match " +
            "mod new null object open override package private protected pub public return sealed self static struct " +
            "super switch this throw throws trait true try type typeof val var void when where while yield int long " +
            "char float double boolean bool byte short unsigned signed sizeof typedef include define ifdef endif"
        ).split(' ').toHashSet()
    private val PY_WORDS = (
        "and as assert async await break class continue def del elif else except False finally for from global if " +
            "import in is lambda None nonlocal not or pass raise return True try while with yield self print"
        ).split(' ').toHashSet()
    private val SH_WORDS = (
        "if then else elif fi for while until do done case esac in function return local export echo exit set " +
            "unset readonly shift source cd test true false"
        ).split(' ').toHashSet()
    private val SQL_WORDS = (
        "select from where and or not insert into values update set delete create table drop alter index on join " +
            "left right inner outer group by order having limit as distinct null is primary key foreign references " +
            "union all exists between like in case when then else end"
        ).split(' ').toHashSet()

    /** Coloured runs of [line] as (start, end, kind) triples, in order and not overlapping. */
    fun spans(lang: Lang, line: String): IntArray {
        val out = IntArrayList()
        when (lang) {
            Lang.XML -> xml(line, out)
            Lang.MD -> md(line, out)
            else -> code(lang, line, out)
        }
        return out.toArray()
    }

    private fun code(lang: Lang, s: String, out: IntArrayList) {
        val n = s.length
        val hashComment = lang == Lang.PY || lang == Lang.SH || lang == Lang.INI || lang == Lang.YAML
        val words = when (lang) {
            Lang.C -> C_WORDS
            Lang.PY -> PY_WORDS
            Lang.SH -> SH_WORDS
            else -> null
        }
        // Key of "key = value" / "key: value" (ini, yaml) or a [section]
        if (lang == Lang.INI || lang == Lang.YAML) {
            val t = s.trimStart()
            val lead = n - t.length
            if (lang == Lang.INI && t.startsWith("[")) {
                out.add(lead, n, TAG)
                return
            }
            val start = if (lang == Lang.YAML && t.startsWith("- ")) lead + 2 else lead
            val sep = s.indexOfAny(if (lang == Lang.INI) charArrayOf('=', ':') else charArrayOf(':'), start)
            if (sep > start && !s.startsWith("#", lead) && !s.startsWith(";", lead) && s.substring(start, sep).none { it == '"' || it == '\'' }) {
                out.add(start, sep, KEY)
                plain(s, sep, n, hashComment, out, null, lang)   // the value: strings, numbers, comments
                return
            }
        }
        plain(s, 0, n, hashComment, out, words, lang)
    }

    /** Comments, strings, numbers and keywords from [from] to [to]. */
    private fun plain(
        s: String, from: Int, to: Int, hashComment: Boolean, out: IntArrayList,
        words: Set<String>? = null, lang: Lang? = null,
    ) {
        var i = from
        val slashComments = lang == Lang.C || lang == Lang.CSS || lang == Lang.JSON
        while (i < to) {
            val c = s[i]
            when {
                (hashComment && c == '#' && (i == 0 || s[i - 1].isWhitespace())) || (lang == Lang.INI && c == ';' && i == s.indexOfFirst { !it.isWhitespace() }) -> {
                    out.add(i, to, COMMENT)
                    return
                }
                lang == Lang.SQL && c == '-' && s.startsWith("--", i) -> {
                    out.add(i, to, COMMENT)
                    return
                }
                slashComments && c == '/' && s.startsWith("//", i) && lang != Lang.CSS -> {
                    out.add(i, to, COMMENT)
                    return
                }
                slashComments && c == '/' && s.startsWith("/*", i) -> {
                    val e = s.indexOf("*/", i + 2).let { if (it < 0) to else it + 2 }
                    out.add(i, e, COMMENT)
                    i = e
                }
                c == '"' || c == '\'' || (c == '`' && lang == Lang.C) -> {
                    var e = i + 1
                    while (e < to && s[e] != c) e += if (s[e] == '\\') 2 else 1
                    e = minOf(e + 1, to)
                    // JSON: a string followed by ':' is a key
                    var k = e
                    while (k < to && s[k] == ' ') k++
                    out.add(i, e, if (lang == Lang.JSON && k < to && s[k] == ':') KEY else STRING)
                    i = e
                }
                c.isDigit() && (i == 0 || !(s[i - 1].isLetterOrDigit() || s[i - 1] == '_')) -> {
                    var e = i + 1
                    while (e < to && (s[e].isLetterOrDigit() || s[e] == '.' || s[e] == '_')) e++
                    out.add(i, e, NUMBER)
                    i = e
                }
                lang == Lang.CSS && c == '#' && i + 1 < to && s[i + 1].isLetterOrDigit() -> {
                    var e = i + 1
                    while (e < to && s[e].isLetterOrDigit()) e++
                    out.add(i, e, NUMBER)
                    i = e
                }
                c == '@' && lang == Lang.C && i + 1 < to && s[i + 1].isLetter() -> {
                    var e = i + 1
                    while (e < to && (s[e].isLetterOrDigit() || s[e] == '_')) e++
                    out.add(i, e, KEY)
                    i = e
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var e = i + 1
                    while (e < to && (s[e].isLetterOrDigit() || s[e] == '_')) e++
                    val w = s.substring(i, e)
                    val hit = when (lang) {
                        Lang.SQL -> w.lowercase() in SQL_WORDS
                        Lang.JSON, Lang.YAML, Lang.INI -> w == "true" || w == "false" || w == "null"
                        else -> words?.contains(w) == true
                    }
                    if (hit) out.add(i, e, KEYWORD)
                    i = e
                }
                else -> i++
            }
        }
    }

    private fun xml(s: String, out: IntArrayList) {
        val n = s.length
        var i = 0
        while (i < n) {
            if (s.startsWith("<!--", i)) {
                val e = s.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                out.add(i, e, COMMENT)
                i = e
            } else if (s[i] == '<') {
                // Tag name, then attributes and their values up to '>'
                var e = i + 1
                while (e < n && (s[e] == '/' || s[e] == '?' || s[e] == '!')) e++
                while (e < n && !s[e].isWhitespace() && s[e] != '>' && s[e] != '/') e++
                out.add(i, e, TAG)
                i = e
                while (i < n && s[i] != '>') {
                    val c = s[i]
                    if (c == '"' || c == '\'') {
                        var q = i + 1
                        while (q < n && s[q] != c) q++
                        q = minOf(q + 1, n)
                        out.add(i, q, STRING)
                        i = q
                    } else if (c.isLetter()) {
                        var q = i + 1
                        while (q < n && (s[q].isLetterOrDigit() || s[q] == '-' || s[q] == ':' || s[q] == '_')) q++
                        out.add(i, q, KEY)
                        i = q
                    } else i++
                }
                if (i < n) {
                    out.add(i, i + 1, TAG)
                    i++
                }
            } else i++
        }
    }

    private fun md(s: String, out: IntArrayList) {
        val t = s.trimStart()
        val lead = s.length - t.length
        when {
            t.startsWith("#") -> out.add(lead, s.length, KEYWORD)
            t.startsWith(">") -> out.add(lead, s.length, COMMENT)
            t.startsWith("```") -> out.add(lead, s.length, STRING)
            else -> {
                if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) out.add(lead, lead + 1, NUMBER)
                var i = 0
                while (i < s.length) {
                    if (s[i] == '`') {
                        val e = s.indexOf('`', i + 1).let { if (it < 0) s.length else it + 1 }
                        out.add(i, e, STRING)
                        i = e
                    } else if (s[i] == '[') {
                        val e = s.indexOf(')', i).let { if (it < 0) s.length else it + 1 }
                        if (s.indexOf("](", i) in i until e) out.add(i, e, TAG)
                        i = e
                    } else i++
                }
            }
        }
    }

    /** Growing int list without boxing. */
    class IntArrayList {
        private var a = IntArray(24)
        private var n = 0
        fun add(start: Int, end: Int, kind: Int) {
            if (end <= start) return
            if (n + 3 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = start
            a[n++] = end
            a[n++] = kind
        }
        fun toArray(): IntArray = a.copyOf(n)
    }
}

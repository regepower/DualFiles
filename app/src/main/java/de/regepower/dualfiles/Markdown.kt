package de.regepower.dualfiles

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * Small Markdown renderer for the viewer (Android has none built in): headings, paragraphs, bold, italic,
 * strike, inline and fenced code, lists, quotes, links, rules and tables (shown as monospaced lines).
 * The result is a list of blocks: wrapped text, code that keeps its lines (shown unwrapped, scrollable
 * sideways) and rules. Framework spans only; no library.
 */
internal object Markdown {
    class Colors(val accent: Int, val muted: Int, val dp: Float)

    /** [code]: monospaced lines, not wrapped. [rule]: a horizontal line (text empty). */
    class Block(val text: CharSequence, val code: Boolean = false, val rule: Boolean = false)

    fun render(src: String, c: Colors): List<Block> {
        val blocks = ArrayList<Block>()
        var out = SpannableStringBuilder()
        // Ends the running text block (without trailing blank lines) and adds [b] after it
        fun push(b: Block?) {
            while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
            if (out.isNotEmpty()) blocks.add(Block(out))
            out = SpannableStringBuilder()
            if (b != null) blocks.add(b)
        }
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flush() {
            if (para.isEmpty()) return
            inline(out, para.toString().trim(), c)
            out.append("\n\n")
            para.setLength(0)
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        code.append(lines[i].replace("\t", "    ")).append('\n')
                        i++
                    }
                    push(Block(code.toString().trimEnd('\n'), code = true))
                }
                t.isEmpty() -> flush()
                t.startsWith("#") && t.trimStart('#').startsWith(" ") -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length.coerceAtMost(6)
                    val start = out.length
                    inline(out, t.drop(level).trim(), c)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(floatArrayOf(1.7f, 1.45f, 1.25f, 1.1f, 1.0f, 0.95f)[level - 1]), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (level <= 2) out.setSpan(ForegroundColorSpan(c.accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append("\n\n")
                }
                t.matches(Regex("([-*_])( *\\1){2,}")) -> {
                    flush()
                    push(Block("", rule = true))
                }
                t.startsWith(">") -> {
                    flush()
                    val start = out.length
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        inline(out, lines[i].trim().removePrefix(">").trim(), c)
                        out.append('\n')
                        i++
                    }
                    out.setSpan(QuoteSpan(c.accent, (3 * c.dp).toInt(), (10 * c.dp).toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(c.muted), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append('\n')
                    continue
                }
                Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").matchEntire(line) != null -> {
                    flush()
                    // A run of list items; indentation gives the level
                    while (i < lines.size) {
                        val m = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").matchEntire(lines[i]) ?: break
                        val depth = m.groupValues[1].replace("\t", "    ").length / 2
                        val mark = m.groupValues[2]
                        val start = out.length
                        var text = m.groupValues[3]
                        val task = Regex("^\\[([ xX])]\\s+(.*)").matchEntire(text)
                        if (task != null) text = (if (task.groupValues[1] == " ") "☐ " else "☑ ") + task.groupValues[2]
                        if (mark[0].isDigit()) {
                            out.append(mark.dropLast(1)).append(". ")
                            inline(out, text, c)
                            out.setSpan(LeadingMarginSpan.Standard(((8 + depth * 16) * c.dp).toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else {
                            inline(out, text, c)
                            out.setSpan(LeadingMarginSpan.Standard(((depth * 16) * c.dp).toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out.setSpan(BulletSpan((8 * c.dp).toInt(), c.accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        out.append('\n')
                        i++
                    }
                    out.append('\n')
                    continue
                }
                t.startsWith("|") -> {
                    flush()
                    // Tables: one monospaced row per line, columns padded to the same width, separator rows left out
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i].trim()
                        if (!row.matches(Regex("[|:\\- ]+"))) rows.add(row.trim('|').split('|').map { it.trim() })
                        i++
                    }
                    val widths = IntArray(rows.maxOfOrNull { it.size } ?: 0)
                    for (r in rows) for ((k, cell) in r.withIndex()) widths[k] = maxOf(widths[k], cell.length)
                    val table = rows.joinToString("\n") { r -> r.withIndex().joinToString(" │ ") { (k, cell) -> cell.padEnd(widths[k]) }.trimEnd() }
                    push(Block(table, code = true))
                    continue
                }
                else -> para.append(t).append(if (line.endsWith("  ")) "\n" else " ")
            }
            i++
        }
        flush()
        push(null)
        return blocks
    }

    /** Inline `code`: monospaced, no background (a background per line looked patchy). */
    private fun code(out: SpannableStringBuilder, start: Int) {
        if (out.length <= start) return
        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Bold, italic, strike, `code`, links and images inside one block of text. */
    private fun inline(out: SpannableStringBuilder, s: String, c: Colors) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\\' && i + 1 < s.length -> {
                    out.append(s[i + 1])
                    i += 2
                }
                ch == '`' -> {
                    val e = s.indexOf('`', i + 1)
                    if (e < 0) {
                        out.append(ch)
                        i++
                    } else {
                        val start = out.length
                        out.append(s, i + 1, e)
                        code(out, start)
                        i = e + 1
                    }
                }
                (ch == '*' || ch == '_' || ch == '~') && i + 1 < s.length && s[i + 1] == ch -> {
                    val mark = s.substring(i, i + 2)
                    val e = s.indexOf(mark, i + 2)
                    if (e < 0) {
                        out.append(mark)
                        i += 2
                    } else {
                        val start = out.length
                        inline(out, s.substring(i + 2, e), c)
                        out.setSpan(if (ch == '~') StrikethroughSpan() else StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = e + 2
                    }
                }
                (ch == '*' || ch == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() &&
                    (ch == '*' || i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val e = s.indexOf(ch, i + 1)
                    if (e < 0 || (ch == '_' && e + 1 < s.length && s[e + 1].isLetterOrDigit())) {
                        out.append(ch)
                        i++
                    } else {
                        val start = out.length
                        inline(out, s.substring(i + 1, e), c)
                        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = e + 1
                    }
                }
                (ch == '[' || (ch == '!' && i + 1 < s.length && s[i + 1] == '[')) -> {
                    val img = ch == '!'
                    val open = if (img) i + 1 else i
                    val close = s.indexOf("](", open)
                    val end = if (close < 0) -1 else s.indexOf(')', close + 2)
                    if (close < 0 || end < 0) {
                        out.append(ch)
                        i++
                    } else {
                        val label = s.substring(open + 1, close)
                        val url = s.substring(close + 2, end).trim().substringBefore(' ')
                        val start = out.length
                        if (img) out.append("🖼 ").append(label.ifEmpty { url }) else inline(out, label, c)
                        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                            out.setSpan(URLSpan(url), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        i = end + 1
                    }
                }
                ch == '<' && (s.startsWith("<http", i)) -> {
                    val e = s.indexOf('>', i)
                    if (e < 0) {
                        out.append(ch)
                        i++
                    } else {
                        val url = s.substring(i + 1, e)
                        val start = out.length
                        out.append(url)
                        out.setSpan(URLSpan(url), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = e + 1
                    }
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
    }
}

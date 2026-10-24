package id.localai.app.ui.markdown

/**
 * Parser markdown ringan untuk jawaban model. Sengaja tanpa dependensi Android/Compose supaya
 * bisa diuji di JVM biasa. Toleran terhadap teks yang belum lengkap (streaming): pembuka tanpa
 * penutup ditampilkan apa adanya, blok kode yang belum ditutup tetap dirender sebagai kode.
 */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val code: String) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
}

enum class MdAlign { START, CENTER, END }

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
    data class Table(
        val header: List<List<MdInline>>,
        val aligns: List<MdAlign>,
        val rows: List<List<List<MdInline>>>
    ) : MdBlock
}

object Markdown {

    // ---------------------------------------------------------------- publik

    fun parse(src: String): List<MdBlock> =
        parseBlocks(src.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n'))

    /** Hanya mengubah URL polos menjadi tautan (untuk teks pengguna yang tidak diformat). */
    fun linkifyPlain(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        var last = 0
        var i = 0
        while (i < text.length) {
            val url = if (startsUrl(text, i)) readBareUrl(text, i) else null
            if (url != null) {
                if (i > last) out.add(MdInline.Text(text.substring(last, i)))
                out.add(MdInline.Link(listOf(MdInline.Text(url)), url))
                i += url.length
                last = i
            } else i++
        }
        if (last < text.length) out.add(MdInline.Text(text.substring(last)))
        return out
    }

    /** Hanya http(s) dan mailto yang boleh diklik; skema lain (javascript:, intent:, file:) ditolak. */
    fun isSafeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:")
    }

    // ---------------------------------------------------------------- blok

    private val FENCE = Regex("^(`{3,}|~{3,})(.*)$")
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)(?:\\s+#+)?\\s*$")
    private val RULE = Regex("^(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
    private val LIST = Regex("^( *)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    private fun indentOf(line: String): Int = line.length - line.trimStart(' ').length

    private fun isBlank(line: String) = line.isBlank()

    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        val t = line.trim()
        if (t.isEmpty()) return true
        val ts = line.trimStart()
        if (FENCE.matches(ts)) return true
        if (HEADING.matches(t)) return true
        if (RULE.matches(t)) return true
        if (ts.startsWith(">")) return true
        if (LIST.matches(line)) return true
        return isTableStart(lines, i)
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        i + 1 < lines.size && lines[i].contains('|') && lines[i + 1].contains('-') &&
            TABLE_SEP.matches(lines[i + 1]) && (lines[i + 1].contains('|') || lines[i].trim().startsWith("|"))

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty()) { i++; continue }
            val ts = line.trimStart()

            // Blok kode berpagar
            val fenceM = FENCE.matchEntire(ts)
            if (fenceM != null) {
                val m = fenceM
                val marker = m.groupValues[1]
                val lang = m.groupValues[2].trim().takeWhile { !it.isWhitespace() }
                val openIndent = indentOf(line)
                val body = mutableListOf<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    val l = lines[j]
                    val lt = l.trim()
                    if (lt.length >= marker.length && lt.all { it == marker[0] }) { closed = true; j++; break }
                    body.add(l.drop(minOf(openIndent, indentOf(l))))
                    j++
                }
                out.add(MdBlock.Code(lang, body.joinToString("\n"), closed))
                i = j
                continue
            }

            val headM = HEADING.matchEntire(t)
            if (headM != null) {
                out.add(MdBlock.Heading(headM.groupValues[1].length, parseInline(headM.groupValues[2])))
                i++
                continue
            }

            if (RULE.matches(t)) { out.add(MdBlock.Rule); i++; continue }

            if (ts.startsWith(">")) {
                val inner = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    inner.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                out.add(MdBlock.Quote(parseBlocks(inner)))
                continue
            }

            if (isTableStart(lines, i)) {
                val header = splitRow(lines[i])
                val aligns = splitRow(lines[i + 1]).map { c ->
                    val s = c.trim()
                    when {
                        s.startsWith(":") && s.endsWith(":") -> MdAlign.CENTER
                        s.endsWith(":") -> MdAlign.END
                        else -> MdAlign.START
                    }
                }
                val cols = header.size
                val rows = mutableListOf<List<List<MdInline>>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    val cells = splitRow(lines[i]).map { parseInline(it) }
                    rows.add((cells + List(maxOf(0, cols - cells.size)) { emptyList<MdInline>() }).take(cols))
                    i++
                }
                out.add(
                    MdBlock.Table(
                        header = header.map { parseInline(it) },
                        aligns = List(cols) { aligns.getOrElse(it) { MdAlign.START } },
                        rows = rows
                    )
                )
                continue
            }

            if (LIST.matches(line)) {
                val (block, next) = parseList(lines, i)
                out.add(block)
                i = next
                continue
            }

            // Paragraf
            val para = mutableListOf<String>()
            while (i < lines.size && (para.isEmpty() || !startsBlock(lines, i))) {
                para.add(lines[i].trim())
                i++
            }
            out.add(MdBlock.Paragraph(parseInline(para.joinToString("\n"))))
        }
        return out
    }

    private fun parseList(lines: List<String>, start: Int): Pair<MdBlock.ListBlock, Int> {
        val first = LIST.matchEntire(lines[start])!!
        val baseIndent = first.groupValues[1].length
        val ordered = first.groupValues[2].first().isDigit()
        val startNumber = if (ordered) first.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1
        val items = mutableListOf<List<MdBlock>>()
        var i = start

        while (i < lines.size) {
            if (isBlank(lines[i])) {
                var k = i
                while (k < lines.size && isBlank(lines[k])) k++
                val m = if (k < lines.size) LIST.matchEntire(lines[k]) else null
                if (m != null && sameList(m, baseIndent, ordered)) { i = k; continue } else break
            }
            val m = LIST.matchEntire(lines[i]) ?: break
            if (!sameList(m, baseIndent, ordered)) break

            val indent = m.groupValues[1].length
            val contentIndent = m.groups[3]!!.range.first
            val itemLines = mutableListOf(m.groupValues[3])
            var j = i + 1
            while (j < lines.size) {
                val l = lines[j]
                if (isBlank(l)) {
                    var k = j
                    while (k < lines.size && isBlank(lines[k])) k++
                    if (k < lines.size && indentOf(lines[k]) > indent) {
                        for (b in j until k) itemLines.add("")
                        j = k
                        continue
                    }
                    break
                }
                val li = indentOf(l)
                if (li <= indent + 1 && LIST.matches(l)) break
                if (li <= indent) break
                itemLines.add(l.drop(minOf(li, contentIndent)))
                j++
            }
            items.add(parseBlocks(itemLines))
            i = j
        }
        return MdBlock.ListBlock(ordered, startNumber, items) to i
    }

    private fun sameList(m: MatchResult, baseIndent: Int, ordered: Boolean): Boolean {
        val indent = m.groupValues[1].length
        val isOrdered = m.groupValues[2].first().isDigit()
        return isOrdered == ordered && indent >= baseIndent - 1 && indent < baseIndent + 2
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { cur.append('|'); i += 2; continue }
            if (c == '|') { cells.add(cur.toString().trim()); cur.clear() } else cur.append(c)
            i++
        }
        cells.add(cur.toString().trim())
        return cells
    }

    // ---------------------------------------------------------------- inline

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"

    fun parseInline(s: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) { out.add(MdInline.Text(buf.toString())); buf.clear() }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    buf.append(s[i + 1]); i += 2
                }

                c == '`' -> {
                    var n = 0
                    while (i + n < s.length && s[i + n] == '`') n++
                    val close = findBacktickClose(s, i + n, n)
                    if (close >= 0) {
                        flush()
                        out.add(MdInline.Code(s.substring(i + n, close).let { if (it.length > 1 && it.startsWith(" ") && it.endsWith(" ")) it.substring(1, it.length - 1) else it }))
                        i = close + n
                    } else { buf.append("`".repeat(n)); i += n }
                }

                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val link = tryLink(s, i + 1)
                    if (link != null) {
                        flush()
                        val label = if (link.label.isBlank()) "gambar" else link.label
                        addLink(out, listOf(MdInline.Text(label)), link.url)
                        i = link.end
                    } else { buf.append(c); i++ }
                }

                c == '[' -> {
                    val link = tryLink(s, i)
                    if (link != null) {
                        flush()
                        addLink(out, parseInline(link.label), link.url)
                        i = link.end
                    } else { buf.append(c); i++ }
                }

                c == '<' -> {
                    val close = s.indexOf('>', i + 1)
                    val inner = if (close > i) s.substring(i + 1, close) else ""
                    if (close > i && inner.none { it.isWhitespace() } && isSafeUrl(inner)) {
                        flush()
                        out.add(MdInline.Link(listOf(MdInline.Text(inner)), inner))
                        i = close + 1
                    } else { buf.append(c); i++ }
                }

                c == '*' || c == '_' -> {
                    var r = 0
                    while (i + r < s.length && s[i + r] == c) r++
                    val after = i + r
                    val canOpen = after < s.length && !s[after].isWhitespace() &&
                        (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit())
                    val n = r
                    val close = if (canOpen && r <= 3) findClose(s, after, c, n) else -1
                    if (close < 0) { buf.append(s, i, after); i = after } else {
                        flush()
                        val inner = parseInline(s.substring(after, close))
                        out.add(
                            when (n) {
                                1 -> MdInline.Italic(inner)
                                2 -> MdInline.Bold(inner)
                                else -> MdInline.Bold(listOf(MdInline.Italic(inner)))
                            }
                        )
                        i = close + n
                    }
                }

                c == '~' && i + 1 < s.length && s[i + 1] == '~' -> {
                    val close = if (i + 2 < s.length && !s[i + 2].isWhitespace()) findClose(s, i + 2, '~', 2) else -1
                    if (close < 0) { buf.append("~~"); i += 2 } else {
                        flush()
                        out.add(MdInline.Strike(parseInline(s.substring(i + 2, close))))
                        i = close + 2
                    }
                }

                startsUrl(s, i) -> {
                    val url = readBareUrl(s, i)
                    if (url != null) {
                        flush()
                        out.add(MdInline.Link(listOf(MdInline.Text(url)), url))
                        i += url.length
                    } else { buf.append(c); i++ }
                }

                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return out
    }

    private fun addLink(out: MutableList<MdInline>, label: List<MdInline>, url: String) {
        if (isSafeUrl(url)) out.add(MdInline.Link(label, url.trim())) else out.addAll(label)
    }

    private fun findBacktickClose(s: String, from: Int, n: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '`') {
                var k = 0
                while (j + k < s.length && s[j + k] == '`') k++
                if (k == n) return j
                j += k
            } else j++
        }
        return -1
    }

    /** Cari penutup delimiter [ch] sepanjang [n] mulai dari [from]; -1 bila tidak ada. */
    private fun findClose(s: String, from: Int, ch: Char, n: Int): Int {
        var j = from
        while (j < s.length) {
            val c = s[j]
            when {
                c == '\\' -> j += 2
                c == '`' -> {
                    var k = 0
                    while (j + k < s.length && s[j + k] == '`') k++
                    val close = findBacktickClose(s, j + k, k)
                    j = if (close >= 0) close + k else j + k
                }
                c == ch -> {
                    var k = 0
                    while (j + k < s.length && s[j + k] == ch) k++
                    val prevOk = j > from && !s[j - 1].isWhitespace()
                    val nextOk = ch != '_' || j + k >= s.length || !s[j + k].isLetterOrDigit()
                    if (n == 1) {
                        if (k == 1 && prevOk && nextOk) return j
                        j += k
                    } else {
                        if (k >= n && prevOk && nextOk) return j
                        j += k
                    }
                }
                else -> j++
            }
        }
        return -1
    }

    private class LinkParse(val label: String, val url: String, val end: Int)

    private fun tryLink(s: String, open: Int): LinkParse? {
        var depth = 0
        var j = open
        var close = -1
        while (j < s.length) {
            val c = s[j]
            if (c == '\\') { j += 2; continue }
            if (c == '[') depth++
            if (c == ']') { depth--; if (depth == 0) { close = j; break } }
            j++
        }
        if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
        var p = close + 2
        while (p < s.length && s[p] == ' ') p++
        val urlStart = p
        val url: String
        if (p < s.length && s[p] == '<') {
            val gt = s.indexOf('>', p)
            if (gt < 0) return null
            url = s.substring(p + 1, gt)
            p = gt + 1
        } else {
            var parens = 0
            while (p < s.length) {
                val c = s[p]
                if (c.isWhitespace()) break
                if (c == '(') parens++
                if (c == ')') { if (parens == 0) break; parens-- }
                p++
            }
            url = s.substring(urlStart, p)
        }
        while (p < s.length && s[p] == ' ') p++
        if (p < s.length && (s[p] == '"' || s[p] == '\'')) {
            val q = s[p]
            val endQ = s.indexOf(q, p + 1)
            if (endQ < 0) return null
            p = endQ + 1
            while (p < s.length && s[p] == ' ') p++
        }
        if (p >= s.length || s[p] != ')' || url.isEmpty()) return null
        return LinkParse(s.substring(open + 1, close), url, p + 1)
    }

    // ---------------------------------------------------------------- URL polos

    private fun startsUrl(s: String, i: Int): Boolean {
        if (s[i] != 'h') return false
        if (i > 0 && s[i - 1].isLetterOrDigit()) return false
        return s.startsWith("https://", i) || s.startsWith("http://", i)
    }

    /** Baca URL polos di posisi [i]; buang tanda baca penutup kalimat. Null bila terlalu pendek. */
    private fun readBareUrl(s: String, i: Int): String? {
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (c.isWhitespace() || c == '<' || c == '>' || c == '"' || c == '`') break
            j++
        }
        var url = s.substring(i, j)
        while (url.isNotEmpty()) {
            val last = url.last()
            val trim = when (last) {
                '.', ',', ';', ':', '!', '?', '*', '_', '~', '\'' -> true
                ')' -> url.count { it == ')' } > url.count { it == '(' }
                ']' -> url.count { it == ']' } > url.count { it == '[' }
                else -> false
            }
            if (trim) url = url.dropLast(1) else break
        }
        val host = url.substringAfter("://", "")
        return if (host.length >= 3 && host.any { it.isLetterOrDigit() }) url else null
    }
}

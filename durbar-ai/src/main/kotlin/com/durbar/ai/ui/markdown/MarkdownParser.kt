package com.durbar.ai.ui.markdown

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Item(val marker: String, val ordered: Boolean, val depth: Int, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class Table(val header: List<String>, val aligns: List<Int>, val rows: List<List<String>>) : MdBlock()
    object Rule : MdBlock()
}

object MarkdownParser {

    private val FENCE = Regex("(`{3,}|~{3,})\\s*([^\\s`]*).*")
    private val HEADING = Regex("(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*")
    private val RULE = Regex("(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,}")
    private val ITEM = Regex("([ \\t]*)([-*+•]|\\d{1,9}[.)])[ \\t]+(.*)")
    private val TASK = Regex("\\[([ xX])\\]\\s+(.*)")
    private val TABLE_SEP = Regex("\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?")
    private val BULLETS = arrayOf("•", "◦", "▪", "▪")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        val indents = ArrayList<Int>()
        var i = 0

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(paragraph.joinToString("\n")))
                paragraph.clear()
                indents.clear()
            }
        }

        fun add(block: MdBlock) {
            flush()
            if (block !is MdBlock.Item) indents.clear()
            blocks.add(block)
        }

        while (i < lines.size) {
            val raw = lines[i].trimEnd()
            val line = raw.trim()

            if (line.isEmpty()) {
                flush()
                i++
                continue
            }

            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2]
                val rest = line.substring(marker.length)
                if (!rest.contains(marker[0])) {
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        if (t.length >= marker.length && t.all { it == marker[0] }) break
                        body.add(lines[i].trimEnd())
                        i++
                    }
                    i++
                    add(MdBlock.Code(lang, body.joinToString("\n")))
                    continue
                }
            }

            if (line.length <= 6 && line.all { it == '#' }) {
                i++
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null && heading.groupValues[2].isNotEmpty()) {
                add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
                i++
                continue
            }

            if (RULE.matches(line)) {
                add(MdBlock.Rule)
                i++
                continue
            }

            if (line.contains('|') && i + 1 < lines.size) {
                val sep = lines[i + 1].trim()
                if (sep.contains('|') && sep.contains('-') && TABLE_SEP.matches(sep)) {
                    val header = splitRow(line)
                    val cols = header.size
                    val aligns = fit(splitRow(sep).map { alignOf(it) }, cols, 0)
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size) {
                        val r = lines[i].trim()
                        if (r.isEmpty() || !r.contains('|')) break
                        rows.add(fit(splitRow(r), cols, ""))
                        i++
                    }
                    add(MdBlock.Table(header, aligns, rows))
                    continue
                }
            }

            if (line.startsWith(">")) {
                val quoted = ArrayList<String>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (!t.startsWith(">")) break
                    quoted.add(t.removePrefix(">").removePrefix(" "))
                    i++
                }
                add(MdBlock.Quote(quoted.joinToString("\n").trim()))
                continue
            }

            val item = ITEM.matchEntire(raw)
            if (item != null) {
                flush()
                val indent = item.groupValues[1].fold(0) { acc, ch -> acc + if (ch == '\t') 4 else 1 }
                while (indents.isNotEmpty() && indent < indents.last()) indents.removeAt(indents.size - 1)
                if (indents.isEmpty() || indent > indents.last()) indents.add(indent)
                val depth = (indents.size - 1).coerceAtMost(3)
                val token = item.groupValues[2]
                val ordered = token.first().isDigit()
                var body = item.groupValues[3]
                var marker = if (ordered) token else BULLETS[depth]
                var isOrdered = ordered
                val task = TASK.matchEntire(body)
                if (task != null) {
                    marker = if (task.groupValues[1] == " ") "☐" else "☑"
                    body = task.groupValues[2]
                    isOrdered = false
                }
                blocks.add(MdBlock.Item(marker, isOrdered, depth, body))
                i++
                continue
            }

            if (paragraph.isEmpty() && blocks.lastOrNull() is MdBlock.Item && raw[0].isWhitespace()) {
                val last = blocks.removeAt(blocks.size - 1) as MdBlock.Item
                blocks.add(last.copy(text = last.text + "\n" + line))
                i++
                continue
            }

            paragraph.add(line)
            i++
        }

        flush()
        return blocks
    }

    private fun splitRow(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') {
                current.append('|')
                k += 2
                continue
            }
            if (c == '|') {
                cells.add(current.toString().trim())
                current.setLength(0)
            } else {
                current.append(c)
            }
            k++
        }
        cells.add(current.toString().trim())
        return cells
    }

    private fun alignOf(cell: String): Int {
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        return when {
            left && right -> 1
            right -> 2
            else -> 0
        }
    }

    private fun <T> fit(list: List<T>, size: Int, filler: T): List<T> {
        return List(size) { index -> if (index < list.size) list[index] else filler }
    }
}
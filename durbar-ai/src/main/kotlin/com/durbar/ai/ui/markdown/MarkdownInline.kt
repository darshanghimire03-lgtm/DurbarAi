package com.durbar.ai.ui.markdown

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.durbar.ai.R

object MarkdownInline {

    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>\$"
    private const val TRAILING = ".,;:!?)]}'\"*_"
    private const val DOLLARS = "\$\$"

    fun style(context: Context, source: String): SpannableStringBuilder {
        val styler = Styler(
            link = ContextCompat.getColor(context, R.color.ai_blue),
            codeFg = ContextCompat.getColor(context, R.color.ai_ink),
            codeBg = ContextCompat.getColor(context, R.color.ai_tint)
        )
        styler.parse(source)
        return styler.out
    }

    private class LinkSpan(private val url: String, private val color: Int) : ClickableSpan() {
        override fun onClick(widget: View) {
            val context = widget.context
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
            }
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }
    }

    private class Styler(
        private val link: Int,
        private val codeFg: Int,
        private val codeBg: Int
    ) {
        val out = SpannableStringBuilder()
        private var inLink = false

        fun parse(s: String) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '$' || c == '\\') {
                    val next = math(s, i)
                    if (next > i) {
                        i = next
                        continue
                    }
                }
                i = when {
                    c == '\\' && i + 1 < s.length && ESCAPABLE.indexOf(s[i + 1]) >= 0 -> {
                        out.append(s[i + 1])
                        i + 2
                    }
                    c == '`' -> code(s, i)
                    c == '[' -> link(s, i)
                    c == '~' && s.startsWith("~~", i) -> emphasis(s, i, "~~")
                    c == '*' || c == '_' -> emphasis(s, i, markerFor(s, i, c))
                    c == 'h' && isUrlStart(s, i) -> bareUrl(s, i)
                    else -> {
                        out.append(c)
                        i + 1
                    }
                }
            }
        }

        private fun math(s: String, i: Int): Int {
            if (s[i] == '$') {
                if (s.startsWith(DOLLARS, i)) {
                    val end = s.indexOf(DOLLARS, i + 2)
                    if (end > i + 2 && appendMath(s.substring(i + 2, end))) return end + 2
                    return -1
                }
                val start = i + 1
                if (start >= s.length || s[start].isWhitespace() || s[start] == '$') return -1
                var j = start
                while (j < s.length) {
                    val k = s.indexOf('$', j)
                    if (k < 0) return -1
                    val before = s[k - 1]
                    val after = if (k + 1 < s.length) s[k + 1] else ' '
                    if (before != '\\' && !before.isWhitespace() && !after.isDigit()) {
                        return if (appendMath(s.substring(start, k))) k + 1 else -1
                    }
                    j = k + 1
                }
                return -1
            }
            if (s.startsWith("\\(", i)) {
                val end = s.indexOf("\\)", i + 2)
                if (end > i + 2 && appendMath(s.substring(i + 2, end))) return end + 2
                return -1
            }
            if (s.startsWith("\\[", i)) {
                val end = s.indexOf("\\]", i + 2)
                if (end > i + 2 && appendMath(s.substring(i + 2, end))) return end + 2
                return -1
            }
            return -1
        }

        private fun appendMath(tex: String): Boolean {
            val clean = tex.replace('\n', ' ').trim()
            if (clean.isEmpty()) return false
            out.append(MarkdownMath.convert(clean))
            return true
        }

        private fun markerFor(s: String, i: Int, c: Char): String {
            var n = 0
            while (i + n < s.length && s[i + n] == c) n++
            return when {
                n >= 3 -> "$c$c$c"
                n == 2 -> "$c$c"
                else -> "$c"
            }
        }

        private fun emphasis(s: String, i: Int, m: String): Int {
            val start = i + m.length
            val canOpen = start < s.length &&
                !s[start].isWhitespace() &&
                (m[0] != '_' || i == 0 || !s[i - 1].isLetterOrDigit())
            if (canOpen) {
                val close = findClose(s, start, m)
                if (close > start) {
                    val from = out.length
                    parse(s.substring(start, close))
                    val to = out.length
                    if (to > from) {
                        when (m) {
                            "***" -> out.setSpan(StyleSpan(android.graphics.Typeface.BOLD_ITALIC), from, to, FLAGS)
                            "**", "__" -> out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), from, to, FLAGS)
                            "~~" -> out.setSpan(StrikethroughSpan(), from, to, FLAGS)
                            else -> out.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), from, to, FLAGS)
                        }
                    }
                    return close + m.length
                }
            }
            out.append(m[0])
            return i + 1
        }

        private fun findClose(s: String, from: Int, m: String): Int {
            var j = from
            while (j < s.length) {
                val c = s[j]
                if (c == '\\') {
                    j += 2
                    continue
                }
                if (c == '`') {
                    val end = s.indexOf('`', j + 1)
                    if (end > 0) {
                        j = end + 1
                        continue
                    }
                }
                if (s.startsWith(m, j) && j > from && !s[j - 1].isWhitespace()) {
                    val after = j + m.length
                    val ok = if (m.length == 1) {
                        val single = m[0]
                        s[j - 1] != single &&
                            (after >= s.length || s[after] != single) &&
                            (single != '_' || after >= s.length || !s[after].isLetterOrDigit())
                    } else if (m[0] == '_') {
                        after >= s.length || !s[after].isLetterOrDigit()
                    } else {
                        true
                    }
                    if (ok) return j
                }
                j++
            }
            return -1
        }

        private fun code(s: String, i: Int): Int {
            var n = 0
            while (i + n < s.length && s[i + n] == '`') n++
            var j = i + n
            while (j < s.length) {
                val k = s.indexOf('`', j)
                if (k < 0) break
                var m = 0
                while (k + m < s.length && s[k + m] == '`') m++
                if (m == n) {
                    var inner = s.substring(i + n, k)
                    if (inner.length > 1 && inner.startsWith(" ") && inner.endsWith(" ")) {
                        inner = inner.substring(1, inner.length - 1)
                    }
                    appendCode(inner)
                    return k + n
                }
                j = k + m
            }
            out.append("`".repeat(n))
            return i + n
        }

        private fun appendCode(text: String) {
            val from = out.length
            out.append(text.replace('\n', ' '))
            val to = out.length
            if (to == from) return
            out.setSpan(TypefaceSpan("monospace"), from, to, FLAGS)
            out.setSpan(BackgroundColorSpan(codeBg), from, to, FLAGS)
            out.setSpan(ForegroundColorSpan(codeFg), from, to, FLAGS)
            out.setSpan(RelativeSizeSpan(0.92f), from, to, FLAGS)
        }

        private fun link(s: String, i: Int): Int {
            val close = matchBracket(s, i)
            if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                val end = findParenEnd(s, close + 1)
                if (end > 0) {
                    val label = s.substring(i + 1, close)
                    var url = s.substring(close + 2, end).trim()
                    val space = url.indexOf(' ')
                    if (space > 0) url = url.substring(0, space)
                    url = url.trim('<', '>')
                    val safe = url.startsWith("http://", true) ||
                        url.startsWith("https://", true) ||
                        url.startsWith("mailto:", true) ||
                        url.startsWith("tel:", true)
                    if (safe) {
                        val from = out.length
                        val previous = inLink
                        inLink = true
                        parse(label.ifEmpty { url })
                        inLink = previous
                        val to = out.length
                        if (to > from) out.setSpan(LinkSpan(url, this.link), from, to, FLAGS)
                        return end + 1
                    }
                }
            }
            out.append('[')
            return i + 1
        }

        private fun matchBracket(s: String, start: Int): Int {
            var depth = 0
            var j = start
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j++
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return j
                    }
                }
                j++
            }
            return -1
        }

        private fun findParenEnd(s: String, open: Int): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                when (s[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return j
                    }
                    '\n' -> return -1
                }
                j++
            }
            return -1
        }

        private fun isUrlStart(s: String, i: Int): Boolean {
            return !inLink &&
                (s.startsWith("http://", i) || s.startsWith("https://", i)) &&
                (i == 0 || !s[i - 1].isLetterOrDigit())
        }

        private fun bareUrl(s: String, i: Int): Int {
            var j = i
            while (j < s.length && !s[j].isWhitespace() && s[j] != '<' && s[j] != '>') j++
            var end = j
            while (end > i && TRAILING.indexOf(s[end - 1]) >= 0) {
                if (s[end - 1] == ')') {
                    val segment = s.substring(i, end)
                    if (segment.count { it == '(' } >= segment.count { it == ')' }) break
                }
                end--
            }
            val url = s.substring(i, end)
            if (url.length <= 8) {
                out.append(s[i])
                return i + 1
            }
            val from = out.length
            out.append(url)
            out.setSpan(LinkSpan(url, link), from, out.length, FLAGS)
            return end
        }
    }
}
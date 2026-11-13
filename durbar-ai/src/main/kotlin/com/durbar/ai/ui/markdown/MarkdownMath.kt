package com.durbar.ai.ui.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.math.ceil
import kotlin.math.max

object MarkdownMath {

    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private const val NBSP = '\u00A0'
    private const val THIN = '\u2009'

    private val CHEM = Regex("[A-Z][A-Za-z]*")

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "lim", "max", "min",
        "sup", "inf", "det", "gcd", "deg", "dim", "ker", "arg", "mod", "Pr",
        "liminf", "limsup"
    )

    private val IGNORED = setOf(
        "limits", "nolimits", "displaystyle", "textstyle", "scriptstyle",
        "scriptscriptstyle", "mathstrut", "strut", "hline", "nonumber", "notag",
        "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl",
        "biggr", "Biggl", "Biggr", "relax", "hfill", "centering", "protect"
    )

    private val GREEK_LOWER = table(
        "alpha α beta β gamma γ delta δ epsilon ε varepsilon ϵ zeta ζ eta η theta θ " +
            "vartheta ϑ iota ι kappa κ lambda λ mu μ nu ν xi ξ pi π varpi ϖ rho ρ " +
            "varrho ϱ sigma σ varsigma ς tau τ upsilon υ phi φ varphi ϕ chi χ psi ψ omega ω"
    )

    private val GREEK_UPPER = table(
        "Gamma Γ Delta Δ Theta Θ Lambda Λ Xi Ξ Pi Π Sigma Σ Upsilon Υ Phi Φ Psi Ψ Omega Ω"
    )

    private val BINARY = table(
        "pm ± mp ∓ times × cdot · div ÷ ast ∗ star ⋆ cup ∪ cap ∩ land ∧ wedge ∧ " +
            "lor ∨ vee ∨ oplus ⊕ otimes ⊗ setminus ∖ circ ∘ bullet ∙"
    )

    private val RELATION = table(
        "le ≤ leq ≤ ge ≥ geq ≥ ne ≠ neq ≠ approx ≈ equiv ≡ sim ∼ simeq ≃ cong ≅ propto ∝ " +
            "in ∈ notin ∉ ni ∋ subset ⊂ subseteq ⊆ supset ⊃ supseteq ⊇ to → rightarrow → " +
            "leftarrow ← gets ← Rightarrow ⇒ Leftarrow ⇐ leftrightarrow ↔ Leftrightarrow ⇔ " +
            "implies ⟹ iff ⟺ mapsto ↦ longrightarrow ⟶ longleftarrow ⟵ Longrightarrow ⟹ " +
            "Longleftarrow ⟸ rightleftharpoons ⇌ ll ≪ gg ≫ perp ⊥ parallel ∥ mid ∣ " +
            "uparrow ↑ downarrow ↓"
    )

    private val OTHERS = table(
        "infty ∞ partial ∂ nabla ∇ emptyset ∅ varnothing ∅ forall ∀ exists ∃ neg ¬ lnot ¬ " +
            "angle ∠ degree ° ldots … dots … cdots ⋯ vdots ⋮ ddots ⋱ therefore ∴ because ∵ " +
            "triangle △ hbar ℏ ell ℓ prime ′ sum ∑ prod ∏ int ∫ iint ∬ oint ∮ aleph ℵ " +
            "top ⊤ bot ⊥ square □ checkmark ✓ dagger † ddagger ‡ copyright © pounds £ " +
            "euro € yen ¥ lbrace { rbrace } langle ⟨ rangle ⟩ lceil ⌈ rceil ⌉ lfloor ⌊ " +
            "rfloor ⌋ vert | Vert ‖ backslash \\ textbackslash \\ colon : AA Å"
    )

    private val DOUBLE_STRUCK = mapOf(
        'R' to 'ℝ', 'N' to 'ℕ', 'Z' to 'ℤ', 'Q' to 'ℚ', 'C' to 'ℂ', 'H' to 'ℍ', 'P' to 'ℙ'
    )

    private fun table(spec: String): Map<String, String> {
        val parts = spec.trim().split(Regex("\\s+"))
        val map = HashMap<String, String>()
        var k = 0
        while (k + 1 < parts.size) {
            map[parts[k]] = parts[k + 1]
            k += 2
        }
        return map
    }

    fun convert(tex: String): SpannableStringBuilder {
        return try {
            TexParser(tex.trim(), false, false).run()
        } catch (e: Throwable) {
            SpannableStringBuilder(tex)
        }
    }

    private class ScriptSpan(private val up: Boolean) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = shift(tp)

        override fun updateMeasureState(tp: TextPaint) = shift(tp)

        private fun shift(tp: TextPaint) {
            val ascent = tp.ascent()
            tp.textSize = tp.textSize * 0.72f
            tp.baselineShift += if (up) (ascent * 0.42f).toInt() else (-ascent * 0.18f).toInt()
        }
    }

    private abstract class MathSpan : ReplacementSpan() {
        private val cache = HashMap<String, StaticLayout>()

        fun box(id: String, content: CharSequence, paint: Paint, scale: Float): StaticLayout {
            val size = paint.textSize * scale
            val key = "$id|$size|${paint.color}"
            return cache.getOrPut(key) {
                val tp = TextPaint(paint)
                tp.textSize = size
                val width = ceil(Layout.getDesiredWidth(content, tp)).toInt() + 2
                StaticLayout.Builder.obtain(content, 0, content.length, tp, width)
                    .setIncludePad(false)
                    .build()
            }
        }

        fun stroke(paint: Paint, factor: Float): Paint {
            return Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, paint.textSize * factor)
                isAntiAlias = true
            }
        }
    }

    private class FracSpan(
        private val num: CharSequence,
        private val den: CharSequence,
        private val bar: Boolean
    ) : MathSpan() {

        private inner class Geo(paint: Paint) {
            val n = box("n", num, paint, 0.85f)
            val d = box("d", den, paint, 0.85f)
            val size = paint.textSize
            val axis = size * 0.3f
            val numBase = axis + size * 0.2f
            val denBase = size * 0.76f - axis
            val up = numBase + n.getLineBaseline(0) * 0.85f
            val down = denBase + (d.height - d.getLineBaseline(0)) * 0.85f
            val width = max(n.width, d.width) + size * 0.3f
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val g = Geo(paint)
            if (fm != null) {
                fm.ascent = -g.up.toInt()
                fm.top = fm.ascent
                fm.descent = g.down.toInt()
                fm.bottom = fm.descent
            }
            return ceil(g.width).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val g = Geo(paint)
            canvas.save()
            canvas.translate(x + (g.width - g.n.width) / 2f, y - g.numBase - g.n.getLineBaseline(0))
            g.n.draw(canvas)
            canvas.restore()
            canvas.save()
            canvas.translate(x + (g.width - g.d.width) / 2f, y + g.denBase - g.d.getLineBaseline(0))
            g.d.draw(canvas)
            canvas.restore()
            if (bar) {
                val barY = y - g.axis
                canvas.drawLine(x + g.size * 0.05f, barY, x + g.width - g.size * 0.05f, barY, stroke(paint, 0.06f))
            }
        }
    }

    private class SqrtSpan(
        private val body: CharSequence,
        private val index: CharSequence?
    ) : MathSpan() {

        private inner class Geo(paint: Paint) {
            val b = box("b", body, paint, 1f)
            val ix = if (index.isNullOrEmpty()) null else box("i", index, paint, 0.6f)
            val size = paint.textSize
            val base = b.getLineBaseline(0).toFloat()
            val rad = size * 0.62f
            val gap = size * 0.1f
            val idxW = if (ix == null) 0f else max(ix.width - rad * 0.4f, 0f)
            val up = base + gap
            val down = (b.height - base)
            val width = idxW + rad + b.width + size * 0.1f
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val g = Geo(paint)
            if (fm != null) {
                fm.ascent = -(g.up + g.size * 0.06f).toInt()
                fm.top = fm.ascent
                fm.descent = g.down.toInt()
                fm.bottom = fm.descent
            }
            return ceil(g.width).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val g = Geo(paint)
            val left = x + g.idxW
            val overY = y - g.up
            val bottomY = y + g.down
            val h = bottomY - overY
            val path = Path()
            path.moveTo(left, overY + h * 0.6f)
            path.lineTo(left + g.rad * 0.2f, overY + h * 0.52f)
            path.lineTo(left + g.rad * 0.45f, bottomY)
            path.lineTo(left + g.rad, overY)
            path.lineTo(x + g.width, overY)
            canvas.drawPath(path, stroke(paint, 0.07f))
            canvas.save()
            canvas.translate(left + g.rad + g.size * 0.04f, y - g.base)
            g.b.draw(canvas)
            canvas.restore()
            val ix = g.ix
            if (ix != null) {
                canvas.save()
                canvas.translate(x, overY + h * 0.05f)
                ix.draw(canvas)
                canvas.restore()
            }
        }
    }

    private class ArrowSpan(
        private val above: CharSequence,
        private val below: CharSequence?,
        private val backward: Boolean
    ) : MathSpan() {

        private inner class Geo(paint: Paint) {
            val a = if (above.isEmpty()) null else box("a", above, paint, 0.72f)
            val b = if (below.isNullOrEmpty()) null else box("b", below, paint, 0.72f)
            val size = paint.textSize
            val axis = size * 0.3f
            val gap = size * 0.1f
            val up = axis + gap + (a?.height ?: 0)
            val down = max((b?.height ?: 0) + gap - axis, 0f)
            val width = max(max(a?.width ?: 0, b?.width ?: 0).toFloat(), size * 1.1f) + size * 0.5f
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val g = Geo(paint)
            if (fm != null) {
                fm.ascent = -g.up.toInt()
                fm.top = fm.ascent
                fm.descent = g.down.toInt()
                fm.bottom = fm.descent
            }
            return ceil(g.width).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val g = Geo(paint)
            val lineY = y - g.axis
            val x0 = x + g.size * 0.12f
            val x1 = x + g.width - g.size * 0.12f
            val pen = stroke(paint, 0.06f)
            canvas.drawLine(x0, lineY, x1, lineY, pen)
            val head = g.size * 0.22f
            if (backward) {
                canvas.drawLine(x0, lineY, x0 + head, lineY - head * 0.7f, pen)
                canvas.drawLine(x0, lineY, x0 + head, lineY + head * 0.7f, pen)
            } else {
                canvas.drawLine(x1, lineY, x1 - head, lineY - head * 0.7f, pen)
                canvas.drawLine(x1, lineY, x1 - head, lineY + head * 0.7f, pen)
            }
            val a = g.a
            if (a != null) {
                canvas.save()
                canvas.translate(x + (g.width - a.width) / 2f, lineY - g.gap - a.height)
                a.draw(canvas)
                canvas.restore()
            }
            val b = g.b
            if (b != null) {
                canvas.save()
                canvas.translate(x + (g.width - b.width) / 2f, lineY + g.gap)
                b.draw(canvas)
                canvas.restore()
            }
        }
    }

    private class TexParser(
        private val src: String,
        private val text: Boolean,
        private val flat: Boolean
    ) {
        private val out = SpannableStringBuilder()
        private var i = 0
        private var operand = false
        private var bigOp = false

        fun run(): SpannableStringBuilder {
            while (i < src.length) step()
            return out
        }

        private fun sub(raw: String, asText: Boolean = text, asFlat: Boolean = flat): SpannableStringBuilder {
            return TexParser(raw, asText, asFlat).run()
        }

        private fun step() {
            val c = src[i]
            when {
                c == '\\' -> command()
                c == '{' -> {
                    val group = balanced()
                    out.append(sub(group))
                    operand = true
                }
                c == '}' -> i++
                c.isWhitespace() -> {
                    if (text && (out.isEmpty() || out[out.length - 1] != ' ')) out.append(' ')
                    i++
                }
                text -> {
                    out.append(c)
                    i++
                    operand = true
                }
                c == '^' -> script(true)
                c == '_' -> script(false)
                c == '-' && src.startsWith("->", i) -> {
                    i += 2
                    relation("\u2192")
                }
                c == '+' -> {
                    i++
                    binary("+")
                }
                c == '-' -> {
                    i++
                    binary("\u2212")
                }
                c == '*' -> {
                    i++
                    binary("\u2217")
                }
                c == '=' || c == '<' || c == '>' -> {
                    i++
                    relation(c.toString())
                }
                c == ',' || c == ';' -> {
                    i++
                    punctuation(c)
                }
                c == '(' || c == '[' -> {
                    out.append(c)
                    i++
                    operand = false
                }
                c == ')' || c == ']' -> {
                    out.append(c)
                    i++
                    operand = true
                }
                c == '\'' -> {
                    out.append('\u2032')
                    i++
                    operand = true
                }
                c == '~' -> {
                    out.append(NBSP)
                    i++
                }
                c == '&' -> {
                    out.append('\u2003')
                    i++
                }
                c == '/' -> {
                    out.append('/')
                    i++
                    operand = false
                }
                c.isLetter() -> {
                    val from = out.length
                    out.append(c)
                    if (!flat) out.setSpan(StyleSpan(Typeface.ITALIC), from, out.length, FLAGS)
                    i++
                    operand = true
                }
                else -> {
                    out.append(c)
                    i++
                    operand = true
                }
            }
        }

        private fun spaceOut() {
            if (out.isEmpty()) return
            val last = out[out.length - 1]
            if (last != ' ' && last != NBSP && last != '\n') out.append(' ')
        }

        private fun binary(symbol: String) {
            if (operand) {
                spaceOut()
                out.append(symbol)
                out.append(NBSP)
            } else {
                out.append(symbol)
            }
            operand = false
        }

        private fun relation(symbol: String) {
            spaceOut()
            out.append(symbol)
            out.append(NBSP)
            operand = false
        }

        private fun punctuation(c: Char) {
            out.append(c)
            val between = c == ',' && i - 2 >= 0 && src[i - 2].isDigit() && i < src.length && src[i].isDigit()
            if (!between && i < src.length) out.append(' ')
            operand = false
        }

        private fun peek(): Char? {
            var j = i
            while (j < src.length && src[j].isWhitespace()) j++
            return if (j < src.length) src[j] else null
        }

        private fun skipSpaces() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun balanced(): String {
            val start = i + 1
            var depth = 0
            var j = i
            while (j < src.length) {
                val ch = src[j]
                if (ch == '\\') {
                    j += 2
                    continue
                }
                if (ch == '{') {
                    depth++
                } else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        i = j + 1
                        return src.substring(start, j)
                    }
                }
                j++
            }
            i = src.length
            return if (start >= src.length) "" else src.substring(start)
        }

        private fun arg(): String {
            skipSpaces()
            if (i >= src.length) return ""
            val c = src[i]
            if (c == '{') return balanced()
            if (c == '\\') {
                var j = i + 1
                if (j < src.length && src[j].isLetter()) {
                    while (j < src.length && src[j].isLetter()) j++
                } else {
                    j = minOf(j + 1, src.length)
                }
                val raw = src.substring(i, j)
                i = j
                return raw
            }
            i++
            return c.toString()
        }

        private fun optional(): String? {
            skipSpaces()
            if (i < src.length && src[i] == '[') {
                val close = src.indexOf(']', i)
                if (close > 0) {
                    val raw = src.substring(i + 1, close)
                    i = close + 1
                    return raw
                }
            }
            return null
        }

        private fun span(what: Any) {
            val from = out.length
            out.append('\uFFFC')
            out.setSpan(what, from, out.length, FLAGS)
            operand = true
        }

        private fun scripted(body: String, up: Boolean) {
            if (body.isEmpty()) return
            val from = out.length
            out.append(body)
            out.setSpan(ScriptSpan(up), from, out.length, FLAGS)
        }

        private fun script(up: Boolean) {
            i++
            val raw = arg()
            if (up && raw == "\\circ") {
                out.append('\u00B0')
                operand = true
                return
            }
            val body = sub(raw)
            if (body.isNotEmpty()) {
                val from = out.length
                out.append(body)
                out.setSpan(ScriptSpan(up), from, out.length, FLAGS)
            }
            operand = true
            if (bigOp) {
                val next = peek()
                if (next != '^' && next != '_') {
                    bigOp = false
                    if (next != null) out.append(THIN)
                }
            }
        }

        private fun command() {
            if (i + 1 >= src.length) {
                i++
                return
            }
            val next = src[i + 1]
            if (!next.isLetter()) {
                i += 2
                symbolChar(next)
                return
            }
            var j = i + 1
            while (j < src.length && src[j].isLetter()) j++
            val name = src.substring(i + 1, j)
            i = j
            named(name)
        }

        private fun symbolChar(c: Char) {
            when (c) {
                ',' -> out.append(THIN)
                ':', ';', ' ' -> out.append(' ')
                '!' -> Unit
                '\\' -> {
                    out.append('\n')
                    operand = false
                }
                '|' -> {
                    out.append('\u2016')
                    operand = true
                }
                else -> {
                    out.append(c)
                    operand = true
                }
            }
        }

        private fun word(symbol: String, function: Boolean) {
            if (out.isNotEmpty() && out[out.length - 1].isLetterOrDigit()) out.append(THIN)
            out.append(symbol)
            val next = peek()
            when {
                next == null -> Unit
                next == '^' || next == '_' -> bigOp = true
                function && next == '(' -> Unit
                else -> out.append(THIN)
            }
            operand = false
        }

        private fun styled(style: Int, upright: Boolean) {
            val body = sub(arg(), text, upright)
            val from = out.length
            out.append(body)
            out.setSpan(StyleSpan(style), from, out.length, FLAGS)
            operand = true
        }

        private fun accent(mark: Char) {
            val body = sub(arg())
            val from = out.length
            out.append(body)
            var k = out.length - 1
            while (k >= from) {
                if (out[k].isLetterOrDigit()) out.insert(k + 1, mark.toString())
                k--
            }
            operand = true
        }

        private fun chemDigits(raw: String) {
            if (!CHEM.matches(raw) || i >= src.length) return
            var digits: String? = null
            if (src[i].isDigit()) {
                var e = i
                while (e < src.length && src[e].isDigit()) e++
                digits = src.substring(i, e)
                i = e
            } else if (src[i] == '{') {
                val save = i
                val group = balanced()
                if (group.isNotEmpty() && group.all { it.isDigit() }) digits = group else i = save
            }
            if (digits != null) scripted(digits, false)
        }

        private fun chem(raw: String) {
            var k = 0
            while (k < raw.length) {
                val c = raw[k]
                when {
                    raw.startsWith("<=>", k) -> {
                        spaceOut()
                        out.append("\u21CC").append(NBSP)
                        k += 3
                    }
                    raw.startsWith("->", k) -> {
                        spaceOut()
                        out.append("\u2192").append(NBSP)
                        k += 2
                    }
                    c.isDigit() && k > 0 && (raw[k - 1].isLetter() || raw[k - 1] == ')') -> {
                        var e = k
                        while (e < raw.length && raw[e].isDigit()) e++
                        scripted(raw.substring(k, e), false)
                        k = e
                    }
                    c == '^' || c == '_' -> {
                        var e = k + 1
                        val body: String
                        if (e < raw.length && raw[e] == '{') {
                            val close = raw.indexOf('}', e)
                            val stop = if (close < 0) raw.length else close
                            body = raw.substring(e + 1, stop)
                            e = if (close < 0) raw.length else close + 1
                        } else {
                            body = if (e < raw.length) raw[e].toString() else ""
                            e = minOf(e + 1, raw.length)
                        }
                        scripted(body.replace("-", "\u2212"), c == '^')
                        k = e
                    }
                    else -> {
                        out.append(c)
                        k++
                    }
                }
            }
            operand = true
        }

        private fun named(name: String) {
            if (name in IGNORED) return
            when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val a = arg()
                    val b = arg()
                    span(FracSpan(sub(a), sub(b), true))
                }
                "binom", "dbinom", "tbinom" -> {
                    val a = arg()
                    val b = arg()
                    out.append('(')
                    span(FracSpan(sub(a), sub(b), false))
                    out.append(')')
                }
                "sqrt" -> {
                    val index = optional()
                    val body = arg()
                    span(SqrtSpan(sub(body), index?.let { sub(it) }))
                }
                "text", "textrm", "mathrm", "mbox", "textnormal", "operatorname",
                "mathsf", "textsf", "mathtt", "texttt" -> {
                    val raw = arg()
                    out.append(TexParser(raw, true, true).run())
                    operand = true
                    if (name == "text" || name == "textrm" || name == "mathrm") chemDigits(raw)
                }
                "textbf", "mathbf", "boldsymbol", "bm" -> styled(Typeface.BOLD, name == "textbf" || name == "mathbf")
                "textit", "mathit", "emph" -> styled(Typeface.ITALIC, true)
                "mathbb" -> {
                    val raw = arg()
                    out.append(raw.map { DOUBLE_STRUCK[it] ?: it }.joinToString(""))
                    operand = true
                }
                "mathcal", "mathfrak", "mathscr" -> {
                    out.append(sub(arg(), text, true))
                    operand = true
                }
                "overline" -> accent('\u0305')
                "bar" -> accent('\u0304')
                "vec", "overrightarrow" -> accent('\u20D7')
                "hat", "widehat" -> accent('\u0302')
                "tilde", "widetilde" -> accent('\u0303')
                "dot" -> accent('\u0307')
                "ddot" -> accent('\u0308')
                "underline" -> {
                    val body = sub(arg())
                    val from = out.length
                    out.append(body)
                    out.setSpan(UnderlineSpan(), from, out.length, FLAGS)
                    operand = true
                }
                "xrightarrow", "xlongrightarrow", "xleftarrow", "xlongleftarrow",
                "xrightleftharpoons", "xlongequal" -> {
                    val below = optional()
                    val above = arg()
                    val backward = name == "xleftarrow" || name == "xlongleftarrow"
                    spaceOut()
                    span(ArrowSpan(sub(above), below?.let { sub(it) }, backward))
                    out.append(NBSP)
                    operand = false
                }
                "left", "right", "middle" -> {
                    if (i < src.length && src[i] == '.') i++
                }
                "begin", "end" -> {
                    val env = arg()
                    if (name == "begin" && (env == "array" || env == "tabular")) arg()
                }
                "ce", "pu" -> chem(arg())
                "hspace", "vspace", "phantom", "hphantom", "vphantom", "label", "tag" -> arg()
                "quad" -> out.append('\u2003')
                "qquad" -> out.append("\u2003\u2003")
                "enspace", "space" -> out.append('\u2002')
                "thinspace" -> out.append(THIN)
                else -> other(name)
            }
        }

        private fun other(name: String) {
            val lower = GREEK_LOWER[name]
            if (lower != null) {
                val from = out.length
                out.append(lower)
                if (!flat) out.setSpan(StyleSpan(Typeface.ITALIC), from, out.length, FLAGS)
                operand = true
                return
            }
            val upper = GREEK_UPPER[name]
            if (upper != null) {
                out.append(upper)
                operand = true
                return
            }
            val binary = BINARY[name]
            if (binary != null) {
                binary(binary)
                return
            }
            val relation = RELATION[name]
            if (relation != null) {
                relation(relation)
                return
            }
            if (name in FUNCTIONS) {
                word(name, true)
                return
            }
            val symbol = OTHERS[name]
            if (symbol != null) {
                if (name == "sum" || name == "prod" || name == "int" || name == "iint" || name == "oint") {
                    word(symbol, false)
                } else {
                    out.append(symbol)
                    operand = true
                }
                return
            }
            out.append(name)
            operand = true
        }
    }
}
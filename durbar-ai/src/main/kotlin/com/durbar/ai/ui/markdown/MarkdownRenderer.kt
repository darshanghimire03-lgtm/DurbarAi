package com.durbar.ai.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.LeadingMarginSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.durbar.ai.R
import kotlin.math.ceil
import kotlin.math.max

object MarkdownRenderer {

    private enum class Kind { TEXT, QUOTE, CODE, TABLE, RULE }

    private class Slot(val kind: Kind) {
        var block: MdBlock? = null
        var topMargin: Int = -1
    }

    private class MarkerSpan(
        private val marker: String,
        private val indent: Int,
        private val width: Int
    ) : LeadingMarginSpan {

        override fun getLeadingMargin(first: Boolean): Int = indent + width

        override fun drawLeadingMargin(
            c: Canvas,
            p: Paint,
            x: Int,
            dir: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            first: Boolean,
            layout: Layout?
        ) {
            if (!first) return
            if (text is Spanned && text.getSpanStart(this) != start) return
            val markerWidth = p.measureText(marker)
            val drawX = if (dir > 0) x + indent.toFloat() else x - indent - markerWidth
            c.drawText(marker, drawX, baseline.toFloat(), p)
        }
    }

    fun render(container: LinearLayout, markdown: String) {
        val context = container.context
        val blocks = MarkdownParser.parse(markdown)
        var previous: MdBlock? = null

        for (index in blocks.indices) {
            val block = blocks[index]
            val kind = kindOf(block)
            val existing = container.getChildAt(index)
            val existingSlot = existing?.tag as? Slot
            val view: View
            val slot: Slot
            if (existing != null && existingSlot != null && existingSlot.kind == kind) {
                view = existing
                slot = existingSlot
            } else {
                if (existing != null) container.removeViewAt(index)
                view = create(context, kind)
                slot = view.tag as Slot
                container.addView(view, index)
            }

            val top = topMargin(context, block, previous)
            if (slot.block != block || slot.topMargin != top) {
                val params = view.layoutParams as LinearLayout.LayoutParams
                params.topMargin = top
                view.layoutParams = params
                bind(view, kind, block)
                slot.block = block
                slot.topMargin = top
            }
            previous = block
        }

        while (container.childCount > blocks.size) {
            container.removeViewAt(container.childCount - 1)
        }
    }

    private fun kindOf(block: MdBlock): Kind = when (block) {
        is MdBlock.Code -> Kind.CODE
        is MdBlock.Table -> Kind.TABLE
        is MdBlock.Quote -> Kind.QUOTE
        MdBlock.Rule -> Kind.RULE
        else -> Kind.TEXT
    }

    private fun topMargin(context: Context, block: MdBlock, previous: MdBlock?): Int {
        if (previous == null) return 0
        val value = when {
            block is MdBlock.Heading -> if (previous is MdBlock.Heading) 6f else 18f
            previous is MdBlock.Heading -> 6f
            block is MdBlock.Item -> if (previous is MdBlock.Item) 4f else 8f
            else -> 10f
        }
        return dp(context, value)
    }

    private fun create(context: Context, kind: Kind): View {
        val view = when (kind) {
            Kind.TEXT -> textView(context)
            Kind.QUOTE -> quoteView(context)
            Kind.CODE -> codeView(context)
            Kind.TABLE -> tableView(context)
            Kind.RULE -> ruleView(context)
        }
        view.tag = Slot(kind)
        return view
    }

    private fun bind(view: View, kind: Kind, block: MdBlock) {
        when (kind) {
            Kind.TEXT -> bindText(view as TextView, block)
            Kind.QUOTE -> bindQuote(view as LinearLayout, block as MdBlock.Quote)
            Kind.CODE -> bindCode(view as LinearLayout, block as MdBlock.Code)
            Kind.TABLE -> bindTable(view as HorizontalScrollView, block as MdBlock.Table)
            Kind.RULE -> Unit
        }
    }

    private fun textView(context: Context): TextView {
        return TextView(context).apply {
            setTextColor(color(context, R.color.ai_ink))
            setLineSpacing(0f, 1.18f)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun quoteView(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val bar = View(context).apply {
            setBackgroundColor(color(context, R.color.ai_blue))
            alpha = 0.35f
        }
        row.addView(bar, LinearLayout.LayoutParams(dp(context, 3f), ViewGroup.LayoutParams.MATCH_PARENT))
        val text = textView(context)
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 12f)
            }
        )
        return row
    }

    private fun codeView(context: Context): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color(context, R.color.ai_tint))
                cornerRadius = dp(context, 12f).toFloat()
                setStroke(dp(context, 1f), color(context, R.color.ai_border))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14f), dp(context, 4f), dp(context, 4f), 0)
        }
        val lang = TextView(context).apply {
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(color(context, R.color.ai_muted))
        }
        val copy = TextView(context).apply {
            text = "Copy"
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(color(context, R.color.ai_blue))
            setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
        }
        header.addView(lang, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            copy,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val code = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setLineSpacing(0f, 1.1f)
            setTextColor(color(context, R.color.ai_ink))
            setPadding(dp(context, 14f), dp(context, 2f), dp(context, 14f), dp(context, 14f))
            setTextIsSelectable(true)
        }
        scroll.addView(
            code,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        root.addView(header)
        root.addView(scroll)
        return root
    }

    private fun tableView(context: Context): HorizontalScrollView {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val table = TableLayout(context).apply {
            setBackgroundColor(color(context, R.color.ai_border))
            setPadding(0, 0, dp(context, 1f), dp(context, 1f))
        }
        scroll.addView(
            table,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return scroll
    }

    private fun ruleView(context: Context): View {
        return View(context).apply {
            setBackgroundColor(color(context, R.color.ai_border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
        }
    }

    private fun bindText(tv: TextView, block: MdBlock) {
        val context = tv.context
        tv.setTextColor(color(context, R.color.ai_ink))
        when (block) {
            is MdBlock.Heading -> {
                tv.textSize = when (block.level) {
                    1 -> 22f
                    2 -> 20f
                    3 -> 18f
                    else -> 16f
                }
                tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                tv.setText(MarkdownInline.style(context, block.text), TextView.BufferType.SPANNABLE)
            }
            is MdBlock.Paragraph -> {
                tv.textSize = 16f
                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                tv.setText(MarkdownInline.style(context, block.text), TextView.BufferType.SPANNABLE)
            }
            is MdBlock.Item -> {
                tv.textSize = 16f
                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                val text = MarkdownInline.style(context, block.text)
                if (text.isEmpty()) text.append('\u200B')
                val indent = dp(context, 4f + block.depth * 18f)
                val markerWidth = ceil(tv.paint.measureText(block.marker)).toInt() + dp(context, 8f)
                val width = max(dp(context, 16f), markerWidth)
                text.setSpan(
                    MarkerSpan(block.marker, indent, width),
                    0,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                tv.setText(text, TextView.BufferType.SPANNABLE)
            }
            else -> Unit
        }
    }

    private fun bindQuote(row: LinearLayout, block: MdBlock.Quote) {
        val context = row.context
        val tv = row.getChildAt(1) as TextView
        tv.textSize = 16f
        tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        tv.setTextColor(color(context, R.color.ai_muted))
        tv.setText(MarkdownInline.style(context, block.text), TextView.BufferType.SPANNABLE)
    }

    private fun bindCode(root: LinearLayout, block: MdBlock.Code) {
        val header = root.getChildAt(0) as LinearLayout
        val lang = header.getChildAt(0) as TextView
        val copy = header.getChildAt(1) as TextView
        val scroll = root.getChildAt(1) as HorizontalScrollView
        val code = scroll.getChildAt(0) as TextView

        lang.text = block.lang.ifBlank { "code" }
        code.text = block.code.ifEmpty { " " }
        copy.text = "Copy"
        copy.setOnClickListener {
            val manager = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("code", block.code))
            copy.text = "Copied"
            copy.postDelayed({ copy.text = "Copy" }, 1500)
        }
    }

    private fun bindTable(scroll: HorizontalScrollView, block: MdBlock.Table) {
        val context = scroll.context
        val table = scroll.getChildAt(0) as TableLayout
        table.removeAllViews()
        table.addView(tableRow(context, block.header, block.aligns, true))
        block.rows.forEach { table.addView(tableRow(context, it, block.aligns, false)) }
    }

    private fun tableRow(context: Context, cells: List<String>, aligns: List<Int>, header: Boolean): TableRow {
        val row = TableRow(context)
        cells.forEachIndexed { index, cell ->
            val tv = TextView(context).apply {
                textSize = 14f
                setTextColor(color(context, R.color.ai_ink))
                setPadding(dp(context, 10f), dp(context, 7f), dp(context, 10f), dp(context, 7f))
                maxWidth = dp(context, 240f)
                setBackgroundColor(color(context, if (header) R.color.ai_tint else R.color.white))
                gravity = when (aligns.getOrElse(index) { 0 }) {
                    1 -> Gravity.CENTER_HORIZONTAL
                    2 -> Gravity.END
                    else -> Gravity.START
                }
                if (header) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                movementMethod = LinkMovementMethod.getInstance()
                setText(MarkdownInline.style(context, cell), TextView.BufferType.SPANNABLE)
            }
            val params = TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(dp(context, 1f), dp(context, 1f), 0, 0)
            row.addView(tv, params)
        }
        return row
    }

    private fun color(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

    private fun dp(context: Context, value: Float): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
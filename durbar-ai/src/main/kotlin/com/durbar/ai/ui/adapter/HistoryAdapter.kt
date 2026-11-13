package com.durbar.ai.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.durbar.ai.R
import com.durbar.ai.data.model.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onOpen: (ChatSession) -> Unit,
    private val onDelete: (ChatSession) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private var items: List<ChatSession> = emptyList()
    private var activeId: String? = null
    private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvHistoryTitle)
        val date: TextView = view.findViewById(R.id.tvHistoryDate)
        val delete: ImageView = view.findViewById(R.id.ivHistoryDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_history, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val session = items[position]
        holder.title.text = session.title.ifBlank { "New chat" }
        holder.date.text = dateFormat.format(Date(session.updatedAt))
        holder.itemView.setBackgroundColor(if (session.id == activeId) 0x1A0B3BFF else Color.TRANSPARENT)
        holder.itemView.setOnClickListener { onOpen(session) }
        holder.delete.setOnClickListener { onDelete(session) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<ChatSession>, activeId: String?) {
        items = list
        this.activeId = activeId
        notifyDataSetChanged()
    }
}
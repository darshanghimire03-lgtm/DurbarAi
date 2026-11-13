package com.durbar.ai.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.durbar.ai.R
import com.durbar.ai.data.model.AiModel

class ModelAdapter(
    private val items: List<AiModel>,
    private val selectedId: String?,
    private val onSelect: (AiModel) -> Unit
) : RecyclerView.Adapter<ModelAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvModelTitle)
        val sub: TextView = view.findViewById(R.id.tvModelSub)
        val check: ImageView = view.findViewById(R.id.ivModelCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_model_option, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val model = items[position]
        val provider = providerName(model.provider)
        holder.title.text = model.label
        holder.sub.text = if (model.description.isBlank()) provider else "$provider • ${model.description}"
        holder.check.visibility = if (model.id == selectedId) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onSelect(model) }
    }

    override fun getItemCount(): Int = items.size

    private fun providerName(provider: String): String {
        return when (provider) {
            "groq" -> "Groq"
            "gemini" -> "Gemini"
            "openrouter" -> "OpenRouter"
            else -> provider
        }
    }
}
package com.durbar.ai.ui.adapter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.durbar.ai.R
import com.durbar.ai.data.model.AiPerson
import com.durbar.ai.data.model.ChatMessage
import com.durbar.ai.util.Constants

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ChatMessage>()

    class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.tvMessage)
    }

    class BotHolder(view: View) : RecyclerView.ViewHolder(view) {
        val message: LinearLayout = view.findViewById(R.id.messageContainer)
        val cards: LinearLayout = view.findViewById(R.id.cardsContainer)
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isUser) TYPE_USER else TYPE_BOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserHolder(inflater.inflate(R.layout.item_ai_message_user, parent, false))
        } else {
            BotHolder(inflater.inflate(R.layout.item_ai_message_bot, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = items[position]
        when (holder) {
            is UserHolder -> holder.text.text = message.text
            is BotHolder -> {
                holder.message.removeAllViews()
                if (message.text.isBlank()) {
                    holder.message.visibility = View.GONE
                } else {
                    holder.message.visibility = View.VISIBLE
                    val tv = TextView(holder.message.context)
                    tv.text = message.text
                    tv.textSize = 16f
                    tv.setLineSpacing(0f, 1.15f)
                    tv.setTextColor(ContextCompat.getColor(tv.context, R.color.ai_ink))
                    tv.setTextIsSelectable(true)
                    holder.message.addView(tv)
                }
                holder.message.alpha = if (message.isTyping) 0.6f else 1f
                bindPeople(holder.cards, message.people)
            }
        }
    }

    private fun bindPeople(container: LinearLayout, people: List<AiPerson>) {
        container.removeAllViews()
        if (people.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        people.forEach { person ->
            container.addView(buildPersonCard(container.context, container, person))
        }
    }

    private fun buildPersonCard(context: Context, parent: ViewGroup, person: AiPerson): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_ai_person_card, parent, false)
        val ivPhoto = view.findViewById<ImageView>(R.id.ivPersonPhoto)
        val tvName = view.findViewById<TextView>(R.id.tvPersonName)
        val ivVerified = view.findViewById<ImageView>(R.id.ivPersonVerified)
        val tvRole = view.findViewById<TextView>(R.id.tvPersonRole)
        val tvSubject = view.findViewById<TextView>(R.id.tvPersonSubject)
        val btnViewProfile = view.findViewById<TextView>(R.id.btnPersonViewProfile)

        tvName.text = person.name
        tvRole.text = person.role.replaceFirstChar { it.uppercase() }

        if (person.photoUrl.isBlank()) {
            ivPhoto.setImageResource(R.drawable.ic_default_profile)
        } else {
            Glide.with(context)
                .load(person.photoUrl)
                .placeholder(R.drawable.ic_default_profile)
                .error(R.drawable.ic_default_profile)
                .dontAnimate()
                .into(ivPhoto)
        }

        val badgeUrl = if (person.verified) {
            when (person.role.lowercase()) {
                Constants.ROLE_PROFESSOR -> Constants.BADGE_PROFESSOR_URL
                Constants.ROLE_MODERATOR -> Constants.BADGE_MODERATOR_URL
                Constants.ROLE_CAPTAIN -> Constants.BADGE_CAPTAIN_URL
                else -> null
            }
        } else {
            null
        }
        if (badgeUrl != null) {
            ivVerified.visibility = View.VISIBLE
            Glide.with(context).load(badgeUrl).dontAnimate().into(ivVerified)
        } else {
            ivVerified.visibility = View.GONE
        }

        val detail = when {
            person.subject.isNotBlank() -> "Subject: ${person.subject}"
            person.classLevel.isNotBlank() && person.section.isNotBlank() ->
                "Class ${person.classLevel} • Section ${person.section}"
            person.classLevel.isNotBlank() -> "Class ${person.classLevel}"
            else -> ""
        }
        if (detail.isBlank()) {
            tvSubject.visibility = View.GONE
        } else {
            tvSubject.visibility = View.VISIBLE
            tvSubject.text = detail
        }

        if (person.uid.isBlank()) {
            btnViewProfile.visibility = View.GONE
            btnViewProfile.setOnClickListener(null)
        } else {
            btnViewProfile.visibility = View.VISIBLE
            btnViewProfile.setOnClickListener {
                openProfile(context, person.uid)
            }
        }

        return view
    }

    private fun openProfile(context: Context, uid: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.PROFILE_BASE_URL + Uri.encode(uid)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this profile", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size

    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun removeTyping() {
        val index = items.indexOfLast { it.isTyping }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun clear() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun submit(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages.filter { !it.isTyping })
        notifyDataSetChanged()
    }

    fun snapshot(): List<ChatMessage> {
        return items.filter { !it.isTyping }
    }

    fun history(limit: Int): List<ChatMessage> {
        return items.filter { it.inHistory && !it.isTyping }.takeLast(limit)
    }

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_BOT = 2
    }
}
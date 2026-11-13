package com.durbar.ai.data.local

import android.content.Context
import com.durbar.ai.data.model.AiPerson
import com.durbar.ai.data.model.AiSource
import com.durbar.ai.data.model.ChatMessage
import com.durbar.ai.data.model.ChatSession
import org.json.JSONArray
import org.json.JSONObject

object ChatStore {

    private const val PREFS = "durbar_ai_chats"
    private const val KEY = "sessions"

    fun load(context: Context): MutableList<ChatSession> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<ChatSession>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                list.add(
                    ChatSession(
                        id = id,
                        title = o.optString("title"),
                        updatedAt = o.optLong("updatedAt"),
                        messages = readMessages(o.optJSONArray("messages"))
                    )
                )
            }
            list.sortByDescending { it.updatedAt }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, sessions: List<ChatSession>) {
        val array = JSONArray()
        sessions.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", writeMessages(s.messages))
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun writeMessages(messages: List<ChatMessage>): JSONArray {
        val array = JSONArray()
        messages.filter { !it.isTyping }.forEach { m ->
            val people = JSONArray()
            m.people.forEach { p ->
                people.put(
                    JSONObject()
                        .put("uid", p.uid)
                        .put("name", p.name)
                        .put("role", p.role)
                        .put("subject", p.subject)
                        .put("classLevel", p.classLevel)
                        .put("section", p.section)
                        .put("photoUrl", p.photoUrl)
                        .put("verified", p.verified)
                )
            }
            val sources = JSONArray()
            m.sources.forEach { s ->
                sources.put(JSONObject().put("url", s.url).put("title", s.title))
            }
            array.put(
                JSONObject()
                    .put("text", m.text)
                    .put("isUser", m.isUser)
                    .put("inHistory", m.inHistory)
                    .put("people", people)
                    .put("sources", sources)
            )
        }
        return array
    }

    private fun readMessages(array: JSONArray?): List<ChatMessage> {
        if (array == null) return emptyList()
        val list = ArrayList<ChatMessage>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val people = ArrayList<AiPerson>()
            val pa = o.optJSONArray("people")
            if (pa != null) {
                for (j in 0 until pa.length()) {
                    val p = pa.optJSONObject(j) ?: continue
                    people.add(
                        AiPerson(
                            uid = p.optString("uid"),
                            name = p.optString("name"),
                            role = p.optString("role"),
                            subject = p.optString("subject"),
                            classLevel = p.optString("classLevel"),
                            section = p.optString("section"),
                            photoUrl = p.optString("photoUrl"),
                            verified = p.optBoolean("verified")
                        )
                    )
                }
            }
            val sources = ArrayList<AiSource>()
            val sa = o.optJSONArray("sources")
            if (sa != null) {
                for (j in 0 until sa.length()) {
                    val s = sa.optJSONObject(j) ?: continue
                    val url = s.optString("url")
                    if (url.isNotBlank()) sources.add(AiSource(url, s.optString("title")))
                }
            }
            list.add(
                ChatMessage(
                    text = o.optString("text"),
                    isUser = o.optBoolean("isUser"),
                    inHistory = o.optBoolean("inHistory", true),
                    people = people,
                    sources = sources
                )
            )
        }
        return list
    }
}
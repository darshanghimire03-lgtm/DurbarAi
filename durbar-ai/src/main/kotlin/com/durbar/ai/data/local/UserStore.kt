package com.durbar.ai.data.local

import android.content.Context
import com.durbar.ai.data.model.UserProfile

object UserStore {

    private const val PREFS = "durbar_ai_user"
    private const val KEY_NAME = "full_name"
    private const val KEY_ROLE = "role"
    private const val KEY_CLASS = "class_level"
    private const val KEY_SECTION = "section"
    private const val KEY_PROMPTED = "prompted"

    fun load(context: Context): UserProfile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return UserProfile(
            fullName = p.getString(KEY_NAME, "").orEmpty(),
            role = p.getString(KEY_ROLE, "student").orEmpty().ifBlank { "student" },
            classLevel = p.getString(KEY_CLASS, "").orEmpty(),
            section = p.getString(KEY_SECTION, "").orEmpty()
        )
    }

    fun save(context: Context, profile: UserProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, profile.fullName.trim())
            .putString(KEY_ROLE, profile.role)
            .putString(KEY_CLASS, profile.classLevel.trim())
            .putString(KEY_SECTION, profile.section.trim())
            .putBoolean(KEY_PROMPTED, true)
            .apply()
    }

    fun wasPrompted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPTED, false)
    }

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PROMPTED, true)
            .apply()
    }
}

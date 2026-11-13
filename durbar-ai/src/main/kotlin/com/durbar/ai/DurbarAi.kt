package com.durbar.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.durbar.ai.ui.ChatActivity

object DurbarAi {

    const val EXTRA_NAME = "durbar_ai_name"
    const val EXTRA_ROLE = "durbar_ai_role"
    const val EXTRA_CLASS = "durbar_ai_class"
    const val EXTRA_SECTION = "durbar_ai_section"

    fun open(
        context: Context,
        fullName: String? = null,
        role: String? = null,
        classLevel: String? = null,
        section: String? = null
    ) {
        val intent = Intent(context, ChatActivity::class.java)
            .putExtra(EXTRA_NAME, fullName.orEmpty())
            .putExtra(EXTRA_ROLE, role.orEmpty())
            .putExtra(EXTRA_CLASS, classLevel.orEmpty())
            .putExtra(EXTRA_SECTION, section.orEmpty())
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

package com.durbar.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.durbar.ai.ui.ChatActivity

object DurbarAi {
    fun open(context: Context) {
        val intent = Intent(context, ChatActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

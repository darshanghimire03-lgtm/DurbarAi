package com.durbar.ai.data.model

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isTyping: Boolean = false,
    var inHistory: Boolean = true,
    val people: List<AiPerson> = emptyList(),
    val status: String = ""
)

data class ChatSession(
    val id: String,
    var title: String,
    var updatedAt: Long,
    var messages: List<ChatMessage>
)
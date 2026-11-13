package com.durbar.ai.data.model

data class AiPerson(
    val uid: String,
    val name: String,
    val role: String,
    val subject: String,
    val classLevel: String,
    val section: String,
    val photoUrl: String,
    val verified: Boolean
)

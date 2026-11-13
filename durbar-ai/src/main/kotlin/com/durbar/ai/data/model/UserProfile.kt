package com.durbar.ai.data.model

data class UserProfile(
    val fullName: String = "",
    val role: String = "student",
    val classLevel: String = "",
    val section: String = ""
) {
    val firstName: String
        get() = fullName.trim().substringBefore(" ")

    val isEmpty: Boolean
        get() = fullName.isBlank() && classLevel.isBlank() && section.isBlank()
}

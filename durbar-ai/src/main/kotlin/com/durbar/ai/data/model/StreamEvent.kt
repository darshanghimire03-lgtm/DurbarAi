package com.durbar.ai.data.model

sealed class StreamEvent {
    data class Start(val modelId: String?) : StreamEvent()
    data class Delta(val text: String) : StreamEvent()
    data class Status(val text: String) : StreamEvent()
    object Reset : StreamEvent()
    data class Done(
        val text: String,
        val people: List<AiPerson>,
        val modelId: String?,
        val images: List<String> = emptyList()
    ) : StreamEvent()
    data class Failure(val code: Int, val message: String) : StreamEvent()
}
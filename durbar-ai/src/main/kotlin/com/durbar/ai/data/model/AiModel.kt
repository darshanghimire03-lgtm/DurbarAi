package com.durbar.ai.data.model

data class AiModel(
    val id: String,
    val label: String,
    val provider: String,
    val description: String
)

data class AiModelList(
    val defaultId: String?,
    val models: List<AiModel>
)

data class AiReply(
    val text: String,
    val people: List<AiPerson>,
    val modelId: String?
)

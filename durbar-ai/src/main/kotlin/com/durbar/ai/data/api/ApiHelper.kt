package com.durbar.ai.data.api

import com.durbar.ai.data.model.AiModel
import com.durbar.ai.data.model.AiModelList
import com.durbar.ai.data.model.AiPerson
import com.durbar.ai.data.model.AiReply
import com.durbar.ai.data.model.ChatMessage
import com.durbar.ai.data.model.StreamEvent
import com.durbar.ai.data.model.UserProfile
import com.durbar.ai.util.CalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : IOException(message)

object ApiHelper {

    private const val BASE_URL = "https://durbar-ai.vercel.app"
    private const val CHAT_URL = "$BASE_URL/chat"
    private const val STREAM_URL = "$BASE_URL/chat/stream"
    private const val MODELS_URL = "$BASE_URL/models"
    private const val HISTORY_LIMIT = 12
    private const val GENERIC_STREAM_ERROR = "Something went wrong. Please try again."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val streamClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun models(): AiModelList {
        val request = Request.Builder().url(MODELS_URL).get().build()
        val json = JSONObject(execute(request))
        val array = json.optJSONArray("models") ?: JSONArray()
        val list = ArrayList<AiModel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            list.add(
                AiModel(
                    id = id,
                    label = item.optString("label").ifBlank { id },
                    provider = item.optString("provider"),
                    description = item.optString("description")
                )
            )
        }
        val defaultId = if (json.isNull("default")) null else json.optString("default").ifBlank { null }
        return AiModelList(defaultId, list)
    }

    fun chatStream(
        message: String,
        history: List<ChatMessage>,
        user: UserProfile?,
        modelId: String?
    ): Flow<StreamEvent> = flow {
        val body = buildBody(message, history, user)
        try {
            stream(body, modelId) { emit(it) }
        } catch (e: ApiException) {
            if (e.code == 400 && modelId != null) {
                stream(body, null) { emit(it) }
            } else {
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun chat(
        message: String,
        history: List<ChatMessage>,
        user: UserProfile?,
        modelId: String?
    ): AiReply {
        val body = buildBody(message, history, user)
        return try {
            post(body, modelId)
        } catch (e: ApiException) {
            if (e.code == 400 && modelId != null) post(body, null) else throw e
        }
    }

    private fun buildBody(message: String, history: List<ChatMessage>, user: UserProfile?): JSONObject {
        val historyJson = JSONArray()
        history.takeLast(HISTORY_LIMIT).forEach {
            historyJson.put(
                JSONObject()
                    .put("role", if (it.isUser) "user" else "assistant")
                    .put("content", it.text)
            )
        }

        val body = JSONObject()
            .put("message", message)
            .put("history", historyJson)

        CalendarRepository.findToday()?.let {
            body.put(
                "today_bs",
                JSONObject().put("year", it.year).put("month", it.month).put("day", it.day)
            )
        }

        if (user != null) {
            body.put(
                "user",
                JSONObject()
                    .put("fullName", user.fullName)
                    .put("role", user.role)
                    .put("classLevel", user.classLevel)
                    .put("section", user.section)
            )
        }
        return body
    }

    private suspend fun stream(
        body: JSONObject,
        modelId: String?,
        onEvent: suspend (StreamEvent) -> Unit
    ) {
        if (modelId != null) body.put("model", modelId) else body.remove("model")

        val request = Request.Builder()
            .url(STREAM_URL)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = streamClient.newCall(request)
        val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw ApiException(response.code, errorMessage(response.code, text))
                }
                val source = response.body?.source() ?: throw IOException("Empty response")
                val data = StringBuilder()
                var finished = false

                while (!finished) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) {
                            val event = parseEvent(data.toString())
                            data.setLength(0)
                            if (event != null) {
                                onEvent(event)
                                finished = event is StreamEvent.Done || event is StreamEvent.Failure
                            }
                        }
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).removePrefix(" "))
                    }
                }

                if (!finished && data.isNotEmpty()) {
                    val event = parseEvent(data.toString())
                    if (event != null) {
                        onEvent(event)
                        finished = event is StreamEvent.Done || event is StreamEvent.Failure
                    }
                }

                if (!finished) throw IOException("Stream ended early")
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            handle?.dispose()
        }
    }

    private fun parseEvent(raw: String): StreamEvent? {
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return null
        }
        return when (json.optString("type")) {
            "start" -> StreamEvent.Start(json.optString("model").ifBlank { null })
            "delta" -> json.optString("text").takeIf { it.isNotEmpty() }?.let { StreamEvent.Delta(it) }
            "status" -> StreamEvent.Status(json.optString("text"))
            "reset" -> StreamEvent.Reset
            "done" -> StreamEvent.Done(
                text = json.optString("reply").trim(),
                people = parsePeople(json.optJSONObject("cards")),
                modelId = json.optString("model").ifBlank { null }
            )
            "error" -> StreamEvent.Failure(
                code = json.optInt("status", 500),
                message = json.optString("error").ifBlank { GENERIC_STREAM_ERROR }
            )
            else -> null
        }
    }

    private suspend fun post(body: JSONObject, modelId: String?): AiReply {
        if (modelId != null) body.put("model", modelId) else body.remove("model")

        val request = Request.Builder()
            .url(CHAT_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val json = JSONObject(execute(request))
        val reply = json.optString("reply").trim()
        val people = parsePeople(json.optJSONObject("cards"))
        val usedModel = json.optString("model").ifBlank { null }
        if (reply.isEmpty() && people.isEmpty()) throw IOException("Empty reply")
        return AiReply(text = reply, people = people, modelId = usedModel)
    }

    private fun parsePeople(cards: JSONObject?): List<AiPerson> {
        if (cards == null || cards.optString("type") != "people") return emptyList()
        val array = cards.optJSONArray("people") ?: return emptyList()
        val list = ArrayList<AiPerson>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            list.add(
                AiPerson(
                    uid = item.optString("uid"),
                    name = item.optString("name"),
                    role = item.optString("role"),
                    subject = item.optString("subject"),
                    classLevel = item.optString("class_level"),
                    section = item.optString("section"),
                    photoUrl = item.optString("photo_url"),
                    verified = item.optBoolean("verified")
                )
            )
        }
        return list
    }

    private fun errorMessage(code: Int, text: String): String {
        val fromServer = try {
            JSONObject(text).optString("error")
        } catch (e: Exception) {
            ""
        }
        return fromServer.ifBlank { "HTTP $code" }
    }

    private suspend fun execute(request: Request): String {
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = try {
                            it.body?.string().orEmpty()
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                            return
                        }
                        if (!it.isSuccessful) {
                            if (cont.isActive) {
                                cont.resumeWithException(ApiException(it.code, errorMessage(it.code, text)))
                            }
                        } else if (cont.isActive) {
                            cont.resume(text)
                        }
                    }
                }
            })
        }
    }
}
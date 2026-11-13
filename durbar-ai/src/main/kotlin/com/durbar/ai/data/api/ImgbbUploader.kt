package com.durbar.ai.data.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.durbar.ai.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object ImgbbUploader {

    private const val MAX_SIDE = 1600
    private const val MAX_BYTES = 3_000_000
    private const val FREEIMAGE_URL = "https://freeimage.host/api/1/upload"
    private const val FREEIMAGE_KEY = "6d207e02198a847aa98d0a2a901485a5"
    private const val CATBOX_URL = "https://catbox.moe/user/api.php"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun upload(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = prepare(context, uri)
        val jpeg = "image/jpeg".toMediaType()

        val providers: List<Pair<String, suspend () -> String>> = listOf(
            "imgbb" to {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "photo.jpg", bytes.toRequestBody(jpeg))
                    .build()
                val request = Request.Builder()
                    .url("${Constants.IMGBB_UPLOAD_URL}?key=${Constants.IMGBB_API_KEY}")
                    .post(body)
                    .build()
                execute(request) { text ->
                    JSONObject(text).optJSONObject("data")?.optString("url").orEmpty()
                }
            },
            "freeimage" to {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("key", FREEIMAGE_KEY)
                    .addFormDataPart("action", "upload")
                    .addFormDataPart("format", "json")
                    .addFormDataPart("source", "photo.jpg", bytes.toRequestBody(jpeg))
                    .build()
                val request = Request.Builder().url(FREEIMAGE_URL).post(body).build()
                execute(request) { text ->
                    JSONObject(text).optJSONObject("image")?.optString("url").orEmpty()
                }
            },
            "catbox" to {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("reqtype", "fileupload")
                    .addFormDataPart("fileToUpload", "photo.jpg", bytes.toRequestBody(jpeg))
                    .build()
                val request = Request.Builder()
                    .url(CATBOX_URL)
                    .header("User-Agent", "Mozilla/5.0")
                    .post(body)
                    .build()
                execute(request) { text -> text.trim() }
            }
        )

        val errors = mutableListOf<String>()
        for ((name, attempt) in providers) {
            try {
                val url = attempt()
                if (url.startsWith("http")) return@withContext url
                errors.add("$name: bad response")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                errors.add("$name: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        throw IOException(errors.joinToString(" | "))
    }

    private suspend fun execute(request: Request, parse: (String) -> String): String {
        val call = client.newCall(request)
        val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            return call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parse(text)
            }
        } finally {
            handle?.dispose()
        }
    }

    private fun prepare(context: Context, uri: Uri): ByteArray {
        var autoOriented = false
        var source = decodeWithFactory(context, uri)

        if (source == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            source = decodeWithDecoder(context, uri)
            autoOriented = source != null
        }

        var bitmap = source ?: throw IOException("Cannot read image")

        val orientation = if (autoOriented) {
            ExifInterface.ORIENTATION_NORMAL
        } else {
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        }

        val matrix = Matrix()
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            matrix.postScale(scale, scale)
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        if (!matrix.isIdentity) {
            bitmap = try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } catch (e: OutOfMemoryError) {
                throw IOException("Image too large")
            }
        }

        if (bitmap.hasAlpha()) {
            bitmap = flatten(bitmap)
        }

        var quality = 85
        var bytes = compress(bitmap, quality)
        while (bytes.size > MAX_BYTES && quality > 40) {
            quality -= 15
            bytes = compress(bitmap, quality)
        }
        return bytes
    }

    private fun decodeWithFactory(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

            var result: Bitmap? = null
            while (result == null && sample <= 64) {
                try {
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    result = resolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    if (result == null) return null
                } catch (e: OutOfMemoryError) {
                    sample *= 2
                }
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithDecoder(context: Context, uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun flatten(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return out
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
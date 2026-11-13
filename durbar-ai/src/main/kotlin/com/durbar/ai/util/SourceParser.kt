package com.durbar.ai.util

import android.net.Uri
import com.durbar.ai.data.model.AiSource

object SourceParser {

    private val header = Regex("""(?im)^[ \t>#*_-]*sources?[ \t]*[:：]?[ \t*_]*(?=$|https?://|\[)""")
    private val mdLink = Regex("""\[([^\]]*)]\((https?://[^)\s]+)\)""")
    private val rawUrl = Regex("""https?://[^\s)\]>"']+""")

    fun split(text: String): Pair<String, List<AiSource>> {
        val match = header.findAll(text).lastOrNull() ?: return text to emptyList()
        val tail = text.substring(match.range.last + 1)
        val sources = parse(tail)
        if (sources.isEmpty() && tail.isNotBlank()) return text to emptyList()
        val body = text.substring(0, match.range.first).trimEnd()
        return body to sources
    }

    private fun parse(tail: String): List<AiSource> {
        val map = LinkedHashMap<String, AiSource>()
        mdLink.findAll(tail).forEach { m ->
            val url = m.groupValues[2].trimEnd('.', ',', ';')
            map.putIfAbsent(url, AiSource(url, m.groupValues[1]))
        }
        rawUrl.findAll(tail).forEach { m ->
            val url = m.value.trimEnd('.', ',', ';')
            map.putIfAbsent(url, AiSource(url))
        }
        return map.values.toList()
    }

    fun host(url: String): String {
        return try {
            Uri.parse(url).host.orEmpty().removePrefix("www.")
        } catch (e: Exception) {
            ""
        }
    }

    fun faviconUrl(url: String): String? {
        val host = host(url)
        if (host.isBlank()) return null
        return "https://www.google.com/s2/favicons?sz=64&domain=" + Uri.encode(host)
    }
}
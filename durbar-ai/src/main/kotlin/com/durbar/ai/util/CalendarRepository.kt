package com.durbar.ai.util

import android.os.Handler
import android.os.Looper
import com.durbar.ai.data.model.AdDate
import com.durbar.ai.data.model.BsDate
import com.durbar.ai.data.model.BsMonthData
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

object CalendarRepository {

    private const val BASE_URL =
        "https://raw.githubusercontent.com/darshanghimire03-lgtm/Nepali_Bs_Calender/main/"

    private val cache = HashMap<Int, Map<Int, BsMonthData>>()
    private val handler = Handler(Looper.getMainLooper())

    private data class Entry(
        val year: Int,
        val month: Int,
        val day: Int,
        val ad: AdDate?,
        val weekday: Int?
    )

    private val monthAliases = listOf(
        1 to listOf("baisakh", "baishakh", "vaisakh", "baisak"),
        2 to listOf("jestha", "jyestha", "jeth"),
        3 to listOf("asadh", "ashadh", "asar", "asad"),
        4 to listOf("shrawan", "srawan", "shravan", "saun"),
        5 to listOf("bhadra", "bhadau", "bhado"),
        6 to listOf("ashwin", "ashoj", "asoj", "aswin"),
        7 to listOf("kartik", "kattik"),
        8 to listOf("mangsir", "mangshir", "margashirsha"),
        9 to listOf("poush", "paush", "push"),
        10 to listOf("magh"),
        11 to listOf("falgun", "phalgun", "fagun"),
        12 to listOf("chaitra", "chait")
    )

    fun cachedYear(year: Int): Map<Int, BsMonthData>? = cache[year]

    fun monthLength(year: Int, month: Int): Int? = cache[year]?.get(month)?.days

    fun findToday(): BsDate? {
        val now = Calendar.getInstance()
        val y = now.get(Calendar.YEAR)
        val m = now.get(Calendar.MONTH) + 1
        val d = now.get(Calendar.DAY_OF_MONTH)
        for ((year, months) in cache) {
            for ((month, data) in months) {
                for ((day, ad) in data.adDays) {
                    if (ad.year == y && ad.month == m && ad.day == d) {
                        return BsDate(year, month, day)
                    }
                }
            }
        }
        return null
    }

    fun loadYear(year: Int, onResult: (Map<Int, BsMonthData>?) -> Unit) {
        val cached = cache[year]
        if (cached != null) {
            onResult(cached)
            return
        }
        Thread {
            val parsed = try {
                parseYear(fetch("$BASE_URL$year.json"), year)
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (parsed != null && parsed.isNotEmpty()) {
                    cache[year] = parsed
                }
                onResult(cache[year])
            }
        }.start()
    }

    private fun fetch(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseYear(text: String, year: Int): Map<Int, BsMonthData> {
        val root = JSONTokener(text).nextValue()
        val entries = mutableListOf<Entry>()
        walk(root, null, null, null, entries)

        val result = HashMap<Int, BsMonthData>()
        entries.filter { it.year == year }.groupBy { it.month }.forEach { (month, list) ->
            val sorted = list.sortedBy { it.day }
            val reference = sorted.firstOrNull { weekdayOf(it) != null }
            val start = if (reference != null) {
                (((weekdayOf(reference) ?: 0) - (reference.day - 1)) % 7 + 7) % 7
            } else {
                0
            }
            val adMap = HashMap<Int, AdDate>()
            sorted.forEach { entry -> entry.ad?.let { adMap[entry.day] = it } }
            result[month] = BsMonthData(year, month, sorted.last().day, start, adMap)
        }
        return result
    }

    private fun weekdayOf(entry: Entry): Int? {
        entry.weekday?.let { return it }
        val ad = entry.ad ?: return null
        return GregorianCalendar(ad.year, ad.month - 1, ad.day).get(Calendar.DAY_OF_WEEK) - 1
    }

    private fun walk(node: Any?, year: Int?, month: Int?, dayHint: Int?, out: MutableList<Entry>) {
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walk(node.opt(i), year, month, null, out)
                }
            }
            is JSONObject -> {
                val ny = node.firstInt("year", "bsYear", "bs_year") ?: year
                val nm = node.firstInt("month", "bsMonth", "bs_month", "monthIndex")
                    ?: monthFromName(node.firstString("monthName", "month_name", "month"))
                    ?: month
                val nd = node.firstInt("day", "bsDay", "bs_day", "nepaliDay", "np_day", "dayNumber")
                    ?: dayHint

                val bsTriple = node.firstString(
                    "bs", "bsDate", "bs_date", "nepaliDate", "nepali_date", "np", "npDate"
                )?.let { parseTriple(it) }

                val resolved = bsTriple
                    ?: if (ny != null && nm != null && nd != null) Triple(ny, nm, nd) else null

                if (resolved != null) {
                    val adTriple = node.firstString(
                        "ad", "adDate", "ad_date", "english", "englishDate", "english_date",
                        "eng", "engDate", "gregorian", "date"
                    )?.let { parseTriple(it) } ?: adFromParts(node)
                    out.add(
                        Entry(
                            resolved.first,
                            resolved.second,
                            resolved.third,
                            adTriple?.let { AdDate(it.first, it.second, it.third) },
                            weekdayFrom(node)
                        )
                    )
                    return
                }

                for (key in node.keys()) {
                    val value = node.opt(key)
                    if (value !is JSONObject && value !is JSONArray) continue
                    var childYear = ny
                    var childMonth = nm
                    var childDay: Int? = null
                    val number = key.toIntOrNull()
                    if (number != null) {
                        if (number in 1000..9999) {
                            childYear = number
                        } else if (childYear != null && childMonth == null && number in 1..12) {
                            childMonth = number
                        } else if (childMonth != null && number in 1..32) {
                            childDay = number
                        }
                    } else {
                        monthFromName(key)?.let { childMonth = it }
                    }
                    walk(value, childYear, childMonth, childDay, out)
                }
            }
        }
    }

    private fun adFromParts(node: JSONObject): Triple<Int, Int, Int>? {
        val y = node.firstInt("adYear", "ad_year", "engYear") ?: return null
        val m = node.firstInt("adMonth", "ad_month", "engMonth") ?: return null
        val d = node.firstInt("adDay", "ad_day", "engDay") ?: return null
        return Triple(y, m, d)
    }

    private fun weekdayFrom(node: JSONObject): Int? {
        val text = node.firstString("weekday", "dayOfWeek", "day_of_week", "dow", "dayName", "day_name")
        if (text != null) {
            val lower = text.trim().lowercase(Locale.ROOT)
            val names = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
            val index = names.indexOfFirst { lower.startsWith(it) }
            if (index >= 0) return index
        }
        val number = node.firstInt("weekday", "dayOfWeek", "day_of_week", "dow")
        return if (number != null && number in 0..6) number else null
    }

    private fun monthFromName(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        val lower = name.trim().lowercase(Locale.ROOT)
        for ((index, aliases) in monthAliases) {
            if (aliases.any { lower.startsWith(it) }) return index
        }
        return null
    }

    private fun parseTriple(text: String): Triple<Int, Int, Int>? {
        val match = Regex("(\\d+)\\D+(\\d+)\\D+(\\d+)").find(text) ?: return null
        val a = match.groupValues[1].toIntOrNull() ?: return null
        val b = match.groupValues[2].toIntOrNull() ?: return null
        val c = match.groupValues[3].toIntOrNull() ?: return null
        return when {
            a > 31 -> Triple(a, b, c)
            c > 31 -> Triple(c, b, a)
            else -> null
        }
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            if (value is Number) return value.toInt()
            if (value is String) {
                value.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }
}
package com.durbar.ai.data.model

data class BsDate(val year: Int, val month: Int, val day: Int) {
    fun key(): Int = year * 10000 + month * 100 + day

    companion object {
        fun fromKey(key: Int): BsDate = BsDate(key / 10000, (key / 100) % 100, key % 100)
    }
}

data class AdDate(val year: Int, val month: Int, val day: Int)

data class BsMonthData(
    val year: Int,
    val month: Int,
    val days: Int,
    val startWeekday: Int,
    val adDays: Map<Int, AdDate>
)

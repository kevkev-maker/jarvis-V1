package com.example.myapp.jarvis

import java.util.Calendar
import java.util.Locale

/**
 * Understands spoken reminder times, on the phone, without AI. Examples:
 *   "in 10 minutes to drink water"      "at 6 pm to call dad"
 *   "tomorrow at 9 to submit the form"  "on friday evening about the party"
 *   "in half an hour"                    "tonight to pay the rent"
 * Returns null if no time could be found.
 */
object ReminderParser {

    data class Parsed(val text: String, val at: Long)

    private val NUM = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60
    )
    private val RELATIVE = Regex(
        "\\b(?:in|after) (\\d+|half an?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|" +
            "fifteen|twenty|thirty|forty|fifty|sixty) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b"
    )
    private val CLOCK_AMPM = Regex("\\b(?:at )?(\\d{1,2})(?::(\\d{2}))? ?(am|pm)\\b")
    private val CLOCK_AT = Regex("\\bat (\\d{1,2})(?::(\\d{2}))?\\b")
    private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val WEEKDAY = Regex("\\b(?:on |next |this )?(${DAYS.joinToString("|")})\\b")
    private val PART = Regex("\\b(?:this |in the |at )?(morning|afternoon|evening|night|tonight)\\b")

    private val LEAD = setOf("to", "that", "about", "me", "please", "remind", "for", "at", "on", "in", "and", "by")
    private val TRAIL = setOf("please", "at", "on", "in", "by", "for", "and", "to")
    private val PRONOUNS = mapOf(
        "my" to "your", "i" to "you", "i'm" to "you're", "i'll" to "you'll", "me" to "you",
        "myself" to "yourself", "mine" to "yours"
    )

    fun parse(input: String, nowMs: Long = System.currentTimeMillis()): Parsed? {
        var s = " " + input.lowercase(Locale.ENGLISH).replace(".", "")
            .replace(Regex("\\s+"), " ").trim() + " "
        s = s.replace(Regex("\\bnoon\\b"), "12 pm").replace(Regex("\\bmidnight\\b"), "12 am")
        var rest = s

        // 1) Relative: "in 10 minutes"
        val rel = RELATIVE.find(s)
        if (rel != null) {
            val g = rel.groupValues[1]
            val amount = when {
                g.startsWith("half") -> 0.5
                g.all { it.isDigit() } -> g.toDouble()
                else -> (NUM[g] ?: 1).toDouble()
            }
            val unit = rel.groupValues[2]
            val unitMs = when {
                unit.startsWith("sec") -> 1_000L
                unit.startsWith("min") -> 60_000L
                unit.startsWith("h") -> 3_600_000L
                else -> 86_400_000L
            }
            rest = rest.replace(rel.value, " ")
            return Parsed(cleanText(rest), nowMs + (amount * unitMs).toLong())
        }

        // 2) Day: today / tomorrow / weekday
        var dayOffset: Int? = null
        when {
            s.contains(" day after tomorrow ") -> { dayOffset = 2; rest = rest.replace("day after tomorrow", " ") }
            s.contains(" tomorrow ") -> { dayOffset = 1; rest = rest.replace(" tomorrow ", " ") }
            s.contains(" today ") -> { dayOffset = 0; rest = rest.replace(" today ", " ") }
        }
        if (dayOffset == null) {
            val wd = WEEKDAY.find(s)
            if (wd != null) {
                val target = DAYS.indexOf(wd.groupValues[1])
                val now = Calendar.getInstance().apply { timeInMillis = nowMs }
                val todayIdx = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Monday = 0
                var diff = (target - todayIdx + 7) % 7
                if (diff == 0) diff = 7
                dayOffset = diff
                rest = rest.replace(wd.value, " ")
            }
        }

        // 3) Clock time
        var hour = -1
        var minute = 0
        var ambiguous = false
        val ampm = CLOCK_AMPM.find(s)
        if (ampm != null) {
            val h = ampm.groupValues[1].toInt()
            val m = ampm.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 1..12 && m in 0..59) {
                hour = h % 12 + if (ampm.groupValues[3] == "pm") 12 else 0
                minute = m
                rest = rest.replace(ampm.value, " ")
            }
        } else {
            val atMatch = CLOCK_AT.find(s)
            if (atMatch != null) {
                val h = atMatch.groupValues[1].toInt()
                val m = atMatch.groupValues[2].ifEmpty { "0" }.toInt()
                if (h in 0..23 && m in 0..59) {
                    hour = h
                    minute = m
                    ambiguous = h in 1..12
                    rest = rest.replace(atMatch.value, " ")
                }
            }
        }

        // 4) Part of day: morning / evening / tonight
        var part: String? = null
        val pm = PART.find(s)
        if (pm != null) {
            part = pm.groupValues[1]
            rest = rest.replace(pm.value, " ")
            if (part == "tonight" && dayOffset == null) dayOffset = 0
        }

        if (hour == -1 && part == null && dayOffset == null) return null

        if (hour == -1) {
            hour = when (part) {
                "morning" -> 8
                "afternoon" -> 15
                "evening" -> 18
                "night", "tonight" -> 21
                else -> 9   // only a day was given, e.g. "tomorrow"
            }
            minute = 0
            ambiguous = false
        }

        val day = dayOffset
        val whenMs: Long = if (ambiguous) {
            val h12 = hour % 12
            when {
                part == "morning" -> resolve(nowMs, day, h12, minute)
                part != null -> resolve(nowMs, day, h12 + 12, minute)
                day != null && day > 0 -> resolve(nowMs, day, if (hour in 7..12) hour else h12 + 12, minute)
                else -> {
                    // Today: earliest future of "h am" and "h pm"
                    listOf(at(nowMs, 0, h12, minute), at(nowMs, 0, h12 + 12, minute))
                        .firstOrNull { it > nowMs } ?: at(nowMs, 1, h12, minute)
                }
            }
        } else {
            resolve(nowMs, day, hour, minute)
        }

        if (whenMs <= nowMs) return null
        return Parsed(cleanText(rest), whenMs)
    }

    /** Explicit day → that day. No day → today if still ahead, else tomorrow. */
    private fun resolve(nowMs: Long, day: Int?, hour: Int, minute: Int): Long {
        if (day != null) return at(nowMs, day, hour, minute)
        val today = at(nowMs, 0, hour, minute)
        return if (today > nowMs) today else at(nowMs, 1, hour, minute)
    }

    private fun at(nowMs: Long, offsetDays: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, offsetDays)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** "to call my mom" → "call your mom". */
    private fun cleanText(raw: String): String {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first() in LEAD) words.removeAt(0)
        while (words.isNotEmpty() && words.last() in TRAIL) words.removeAt(words.lastIndex)
        val text = words.joinToString(" ") { PRONOUNS[it] ?: it }
        return text.ifEmpty { "your reminder" }
    }
}

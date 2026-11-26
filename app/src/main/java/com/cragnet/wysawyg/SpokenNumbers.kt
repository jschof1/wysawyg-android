package com.cragnet.wysawyg

/**
 * Writes spoken numbers as digits: "twenty five" → 25, "one hundred and twenty" → 120,
 * "two point five" → 2.5, "twenty first" → 21st, "three thirty" → 3:30, "twenty twenty six"
 * → 2026, "oh seven seven double oh" → 07700. Times of day are written one way: "three pm",
 * "3PM" and "3 p m" all become "3 p.m.". Other words around numbers (pounds, percent) stay as said.
 *
 * Words that are usually not counting are left alone: "one" on its own ("the blue one", "no
 * one") unless a time, unit or "at"/"number" marks it as a number; "a hundred" and "a million"
 * on their own; "first", "second" and "third" unless they are part of a date.
 */
object SpokenNumbers {
    private val units = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)
    private val teens = mapOf("ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val scales = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val ordinals = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11,
        "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16,
        "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30,
        "fortieth" to 40, "fiftieth" to 50, "sixtieth" to 60, "seventieth" to 70, "eightieth" to 80,
        "ninetieth" to 90, "hundredth" to 100, "thousandth" to 1000)
    private val months = setOf("january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
        "sep", "sept", "oct", "nov", "dec")
    /** Words before a lone "one" at the end of a phrase that make it a number: "see you at one." */
    private val numberBefore = setOf("at", "number", "page", "room", "level", "step", "plus", "minus", "times",
        "by", "until", "till", "til", "around", "about", "past", "equals")
    private val numberAfter = setOf("am", "pm", "a.m", "p.m", "o'clock", "o’clock", "percent", "per", "pound",
        "pounds", "quid", "pence", "p", "dollar", "dollars", "euro", "euros", "minute", "minutes", "min", "mins",
        "hour", "hours", "hr", "hrs", "week", "weeks", "month", "months",
        "year", "years", "km", "kilometre", "kilometres", "kilometer", "kilometers", "mile", "miles", "metre",
        "metres", "meter", "meters", "kg", "kilo", "kilos", "gram", "grams", "litre", "litres", "liter", "liters",
        "out", "degree", "degrees", "star", "stars")
    /** A lone "one" in these phrases is never a count, even next to a number cue. */
    private val notOne = setOf("no", "any", "every", "some", "the", "this", "that", "which", "each", "another",
        "a", "little", "big", "other", "last", "next", "only", "same")
    /** Words that make "two fifty" a quantity rather than the time 2:50. */
    private val notTime = setOf("pound", "pounds", "quid", "pence", "p", "dollar", "dollars", "euro", "euros",
        "percent", "per", "grand", "k")

    private val word = Regex("""[\p{L}][\p{L}'’.]*""")

    private data class Token(val text: String, val lower: String, val start: Int, val end: Int)

    private class Parsed(val consumed: Int, val text: String, val value: Long?, val small: Boolean)

    /** "3pm", "3 PM", "3 p m", "3:30 p.m" (any hour 1 to 12) → "3 p.m.", "3:30 p.m.". */
    private val meridiem = Regex("""(?<![\d:.,])\b(\d{1,2})(:[0-5]\d)?\s?([AaPp])\.?\s?[Mm]\b\.?""")

    fun format(input: String): String = times(words(input))

    fun times(input: String): String = meridiem.replace(input) { m ->
        val hour = m.groupValues[1].toInt()
        if (hour !in 1..12) m.value else "$hour${m.groupValues[2]} ${m.groupValues[3].lowercase()}.m."
    }

    /** True when [text] ends with a time marker, whose last dot is not a full stop. */
    fun endsWithTime(text: String): Boolean = text.endsWith("a.m.") || text.endsWith("p.m.")

    private fun words(input: String): String {
        if (input.isEmpty()) return input
        val tokens = word.findAll(input).map { m ->
            val raw = m.value.trimEnd('.')
            Token(raw, raw.lowercase().replace('’', '\''), m.range.first, m.range.first + raw.length)
        }.toList()
        if (tokens.none { isNumberWord(it.lower) || it.lower == "a" }) return input
        val out = StringBuilder()
        var copied = 0
        var i = 0
        while (i < tokens.size) {
            val parsed = parseAt(tokens, i, input)
            if (parsed == null) { i++; continue }
            out.append(input, copied, tokens[i].start).append(parsed.text)
            copied = tokens[i + parsed.consumed - 1].end
            i += parsed.consumed
        }
        out.append(input, copied, input.length)
        return out.toString()
    }

    private fun isNumberWord(w: String) = w in units || w in teens || w in tens || w in ordinals || w == "hundred" ||
        w in scales

    /** True when the two tokens are next to each other with only a space or hyphen between. */
    private fun joined(tokens: List<Token>, a: Int, input: String): Boolean {
        if (a + 1 >= tokens.size) return false
        val gap = input.substring(tokens[a].end, tokens[a + 1].start)
        return gap == " " || gap == "-"
    }

    /** The run of number-ish words starting at [from] that sit together in the text. */
    private fun run(tokens: List<Token>, from: Int, input: String): List<String> {
        val words = mutableListOf(tokens[from].lower)
        var k = from
        while (joined(tokens, k, input)) { k++; words += tokens[k].lower }
        return words
    }

    private fun parseAt(tokens: List<Token>, i: Int, input: String): Parsed? {
        val first = tokens[i].lower
        if (!isNumberWord(first) && first != "a" && first != "oh") return null
        val words = run(tokens, i, input)
        val prevToken = tokens.getOrNull(i - 1)?.takeIf { joined(tokens, i - 1, input) }
        val prev = prevToken?.lower
        digitString(words)?.let { return it }
        if (first == "oh") return null
        val number = cardinal(words) ?: return ordinalOnly(words, prevToken, tokens.getOrNull(i + 1), tokens.getOrNull(i + 2))
        if (number.consumed == 1 && first == "one" && !loneOneIsNumber(prev, words.getOrNull(1))) return null
        if (first == "a" && number.consumed <= 2) return null
        val after = words.drop(number.consumed)
        pair(number, after)?.let { return it }
        return number
    }

    private fun loneOneIsNumber(prev: String?, next: String?): Boolean = when {
        next in numberAfter -> true
        prev in notOne -> false
        next != null && isNumberWord(next) || prev != null && isNumberWord(prev) -> true
        else -> prev in numberBefore && next == null
    }

    /** "three thirty" → 3:30, "ten oh five" → 10:05, "nineteen ninety" → 1990, "twenty twenty six" → 2026. */
    private fun pair(first: Parsed, rest: List<String>): Parsed? {
        if (rest.isEmpty() || first.value == null || !first.small) return null
        val ohMinute = rest[0] == "oh" && rest.getOrNull(1) in units && units[rest[1]]!! > 0
        val second = if (ohMinute) Parsed(2, "0" + units[rest[1]], units[rest[1]]!!.toLong(), true)
            else cardinal(rest)?.takeIf { it.small && it.value != null && it.value >= 10 } ?: return null
        val following = rest.getOrNull(second.consumed)
        val h = first.value
        return when {
            following in notTime -> null
            h in 1..12 && second.value!! in 1..59 ->
                Parsed(first.consumed + second.consumed, "$h:" + second.value.toString().padStart(2, '0'), null, false)
            h in 10..99 -> Parsed(first.consumed + second.consumed, (h * 100 + second.value!!).toString(), null, false)
            else -> null
        }
    }

    /** "oh seven seven double nine" → 07799. Three or more digits spoken one by one. */
    private fun digitString(words: List<String>): Parsed? {
        val digits = StringBuilder()
        var used = 0
        var k = 0
        while (k < words.size) {
            val w = words[k]
            val repeat = when (w) { "double" -> 2; "triple" -> 3; else -> 1 }
            val digitWord = if (repeat > 1) words.getOrNull(k + 1) else w
            val d = when (digitWord) { "oh" -> 0; null -> null; else -> units[digitWord] } ?: break
            repeat(repeat) { digits.append(d) }
            k += if (repeat > 1) 2 else 1
            used = k
        }
        // Stop before a tens word, so "one two twenty" is not a phone number.
        if (digits.length < 3 || words.getOrNull(used)?.let { it in tens || it in teens || it == "hundred" || it in scales } == true) return null
        return Parsed(used, digits.toString(), null, false)
    }

    /** A lone ordinal: "fifth" → 5th; "first", "second", "third" only in dates ("the third of May"). */
    private fun ordinalOnly(words: List<String>, prev: Token?, next: Token?, afterNext: Token?): Parsed? {
        val value = ordinals[words[0]] ?: return null
        if (prev?.lower == "a" || prev?.lower == "an") return null
        // Months are capitalised by the speech engine, so "the second may be" is not a date.
        fun month(token: Token?) = token != null && token.lower in months && token.text.first().isUpperCase()
        val dated = month(prev) || month(next) || next?.lower == "of" && month(afterNext)
        if (value <= 3 && !dated) return null
        if (value <= 3 && !dated) return null
        return Parsed(1, suffix(value.toLong()), null, false)
    }

    /**
     * Reads one cardinal number from the start of [words], with an optional decimal part or
     * ordinal ending. Returns null when the first word does not start a number.
     */
    private fun cardinal(words: List<String>): Parsed? {
        var total = 0L
        var current = 0L
        var last = ""          // unit, teen, tens, hundred, scale, and
        var smallestScale = Long.MAX_VALUE
        var consumed = 0
        var ordinal = false
        var onlyScale: Long? = null
        loop@ for ((k, w) in words.withIndex()) {
            val next = words.getOrNull(k + 1)
            val startsSegment = last == "" || last == "hundred" || last == "scale" || last == "and"
            when {
                w == "a" && k == 0 && (next == "hundred" || next == "thousand") -> { current = 1; last = "unit" }
                w == "zero" -> { if (last != "") break@loop; return Parsed(1, "0", 0, true) }
                w in units && (startsSegment || last == "tens") -> { current += units[w]!!; last = "unit" }
                w in teens && startsSegment -> { current += teens[w]!!; last = "teen" }
                w in tens && startsSegment -> { current += tens[w]!!; last = "tens" }
                w == "hundred" && current in 1..99 && (last == "unit" || last == "teen" || last == "tens") &&
                    current % 100 == current -> { current *= 100; last = "hundred" }
                w in scales && (current > 0) && scales[w]!! < smallestScale && last != "and" -> {
                    val scale = scales[w]!!
                    onlyScale = if (total == 0L && scale >= 1_000_000) scale else null
                    total += current * scale
                    current = 0
                    smallestScale = scale
                    last = "scale"
                }
                w == "and" && (last == "hundred" || last == "scale") &&
                    (next in units && next != "zero" || next in teens || next in tens) -> { last = "and"; consumed = k; continue@loop }
                w in ordinals && (last == "tens" && (ordinals[w]!! in 1..9)) -> {
                    current += ordinals[w]!!; ordinal = true; consumed = k + 1; break@loop
                }
                w in ordinals && (last == "hundred" || last == "scale" || last == "and") && ordinals[w]!! < 100 -> {
                    current += ordinals[w]!!; ordinal = true; consumed = k + 1; break@loop
                }
                (w == "hundredth" || w == "thousandth") && current in 1..99 && last != "and" -> {
                    current *= ordinals[w]!!; ordinal = true; consumed = k + 1; break@loop
                }
                else -> break@loop
            }
            if (last != "scale") onlyScale = null
            consumed = k + 1
        }
        if (consumed == 0) return null
        val value = total + current
        val scaleOnlyWords = onlyScale != null && current == 0L
        if (ordinal) return Parsed(consumed, suffix(value), null, false)
        val small = consumed <= 2 && smallestScale == Long.MAX_VALUE && last != "hundred" && value < 100
        // Decimals: "two point five" → 2.5, "one point two million" → 1.2 million.
        if (words.getOrNull(consumed) == "point" && !scaleOnlyWords && smallestScale == Long.MAX_VALUE) {
            val decimals = StringBuilder()
            var k = consumed + 1
            while (k < words.size) {
                val d = when (words[k]) { "oh" -> 0; else -> units[words[k]] } ?: break
                decimals.append(d); k++
            }
            if (decimals.isNotEmpty()) {
                val scaleWord = words.getOrNull(k)?.takeIf { it == "million" || it == "billion" }
                val text = "$value.$decimals" + (scaleWord?.let { " $it" } ?: "")
                return Parsed(k + if (scaleWord != null) 1 else 0, text, null, false)
            }
        }
        if (scaleOnlyWords) {
            val scaleWord = if (onlyScale == 1_000_000_000L) "billion" else "million"
            return Parsed(consumed, "${value / onlyScale!!} $scaleWord", value, false)
        }
        return Parsed(consumed, group(value), value, small)
    }

    private fun group(value: Long): String =
        if (value < 10_000) value.toString() else "%,d".format(java.util.Locale.UK, value)

    private fun suffix(value: Long): String {
        val tail = when {
            value % 100 in 11..13 -> "th"
            value % 10 == 1L -> "st"
            value % 10 == 2L -> "nd"
            value % 10 == 3L -> "rd"
            else -> "th"
        }
        return group(value) + tail
    }
}

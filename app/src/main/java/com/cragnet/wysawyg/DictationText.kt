package com.cragnet.wysawyg

/** Builds one cursor edit without losing the surrounding text or selected range. */
object DictationText {
    data class Edit(val text: String, val cursor: Int)

    fun insert(existing: String, selectionStart: Int, selectionEnd: Int, spoken: String): Edit {
        val start = if (selectionStart < 0) existing.length else selectionStart.coerceAtMost(existing.length)
        val end = if (selectionEnd < 0) start else selectionEnd.coerceAtMost(existing.length)
        val from = minOf(start, end)
        val to = maxOf(start, end)
        // Spoken "new line" commands may start or end the transcript; keep those line breaks.
        val words = spoken.trim(' ', '\t').let { if (it.isBlank() && !it.contains('\n')) "" else it }
        if (words.isEmpty()) return Edit(existing, to)
        val before = existing.substring(0, from)
        val after = existing.substring(to)
        val leftSpace = before.lastOrNull()?.let { !it.isWhitespace() && it !in "([{\"“" } == true &&
            words.first() !in ".,!?;:)]}\n"
        val rightSpace = after.firstOrNull()?.let { !it.isWhitespace() && it !in ".,!?;:)]}\"”" } == true &&
            words.last() !in "([{\n"
        val insertion = (if (leftSpace) " " else "") + words + (if (rightSpace) " " else "")
        return Edit(before + insertion + after, before.length + insertion.length)
    }
}

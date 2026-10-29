package com.cragnet.wysawyg

data class DirectTextInsertion(val text: String, val cursor: Int) {
    fun confirmed(text: String, selectionStart: Int, selectionEnd: Int, offset: Int): Boolean =
        offset >= 0 && selectionStart == selectionEnd && selectionStart in this.text.length..text.length &&
            offset.toLong() + selectionStart == cursor.toLong() &&
            text.regionMatches(selectionStart - this.text.length, this.text, 0, this.text.length)

    companion object {
        fun plan(text: String, selectionStart: Int, selectionEnd: Int, offset: Int, spoken: String): DirectTextInsertion? {
            if (spoken.isBlank() && !spoken.contains('\n')) return null
            if (offset < 0 || selectionStart !in 0..text.length || selectionEnd !in 0..text.length) return null
            val from = minOf(selectionStart, selectionEnd)
            val edit = DictationText.insert(text, selectionStart, selectionEnd, spoken)
            val insertion = edit.text.substring(from, edit.cursor)
            val cursor = offset.toLong() + edit.cursor
            if (insertion.isEmpty() || cursor > Int.MAX_VALUE) return null
            return DirectTextInsertion(insertion, cursor.toInt())
        }
    }
}

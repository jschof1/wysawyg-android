package com.cragnet.wysawyg

/**
 * Deterministic, instant interpretation of a raw transcript for its destination: voice
 * commands, filler words, the app's writing style and the text already around the cursor.
 * It only removes words or changes punctuation, spacing and capitals; it never adds words.
 */
object DictationFormatter {
    private const val INTERJECTIONS = "yeah|yes|yep|yup|yea|no|nope|nah|ok|okay|oh|so|well|hey|hi|hiya|hello|right|" +
        "cool|sure|alright|all right|haha|hahaha|lol|aw|aww|ah|thanks|thank you|cheers|sorry|honestly|anyway|anyways|" +
        "wait|great|nice|perfect|lovely|amazing|morning|good morning|night|fair enough|of course|no worries|bless"
    private val leadingInterjection = Regex("""^(?i)($INTERJECTIONS),?(?=\s)""")
    private val discourseBefore = Regex("""(?i),\s+(like|you know|i mean|basically|literally|though|too|then|mate|man|dude|bro|babe|hun|guys|lol|haha|x+)\b""")
    private val discourseAfter = Regex("""(?i)\b(like|you know|i mean|basically|literally|actually|honestly),(?=\s)""")
    private val conjunctionComma = Regex("""(?i),\s+(and|but|so|or|because|cause|cos|then|if|when)\b""")
    /** Case-sensitive so "ER" (emergency room) survives while "Er," and "erm" go. */
    private val filler = Regex(""",?\s*(?<![\w@/.\-'’])(?:[Uu]+[Mm]+|[Uu]+[Hh]+[Mm]*|[Ee]r+m*|[Aa]h+m+)(?![\w@/\-'’]|\.\w)[,…]*""")
    private val scratch = Regex("""(?i)(?:^|[,.!?;:])\s*scratch that(?:[,.!?;:]+|$)\s*""")
    private val newLine = Regex("""(?i)\bnew\s?(line|paragraph)\b""")
    private val sentenceStart = Regex("""(^|[.!?]\s+|\n\s*)(\p{L})""")
    private val iWords = Regex("""^I('|’)?(m|ll|ve|d)?\b""")
    /** Words that are safe to lower-case when dictation continues an existing sentence. */
    private val commonStarters = ("a an the and but or so then also just yes yeah no not it it's its this that these those " +
        "there there's they they're their we we're our you you're your he she his her him them my me to of in on at for " +
        "with from by about as if when where what why how which who can could would will should shall may might must do " +
        "does did is are was were be been have has had maybe perhaps please thanks thank sorry okay ok well actually " +
        "really still even only some any all every each more most other another here now later today tomorrow tonight " +
        "yesterday because since until after before while though although let let's get got go going make see see you " +
        "one two three four five six seven eight nine ten great good nice cool sure lol haha oh hey hi").split(" ").toSet()

    fun format(raw: String, context: DictationContext, settings: StyleSettings): String =
        finish(prepare(raw, context, settings), context, settings)

    /** Word-level interpretation: voice commands, fillers and self-corrections. */
    fun prepare(raw: String, context: DictationContext, settings: StyleSettings): String {
        var text = raw.replace(Regex("""[ \t]+"""), " ").trim()
        if (text.isEmpty() || context.isShortField) return text
        if (settings.voiceCommands) text = applyCommands(text, multiline = context.isMultiLine && !context.isShortField)
        if (settings.removeFillers) text = applyCorrections(removeFillers(text))
        return tidy(text)
    }

    /** Punctuation and capitals for the destination app and the text around the cursor. */
    fun finish(prepared: String, context: DictationContext, settings: StyleSettings): String {
        var text = tidy(prepared)
        if (text.isEmpty()) return text
        if (context.isShortField) return text.replace('\n', ' ').trimEnd('.').trim()
        val style = if (settings.matchApp) settings.styleFor(context.category) else null
        text = when (style) {
            WritingStyle.FORMAL -> capitaliseSentences(text)
            WritingStyle.CASUAL -> capitaliseSentences(lightenCommas(text))
            WritingStyle.VERY_CASUAL -> veryCasual(lightenCommas(text))
            null -> if (settings.removeFillers || settings.voiceCommands) capitaliseSentences(text) else text
        }
        return fitCursor(text, context, style)
    }

    /** "New line", "new paragraph" and "scratch that" (deletes the last phrase), when spoken as commands. */
    fun applyCommands(input: String, multiline: Boolean = true): String {
        var text = input
        while (true) {
            val match = scratch.find(text) ?: break
            val kept = text.substring(0, match.range.first).trimEnd().trimEnd('.', ',', '!', '?', ';', ':').trimEnd()
            // Deletes only the last phrase, so "Sounds good, see you at 6. Scratch that, see you at 7"
            // keeps "Sounds good".
            val boundary = kept.indices.lastOrNull { i ->
                kept[i] in ".!?\n;" || kept[i] == ',' && !(kept.getOrNull(i - 1)?.isDigit() == true && kept.getOrNull(i + 1)?.isDigit() == true)
            } ?: -1
            val prefix = if (boundary >= 0) kept.substring(0, boundary + 1).let { if (it.endsWith(',') || it.endsWith(';')) it.dropLast(1) + "," else it } else ""
            val rest = text.substring(match.range.last + 1)
            text = if (prefix.isEmpty()) rest else if (prefix.endsWith('\n')) prefix + rest else "$prefix $rest"
        }
        var from = 0
        while (true) {
            val match = newLine.find(text, from) ?: break
            val before = text.substring(0, match.range.first)
            val after = text.substring(match.range.last + 1)
            val spokenAlone = before.isBlank() || before.trimEnd().lastOrNull() in ".,!?;:\n".toList() ||
                after.isBlank() || after.firstOrNull() in ".,!?;:".toList()
            if (!spokenAlone) { from = match.range.last + 1; continue }
            val breakText = when {
                !multiline -> " "
                match.groupValues[1].equals("paragraph", true) -> "\n\n"
                else -> "\n"
            }
            val left = before.trimEnd().trimEnd(',', ';', ' ')
            val right = after.trimStart('.', ',', '!', '?', ';', ':', ' ')
            text = left + breakText + right
            from = left.length + breakText.length
        }
        return text
    }

    fun removeFillers(text: String): String = filler.replace(text, " ")

    private val strongCorrection = Regex("""(?i)^(.*?[\p{L}\p{N}])[,;]\s+(?:no,? wait|wait,? no|no,? sorry|sorry,? (?:i mean|no)|i mean to say|or rather)[,:]?\s+(.+?)([.!?]*)$""")
    /** A short correction that the sentence then continues after: "ask Tom, no wait, Sarah, to call". */
    private val midCorrection = Regex("""(?i)^(.*?[\p{L}\p{N}])[,;]\s+(?:no,? wait|wait,? no|no,? sorry|sorry,? (?:i mean|no)|i mean to say|or rather)[,:]?\s+([^,.!?;]+?),\s+([^,.!?;].*?)([.!?]*)$""")
    private val softCorrection = Regex("""(?i)^(.*?[\p{L}\p{N}]),\s+(?:actually|i mean)[,:]?\s+(.+?)([.!?]*)$""")
    /** The speech engine often punctuates a spoken correction as its own sentences:
     * "at five. No wait. Six." Rejoin it so the correction applies within the sentence. */
    private val splitCorrection = Regex("""(?i)([\p{L}\p{N}])[.!?]\s+(no,? wait|wait,? no|no,? sorry|sorry,? (?:i mean|no)|i mean to say|or rather)[.,!?:]?\s+([\p{L}\p{N}][\p{L}\p{N}'’]*)(?=(?:\s+[\p{L}\p{N}'’]+)?(?:[,.!?]|$))""")
    private fun joinSplitCorrections(input: String): String = splitCorrection.replace(input) { match ->
        val word = match.groupValues[3]
        val lower = word.lowercase()
        val fixed = if (lower in numberWords || lower in commonStarters && !iWords.containsMatchIn(word)) lower else word
        "${match.groupValues[1]}, ${match.groupValues[2].lowercase()}, $fixed"
    }

    private val negations = setOf("not", "don't", "dont", "never", "no", "can't", "cannot", "won't", "shouldn't", "didn't", "doesn't")
    private val numberWords = setOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "half", "quarter", "noon", "midday", "midnight")
    private val dayWords = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "today",
        "tomorrow", "tonight", "yesterday", "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "morning", "afternoon", "evening", "weekend", "week", "month")

    private fun kind(word: String): String? {
        val bare = word.lowercase().trim(',', '.', '!', '?', ';', ':', '"')
        return when {
            bare.any(Char::isDigit) || bare in numberWords -> "number"
            bare in dayWords -> "day"
            else -> null
        }
    }

    /**
     * "Coffee at 2, actually 3" becomes "Coffee at 3". The correction replaces the earlier words
     * from the last one it lines up with: the same word ("the red one, no wait, the blue one"),
     * the same kind of word (a number or a day) or another name. Without an anchor it stays as said.
     */
    fun applyCorrections(input: String): String = sentences(joinSplitCorrections(input)).joinToString("") { part ->
        var body = part.trimEnd()
        val spacing = part.substring(body.length)
        fun fix(regex: Regex, maxWords: Int, continues: Boolean = false): String? {
            val match = regex.find(body) ?: return null
            val before = match.groupValues[1].split(' ')
            val correction = match.groupValues[2].trimEnd(',') +
                if (continues) " " + match.groupValues[3] else ""
            val end = match.groupValues[if (continues) 4 else 3]
            val replacement = match.groupValues[2].split(' ').filter { it.isNotBlank() }
            if (replacement.isEmpty() || replacement.size > maxWords) return null
            val first = replacement.first()
            fun name(word: String, index: Int) = index > 0 && word.firstOrNull()?.isUpperCase() == true &&
                !iWords.containsMatchIn(word) && kind(word) == null
            val anchor = before.indexOfLast { it.equals(first, ignoreCase = true) }.takeIf { it >= 0 }
                ?: kind(first)?.let { wanted -> before.indexOfLast { kind(it) == wanted } }?.takeIf { it >= 0 }
                ?: before.indices.lastOrNull { name(before[it], it) }?.takeIf { name(first, 1) }
                ?: return null
            // Never replace more words than the correction has ("five thousand pounds. No wait. Six"
            // stays), and never leave a dangling negation ("Do not send it, no wait, send it now").
            if (before.size - anchor > replacement.size) return null
            val kept = before.subList(0, anchor).joinToString(" ")
            if (kept.split(' ').lastOrNull()?.lowercase()?.trim(',') in negations) return null
            return (if (kept.isEmpty()) "" else "$kept ") + correction + end
        }
        // A sentence can hold more than one correction; each pass removes one cue.
        repeat(3) {
            body = fix(midCorrection, 3, continues = true) ?: fix(strongCorrection, 6) ?: fix(softCorrection, 3) ?: return@repeat
        }
        body + spacing
    }

    private val droppable = setOf("um", "umm", "uh", "uhh", "uhm", "er", "erm", "ah", "ahm")
    private fun words(text: String) = text.lowercase().replace('’', '\'')
        .split(Regex("""[^\p{L}\p{N}']+""")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** True when [rewritten] only changes punctuation, capitals and spacing, apart from dropping
     * filler words. Anything else, even dropping a repeated word, is treated as a model mistake. */
    fun keepsWords(original: String, rewritten: String): Boolean {
        val source = words(original)
        val result = words(rewritten)
        if (result.isEmpty() || rewritten.length > original.length * 3 / 2 + 20) return false
        var i = 0
        fun skippable(index: Int) = source[index] in droppable
        for (word in result) {
            while (i < source.size && source[i] != word && skippable(i)) i++
            if (i >= source.size || source[i] != word) return false
            i++
        }
        return (i until source.size).all(::skippable)
    }

    /** Repairs spacing and punctuation left behind by removals. */
    private fun tidy(input: String): String = input
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex(""" *\n *"""), "\n")
        .replace(Regex("""\s+([,.!?;:])"""), "$1")
        .replace(Regex(""",([,.!?;:])"""), "$1")
        .replace(Regex("""(^|\n)[,.;:] *"""), "$1")
        .replace(Regex("""([.!?])[,;] *"""), "$1 ")
        .trim(' ', '\t')

    /** Casual writing keeps sentence punctuation but drops the commas speech recognisers add. */
    fun lightenCommas(input: String): String {
        var text = discourseBefore.replace(input) { " " + it.groupValues[1] }
        text = discourseAfter.replace(text) { it.groupValues[1] }
        text = conjunctionComma.replace(text) { " " + it.groupValues[1] }
        val out = StringBuilder()
        sentences(text).forEach { sentence ->
            var rest = sentence
            val lead = StringBuilder()
            while (true) {
                val trimmed = rest.trimStart()
                lead.append(rest.substring(0, rest.length - trimmed.length))
                val match = leadingInterjection.find(trimmed) ?: run { rest = trimmed; null } ?: break
                lead.append(match.groupValues[1])
                rest = trimmed.substring(match.range.last + 1)
            }
            out.append(lead).append(dropShortClauseCommas(rest))
        }
        return out.toString()
    }

    /** Drops commas that cut off one or two words ("Hey Sarah, how are you", "come over, later"),
     * but keeps lists such as "milk, eggs, bread" and numbers such as 1,000. */
    private fun dropShortClauseCommas(sentence: String): String {
        val parts = sentence.split(", ")
        if (parts.size < 2) return sentence
        fun words(part: String) = part.trim().split(Regex("""\s+""")).count { it.any(Char::isLetterOrDigit) }
        val middle = parts.subList(1, parts.size - 1).map(::words)
        val listEnd = Regex("""\b(and|or)\b""", RegexOption.IGNORE_CASE).containsMatchIn(parts.last())
        if (parts.size >= 3 && (middle.all { it <= 2 } || (listEnd && middle.all { it <= 3 }))) return sentence
        val out = StringBuilder(parts[0])
        for (i in 1 until parts.size) {
            val short = words(parts[i - 1]) <= 2 || words(parts[i]) <= 2
            out.append(if (short) " " else ", ").append(parts[i])
        }
        return out.toString()
    }

    private fun veryCasual(input: String): String {
        val lowered = sentenceStart.replace(input) { match ->
            val index = match.range.last
            val word = input.substring(index).takeWhile { it.isLetterOrDigit() || it == '\'' || it == '’' }
            val keep = iWords.containsMatchIn(word) || (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() })
            match.groupValues[1] + if (keep) match.groupValues[2] else match.groupValues[2].lowercase()
        }
        return lowered.replace(",", "")
            .replace(Regex("""(?<![.\s])\.(?!\.)(?=\s|$)"""), "")
            .replace(Regex("""[ \t]+"""), " ").trim(' ', '\t')
    }

    private fun capitaliseSentences(input: String): String =
        sentenceStart.replace(input) { it.groupValues[1] + it.groupValues[2].uppercase() }

    /** Fits the transcript into the sentence the cursor is already in. */
    private fun fitCursor(input: String, context: DictationContext, style: WritingStyle?): String {
        var text = input
        val before = context.textBefore.trimEnd(' ', '\t')
        val after = context.textAfter.trimStart(' ', '\t')
        val continuesSentence = before.isNotEmpty() && before.last() !in ".!?\n"
        if (continuesSentence) {
            val first = text.takeWhile { it.isLetterOrDigit() || it == '\'' || it == '’' }
            if (first.lowercase() in commonStarters && !iWords.containsMatchIn(first)) {
                text = text.replaceFirstChar { it.lowercase() }
            }
        } else if (style != null && style != WritingStyle.VERY_CASUAL) {
            text = text.replaceFirstChar { it.uppercase() }
        }
        val endsWithStop = text.endsWith('.') && !text.endsWith("..")
        val next = after.firstOrNull()
        when {
            // Existing punctuation, or a word that may continue this sentence, follows the cursor.
            next != null && next != '\n' && endsWithStop -> text = text.dropLast(1)
            style == WritingStyle.CASUAL && after.isEmpty() && endsWithStop -> text = text.dropLast(1)
            style == WritingStyle.FORMAL && (next == null || next == '\n') && text.last().isLetterOrDigit() -> text += "."
        }
        return text
    }

    /** Splits after sentence punctuation or newlines, keeping every character. */
    private fun sentences(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        Regex("""(?<=[.!?\n])\s*""").findAll(text).forEach { match ->
            val end = match.range.last + 1
            if (end > start && end < text.length) { parts += text.substring(start, end); start = end }
        }
        parts += text.substring(start)
        return parts
    }
}

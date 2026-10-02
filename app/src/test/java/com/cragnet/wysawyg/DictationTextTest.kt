package com.cragnet.wysawyg

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTextTest {
    @Test fun insertsBetweenExistingWordsAndLeavesCursorAfterDictation() {
        assertEquals(DictationText.Edit("Hello brave new world", 16),
            DictationText.insert("Hello world", 6, 6, "brave new"))
    }
    @Test fun replacesOnlyTheSelectedWords() {
        assertEquals("Please call Jack tomorrow.",
            DictationText.insert("Please call Bob tomorrow.", 12, 15, "Jack").text)
    }
    @Test fun handlesReversedSelections() {
        assertEquals("Please call Jack tomorrow.",
            DictationText.insert("Please call Bob tomorrow.", 15, 12, "Jack").text)
    }
    @Test fun doesNotAddASpaceBeforePunctuation() {
        assertEquals("Hello, world!", DictationText.insert("Hello!", 5, 5, ", world").text)
    }
    @Test fun preservesNewlinesAndExistingWhitespace() {
        assertEquals("Notes:\nFirst item\nNext item", DictationText.insert("Notes:\n\nNext item", 7, 7, "First item").text)
    }
    @Test fun appendsWithSpacingWhenSelectionIsUnavailable() {
        assertEquals("Hello world", DictationText.insert("Hello", -1, -1, " world ").text)
    }
    @Test fun insertsInAnEmptyField() {
        assertEquals(DictationText.Edit("Hello", 5), DictationText.insert("", 0, 0, "Hello"))
    }
    @Test fun emptyTranscriptLeavesExistingTextUntouched() {
        assertEquals("Keep this", DictationText.insert("Keep this", 0, 9, "  ").text)
    }
}

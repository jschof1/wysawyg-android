package com.cragnet.wysawyg

import org.junit.Assert.*
import org.junit.Test

class DirectTextInsertionTest {
    @Test fun insertsAtCurrentCursorWithoutRewritingSurroundingText() {
        val edit = requireNotNull(DirectTextInsertion.plan("ab", 1, 1, 40, "hello"))
        assertEquals(" hello ", edit.text)
        assertEquals(48, edit.cursor)
        assertTrue(edit.confirmed("a hello b", 8, 8, 40))
    }

    @Test fun replacesSelectionAndPreservesNewlineCommands() {
        val edit = requireNotNull(DirectTextInsertion.plan("old", 0, 3, 0, "\nnext\n"))
        assertEquals("\nnext\n", edit.text)
        assertTrue(edit.confirmed("\nnext\n", 6, 6, 0))
    }

    @Test fun truncatedChangedOrUnacknowledgedInsertKeepsThePrivateCopy() {
        val edit = requireNotNull(DirectTextInsertion.plan("", 0, 0, 0, "hello"))
        assertFalse(edit.confirmed("hell", 4, 4, 0))
        assertFalse(edit.confirmed("HELLO", 5, 5, 0))
        assertFalse(edit.confirmed("", 0, 0, 0))
        assertFalse(edit.confirmed("hello", 0, 5, 0))
    }

    @Test fun anExistingIdenticalSuffixDoesNotConfirmANewInsertion() {
        val edit = requireNotNull(DirectTextInsertion.plan("hello ", 6, 6, 0, "hello"))
        assertFalse(edit.confirmed("hello ", 6, 6, 0))
        assertTrue(edit.confirmed("hello hello", 11, 11, 0))
    }

    @Test fun unknownOffsetsOrCursorsAreNotGuessed() {
        assertNull(DirectTextInsertion.plan("abc", -1, -1, 0, "hello"))
        assertNull(DirectTextInsertion.plan("abc", 1, 1, -1, "hello"))
        assertNull(DirectTextInsertion.plan("abc", 4, 4, 0, "hello"))
        assertNull(DirectTextInsertion.plan("", 0, 0, 0, " "))
        assertNull(DirectTextInsertion.plan("selected", 0, 8, 0, " "))
    }
}

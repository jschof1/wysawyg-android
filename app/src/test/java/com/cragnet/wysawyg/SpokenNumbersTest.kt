package com.cragnet.wysawyg

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenNumbersTest {
    private fun n(text: String) = SpokenNumbers.format(text)

    @Test fun writesCardinalsAsDigits() {
        assertEquals("I need 25 boxes", n("I need twenty five boxes"))
        assertEquals("I need 25 boxes", n("I need twenty-five boxes"))
        assertEquals("About 120 people came", n("About one hundred and twenty people came"))
        assertEquals("It's 3500 pounds", n("It's three thousand five hundred pounds"))
        assertEquals("We sold 45,000 units", n("We sold forty five thousand units"))
        assertEquals("That's 1,250,000", n("That's one million two hundred and fifty thousand"))
        assertEquals("Grab 2 coffees and 3 teas", n("Grab two coffees and three teas"))
        assertEquals("1500 metres", n("fifteen hundred metres"))
        assertEquals("0 chance", n("Zero chance"))
        assertEquals("0 people", n("zero people"))
        assertEquals("25.", n("Twenty five."))
    }

    @Test fun keepsSentencePunctuationAroundNumbers() {
        assertEquals("See you at 6.", n("See you at six."))
        assertEquals("It's 25, not 30!", n("It's twenty five, not thirty!"))
        assertEquals("5. 6.", n("Five. Six."))
    }

    @Test fun decimalsAndLargeRoundNumbers() {
        assertEquals("It's 2.5 miles", n("It's two point five miles"))
        assertEquals("Version 1.05", n("Version one point oh five"))
        assertEquals("2 million views", n("two million views"))
        assertEquals("3.2 billion", n("three point two billion"))
    }

    @Test fun ordinals() {
        assertEquals("the 21st century", n("the twenty first century"))
        assertEquals("the 5th time", n("the fifth time"))
        assertEquals("on the 3rd of May", n("on the third of May"))
        assertEquals("on May 2nd", n("on May second"))
        assertEquals("the 100th day", n("the one hundredth day"))
        assertEquals("first of all, wait a second", n("first of all, wait a second"))
        assertEquals("the second may be better", n("the second may be better"))
        assertEquals("a tenth of it", n("a tenth of it"))
    }

    @Test fun timesYearsAndPhoneNumbers() {
        assertEquals("Meet at 3:30", n("Meet at three thirty"))
        assertEquals("Leave at 10:05 tomorrow", n("Leave at ten oh five tomorrow"))
        assertEquals("Back in 2026", n("Back in twenty twenty six"))
        assertEquals("Born in 1990", n("Born in nineteen ninety"))
        assertEquals("Call 07700900123", n("Call oh seven seven double oh nine double oh one two three"))
    }

    @Test fun timesOfDay() {
        assertEquals("Meet at 1 p.m.", n("Meet at one pm"))
        assertEquals("3 p.m. or 4 p.m. works", n("three PM or four p m works"))
        assertEquals("from 9 a.m. to 5:30 p.m.", n("from nine am to five thirty pm"))
        assertEquals("at 11 a.m. tomorrow", n("at eleven a.m. tomorrow"))
        assertEquals("at 12 p.m.", n("at twelve p.m."))
        assertEquals("Call at 3 p.m.", n("Call at 3pm."))
        assertEquals("at 7:45 p.m.", n("at 7:45 PM"))
        assertEquals("1 o'clock", n("one o'clock"))
        assertEquals("I am 5 amazing people", n("I am five amazing people"))
        assertEquals("15 pm", n("15 pm"))
    }

    @Test fun leavesWordsThatAreNotCounting() {
        assertEquals("Pick the blue one", n("Pick the blue one"))
        assertEquals("No one came", n("No one came"))
        assertEquals("one of them said one day", n("one of them said one day"))
        assertEquals("at one point", n("at one point"))
        assertEquals("thanks a million", n("thanks a million"))
        assertEquals("a hundred percent", n("a hundred percent"))
        assertEquals("someone and everyone", n("someone and everyone"))
        assertEquals("Oh nice", n("Oh nice"))
        assertEquals("often tension", n("often tension"))
    }

    @Test fun convertsALoneOneWhenItIsANumber() {
        assertEquals("See you at 1.", n("See you at one."))
        assertEquals("1 p.m. works", n("one pm works"))
        assertEquals("1 hour left", n("one hour left"))
        assertEquals("1 out of 10", n("one out of ten"))
        assertEquals("120 guests", n("a hundred and twenty guests"))
    }

    @Test fun formatterUsesItAndItCanBeTurnedOff() {
        val notes = DictationContext("com.example.notes", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val whatsapp = DictationContext("com.whatsapp", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertEquals("I'll be there at 6 with 25 chairs.", DictationFormatter.format("I'll be there at six with twenty five chairs", notes, StyleSettings()))
        assertEquals("I'll be there at six.", DictationFormatter.format("I'll be there at six", notes, StyleSettings(numbersAsDigits = false)))
        val veryCasual = StyleSettings(styles = mapOf(AppCategory.PERSONAL to WritingStyle.VERY_CASUAL))
        val gmail = DictationContext("com.google.android.gm", InputType.TYPE_CLASS_TEXT)
        assertEquals("See you at 3 p.m. tomorrow.", DictationFormatter.format("see you at three pm tomorrow", gmail, StyleSettings()))
        assertEquals("See you at 3 p.m.", DictationFormatter.format("See you at three pm.", whatsapp, StyleSettings()))
        assertEquals("Ok 4 p.m. then. Bring snacks", DictationFormatter.format("Ok, four pm then. Bring snacks.", whatsapp, StyleSettings()))
        assertEquals("see you at 3 p.m. tomorrow", DictationFormatter.format("See you at three pm tomorrow.", whatsapp, veryCasual))
        assertEquals("we made 25,000 today", DictationFormatter.format("We made twenty five thousand, today.", whatsapp, veryCasual))
    }
}

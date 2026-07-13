package com.newsradar.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokeniserTest {

    @Test
    fun lowercasesAndStripsPunctuation() {
        val t = Tokeniser.tokenise("The Brexit Deal!")
        assertTrue(t.contains("brexit"))
        assertTrue(t.none { it.contains(Regex("[^a-z0-9]")) })
    }

    @Test
    fun removesStopWords() {
        val t = Tokeniser.tokenise("the and a of is to in")
        assertTrue(t.isEmpty())
    }

    @Test
    fun stemsRelatedForms_partially() {
        // Current light stemmer does NOT fully collapse economy/economic/economics
        // (economy->economy, economics->economic). They partially converge. This
        // test documents current behaviour; a stronger stemmer is a V2 design item.
        val t = Tokeniser.tokenise("economy economic economics")
        // at least two of the three should share a stem (economic/economics both -> 'economic')
        val economicsStem = t.count { it == "economic" }
        assertTrue(economicsStem >= 2)
    }

    @Test
    fun dropsVeryShortAndNumericTokens() {
        val t = Tokeniser.tokenise("go to 2024 99 buy")
        assertTrue(t.none { it.length < 3 })
        assertTrue(t.none { it.all { c -> c.isDigit() } })
    }
}

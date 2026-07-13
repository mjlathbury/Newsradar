package com.newsradar.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityExtractorTest {

    @Test
    fun extractsLexiconPeople() {
        val e = EntityExtractor.extract("Keir Starmer visits Manchester", "The PM met locals.")
        val keys = e.map { it.key }
        assertTrue("keir starmer should be extracted" , "keir starmer" in keys)
        assertEquals("keir starmer", e.first { it.key == "keir starmer" }.key)
    }

    @Test
    fun extractsOrgAndGpe() {
        val e = EntityExtractor.extract(
            "Labour Party wins in Scotland",
            "The NHS struggles as Putin warns the EU."
        )
        val keys = e.map { it.key }
        assertTrue("keys=$keys", "labour party" in keys)
        assertTrue("keys=$keys", "scotland" in keys)
        assertTrue("keys=$keys", "nhs" in keys)
        assertTrue("keys=$keys", "vladimir putin" in keys)
        assertTrue("keys=$keys", "eu" in keys)
    }

    @Test
    fun ignoresCapitalisedStopwords() {
        val e = EntityExtractor.extract("The How And Why Of UK Weather", "More On This")
        val keys = e.map { it.key }
        assertTrue(keys.none { it == "the" || it == "uk" || it == "how" || it == "weather" })
    }

    @Test
    fun normaliseCollapsesWhitespaceAndSSuffix() {
        assertEquals("donald trump", EntityExtractor.normalise("Donald  Trump"))
        assertEquals("keir starmer", EntityExtractor.normalise("Starmers"))
        assertEquals("labour party", EntityExtractor.normalise("Labour Party"))
    }

    @Test
    fun noFalseEntityForPlainSentence() {
        val e = EntityExtractor.extract("football fans celebrate a local win", "the team played well")
        // no lexicon hit; capitalised-phrase fallback needs 2+ capitalised words
        assertTrue(e.isEmpty())
    }
}

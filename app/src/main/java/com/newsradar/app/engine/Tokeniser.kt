package com.newsradar.app.engine

/**
 * Text tokeniser for the recommender.
 * - lowercases
 * - strips punctuation
 * - removes stop-words
 * - applies a light Porter-style suffix stemmer so "economy"/"economic"/"economics"
 *   collapse toward a shared stem, improving topic learning.
 */
object Tokeniser {

    private val PUNCTUATION = Regex("[^a-z0-9\\s]")
    private val SPACES = Regex("\\s+")

    private val STOP_WORDS = setOf(
        "the","a","an","and","or","but","if","then","else","for","on","in","at","to",
        "of","is","are","was","were","be","been","being","it","its","this","that",
        "these","those","with","as","by","from","up","down","out","over","under",
        "he","she","they","them","his","her","their","we","you","i","my","me","us",
        "has","have","had","do","does","did","will","would","can","could","should",
        "not","no","yes","so","than","too","very","just","says","said","after","new",
        "uk","news","more","one","two","get","got","who","what","when","where","why",
        "how","which","about","into","also","been","amid","live","latest"
    )

    fun tokenise(text: String): List<String> {
        return text.lowercase()
            .replace(PUNCTUATION, " ")
            .split(SPACES)
            .filter { it.length in 3..24 && it !in STOP_WORDS && !it.all { c -> c.isDigit() } }
            .map { stem(it) }
    }

    /** Very light stemmer: strips common English suffixes. */
    private fun stem(w: String): String {
        var s = w
        val suffixes = listOf("ational","tional","ically","fulness","ousness",
            "iveness","ization","ations","ingly","edly","ies","ied","ing","ed",
            "ly","es","s","ment","ness","ity")
        for (suf in suffixes) {
            if (s.length > suf.length + 2 && s.endsWith(suf)) {
                s = s.removeSuffix(suf)
                break
            }
        }
        return s
    }
}

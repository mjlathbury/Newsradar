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
        "how","which","about","into","also","been","amid","live","latest",
        // extra noise tokens that otherwise leak into learned weights / chips
        "take","taken","takes","taking","short","year","years","don","dont","easy",
        "easily","declare","declared","declares","make","makes","made","well","will",
        "want","need","needs","use","used","using","like","likes","liked","many",
        "much","way","ways","thing","things","people","time","times","day","days",
        "week","weeks","now","back","still","first","last","next","set","sets","said",
        "say","saying","could","may","might","must","let","lets","put","puts","go",
        "goes","going","gone","come","comes","came","see","seen","look","looks",
        "find","found","give","gave","given","keep","kept","tell","told","ask","asked",
        "work","works","working","show","shows","shown","call","called","told","end",
        "ends","part","parts","place","state","states","world","man","men","woman",
        "women","child","children","home","house","group","area","number","high","low",
        "big","small","old","young","great","large","long","full","half","per","via",
        "against","between","through","before","while","without","within","around",
        "them","there","here","both","each","every","any","all","our","your"
    )

    fun tokenise(text: String): List<String> {
        return text.lowercase()
            .replace(PUNCTUATION, " ")
            .split(SPACES)
            .filter { it.length in 3..24 && it !in STOP_WORDS && !it.all { c -> c.isDigit() } }
            .map { stem(it) }
    }

    /** True if [w] is a noise/stop word that should never be learned or shown. */
    fun isStopWord(w: String): Boolean = w in STOP_WORDS

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

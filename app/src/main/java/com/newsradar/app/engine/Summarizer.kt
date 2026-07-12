package com.newsradar.app.engine

/**
 * Tiny on-device extractive summarizer (no network, no API key).
 *
 * Scores each sentence by the frequency of its content words, keeps the top N
 * (preserving original order), and returns them joined. This is a real overview
 * built from the article's own sentences — not a paraphrase.
 */
object Summarizer {

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "else", "of", "to", "in",
        "on", "for", "with", "at", "by", "from", "as", "is", "are", "was", "were",
        "be", "been", "being", "this", "that", "these", "those", "it", "its", "he",
        "she", "they", "we", "you", "i", "his", "her", "their", "our", "your",
        "has", "have", "had", "will", "would", "can", "could", "should", "may",
        "might", "do", "does", "did", "not", "no", "so", "than", "into", "about",
        "over", "after", "before", "out", "up", "down", "how", "what", "when",
        "where", "who", "why", "which", "there", "here", "also", "more", "most", "other"
    )

    /** Produce a ~60 second overview: up to [maxSentences] key sentences. */
    fun summarize(text: String, maxSentences: Int = 12): String {
        val sentences = text.split(Regex("(?<=.?!)\\s+"))
            .map { it.trim() }
            .filter { it.length > 30 }
        if (sentences.size <= maxSentences) return sentences.joinToString(" ")

        // Word frequency across the whole text.
        val freq = mutableMapOf<String, Int>()
        for (s in sentences) {
            for (w in s.lowercase().split(Regex("\\W+"))) {
                if (w.length > 2 && w !in STOP) freq[w] = (freq[w] ?: 0) + 1
            }
        }
        val ranked = sentences.mapIndexed { i, s ->
            val score = s.lowercase().split(Regex("\\W+"))
                .count { it.length > 2 && it in freq }
            i to score
        }.sortedByDescending { it.second }
            .take(maxSentences)
            .sortedBy { it.first } // keep reading order

        return ranked.joinToString(" ") { sentences[it.first] }
    }
}

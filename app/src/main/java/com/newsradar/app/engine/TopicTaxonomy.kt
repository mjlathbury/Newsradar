package com.newsradar.app.engine

/**
 * Bundled topic taxonomy (keyword -> topic). Pure static data compiled into the APK;
 * no DB table. Used to (a) classify an article's broad topic for the Sbase bonus and
 * (b) drive explicit-interest / explicit-dislike topic matching (hard veto).
 *
 * Topics mirror the brief's broad categories. Extend the maps to improve coverage.
 */
object TopicTaxonomy {

    val TOPICS: Set<String> = setOf(
        "Politics", "Business", "Tech", "Science", "Health",
        "Sport", "Entertainment", "UK", "World", "Weather", "Environment"
    )

    private val TOPIC_KEYWORDS: Map<String, Set<String>> = mapOf(
        "Politics" to setOf(
            "government", "parliament", "minister", "pm", "mp", "election", "vote",
            "labour", "conservative", "tory", "tories", "reform", "policy", "bill",
            "commons", "westminster", "chancellor", "brexit", "cabinet", "law", "court"
        ),
        "Business" to setOf(
            "business", "economy", "market", "stock", "shares", "company", "firm",
            "profit", "revenue", "trade", "inflation", "interest", "rate", "bank",
            "invest", "startup", "retail", "wage", "gdp", "financial"
        ),
        "Tech" to setOf(
            "tech", "technology", "software", "app", "ai", "artificial", "intelligence",
            "google", "apple", "microsoft", "meta", "algorithm", "data", "privacy",
            "cyber", "chip", "smartphone", "computer", "openai", "chatgpt"
        ),
        "Science" to setOf(
            "science", "research", "study", "scientist", "space", "nasa", "physics",
            "biology", "chemistry", "quantum", "dna", "experiment", "discovery"
        ),
        "Health" to setOf(
            "health", "nhs", "doctor", "hospital", "virus", "covid", "disease", "cancer",
            "patient", "medicine", "vaccine", "mental", "care", "medical"
        ),
        "Sport" to setOf(
            "football", "premier", "league", "match", "goal", "player", "team", "rugby",
            "cricket", "tennis", "olympic", "race", "champion", "cup", "win", "draw",
            "fifa", "uefa", "world", "sport"
        ),
        "Entertainment" to setOf(
            "film", "movie", "tv", "show", "music", "album", "song", "star", "actor",
            "actress", "celebrity", "netflix", "disney", "concert", "award", "gig"
        ),
        "UK" to setOf(
            "uk", "britain", "british", "england", "scotland", "wales", "london",
            "manchester", "birmingham", "glasgow", "ukraine" // note: ukraine handled as World context too
        ),
        "World" to setOf(
            "world", "global", "international", "war", "ukraine", "russia", "gaza",
            "israel", "palestine", "china", "us", "america", "europe", "nato", "un",
            "foreign", "diplomacy"
        ),
        "Weather" to setOf(
            "weather", "rain", "storm", "snow", "heat", "temperature", "forecast",
            "flood", "wind", "cold", "hot", "met", "office"
        ),
        "Environment" to setOf(
            "climate", "environment", "carbon", "emission", "green", "renewable",
            "energy", "wildfire", "drought", "ocean", "wildlife", "nature", "net"
        )
    )

    /** Topics whose keyword set intersects the article's tokens. */
    fun topicsForTokens(tokens: Set<String>): Set<String> {
        val lower = tokens.map { it.lowercase() }.toSet()
        return TOPIC_KEYWORDS.filter { (_, kws) -> kws.any { k -> k in lower } }.keys
    }

    /** Tokens that signal a topic (used by taxonomy maintenance / debugging). */
    fun tokensForTopic(topic: String): Set<String> =
        TOPIC_KEYWORDS[topic] ?: emptySet()
}

package com.newsradar.app.engine

/**
 * On-device Named-Entity Recognition — dictionary/lexicon + heuristic only.
 * No cloud, no model. Extracts PERSON / ORG / GPE / PRODUCT / EVENT entities from
 * a headline + summary so the recommender can learn per-entity affinity.
 *
 * Strategy (precision over recall):
 *  1. Lexicon hits (multi-word proper nouns we bundle) — highest precision.
 *  2. Capitalised multi-word phrases not in the stopword list — fallback.
 *  3. Single capitalised tokens are mostly ignored (noisy) unless in the lexicon.
 *
 * `normalise` collapses a surface form to a stable key so affinity isn't split
 * across "Trump" / "Donald Trump" spellings.
 */
object EntityExtractor {

    data class ExtractedEntity(val rawText: String, val type: String, val key: String)

    // Capitalised tokens that are NOT entities (keeps the fallback from over-firing).
    private val CAP_STOP = setOf(
        "The", "A", "An", "And", "Or", "But", "If", "Then", "Else", "For", "On", "In", "At",
        "To", "Of", "Is", "Are", "Was", "Were", "Be", "Been", "Being", "It", "Its", "This",
        "That", "These", "Those", "With", "As", "By", "From", "Up", "Down", "Out", "Over",
        "Under", "He", "She", "They", "Them", "His", "Her", "Their", "We", "You", "I", "My",
        "Me", "Us", "How", "Why", "What", "When", "Where", "Who", "Which", "About", "Into",
        "Also", "After", "New", "UK", "US", "U", "S", "K", "PM", "MP", "BBC", "Q", "A", "I",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December",
        "Metro", "Mail", "Guardian", "Telegraph", "Times", "Sun", "Mirror", "Express",
        "Standard", "Sky", "ITV", "BBC", "Daily", "Record" // outlet names handled via outletId, not entities
    )

    // Bundled lexicon: surface form -> type. UK-focused seed set; extend over time.
    private val LEXICON: Map<String, String> = buildMap {
        // People (politicians / public figures)
        listOf(
            "Keir Starmer", "Rishi Sunak", "Boris Johnson", "Liz Truss", "Theresa May",
            "Nigel Farage", "Ed Davey", "Kemi Badenoch", "Donald Trump", "Joe Biden",
            "Kamala Harris", "Vladimir Putin", "Volodymyr Zelensky", "Emmanuel Macron",
            "Olaf Scholz", "Benjamin Netanyahu", "Elon Musk", "Jeff Bezos", "Mark Zuckerberg",
            "Tim Apple", "King Charles", "Prince William", "Prince Harry", "Meghan Markle",
            "Kate Middleton", "Taylor Swift", "Adele", "David Beckham", "Harry Kane",
            "Lewis Hamilton", "Pep Guardiola", "Jurgen Klopp", "Gareth Southgate"
        ).forEach { put(it.lowercase(), "PERSON") }
        // Common single surnames (so "Putin warns" extracts cleanly as "putin", not the
        // noisy 2-word phrase "putin warns").
        listOf(
            "putin", "trump", "starmer", "sunak", "johnson", "biden", "zelensky",
            "macron", "farage", "musk", "khan", "bezos", "zuckerberg", "swift",
            "beckham", "kane", "hamilton", "guardiola", "klopp", "southgate"
        ).forEach { put(it, "PERSON") }
        // Organisations / companies
        listOf(
            "NHS", "BBC", "Google", "Apple", "Microsoft", "Amazon", "Meta", "Tesla",
            "OpenAI", "Anthropic", "Netflix", "Disney", "Twitter", "X", "TikTok",
            "Labour Party", "Conservative Party", "Liberal Democrats", "Reform UK",
            "SNP", "Green Party", "Plaid Cymru", "Bank of England", "Ofcom", "Ofgem",
            "MI5", "MI6", "NATO", "EU", "UN", "WHO", "FIFA", "UEFA", "Premier League",
            "Manchester United", "Liverpool", "Arsenal", "Chelsea", "Manchester City",
            "Tottenham", "Google DeepMind"
        ).forEach { put(it.lowercase(), "ORG") }
        // Geo / places
        listOf(
            "United Kingdom", "England", "Scotland", "Wales", "Northern Ireland",
            "London", "Manchester", "Birmingham", "Glasgow", "Edinburgh", "Cardiff",
            "Belfast", "Europe", "Ukraine", "Russia", "United States", "China", "Gaza",
            "Israel", "France", "Germany"
        ).forEach { put(it.lowercase(), "GPE") }
        // Events
        listOf(
            "General Election", "Budget", "COP26", "COP27", "World Cup", "Euro 2024",
            "Olympics", "Brexit", "Covid", "Pandemic"
        ).forEach { put(it.lowercase(), "EVENT") }
    }

    /** Extract entities from a title + summary. Pure, deterministic, offline. */
    fun extract(title: String, summary: String): List<ExtractedEntity> {
        val text = "$title . $summary"
        val found = LinkedHashSet<ExtractedEntity>()

        // 1. Lexicon (case-insensitive, multi-word safe) — matched on WORD BOUNDARIES
        //    so short forms ("eu", "un", "who", "x") don't fire inside larger words.
        for ((form, type) in LEXICON) {
            val rx = Regex("(?i)\\b${Regex.escape(form)}\\b")
            if (rx.containsMatchIn(text)) {
                found.add(ExtractedEntity(form, type, normalise(form)))
            }
        }

        // 2. Capitalised multi-word phrase fallback (2-3 words), skipping stopwords.
        val phrase = Regex("""\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+){1,2})\b""")
        phrase.findAll(text).forEach { m ->
            val surface = m.groupValues[1].trim()
            val words = surface.split(" ")
            if (words.any { it in CAP_STOP }) return@forEach
            if (surface.lowercase() in LEXICON) return@forEach
            found.add(ExtractedEntity(surface, "PERSON", normalise(surface)))
        }

        return found.toList()
    }

    /**
     * Normalize a surface form to a stable key: collapse whitespace, canonicalise
     * surname variants to a full name so affinity isn't split (e.g. "trump" -> "donald trump").
     * Deliberately does NOT strip a trailing 's' (that corrupts names like "Boris"/"Wales").
     */
    private val CANONICAL = mapOf(
        "trump" to "donald trump",
        "trumps" to "donald trump",
        "starmer" to "keir starmer",
        "starmers" to "keir starmer",
        "sunak" to "rishi sunak",
        "johnson" to "boris johnson",
        "johnsons" to "boris johnson",
        "biden" to "joe biden",
        "putin" to "vladimir putin",
        "putins" to "vladimir putin",
        "zelensky" to "volodymyr zelensky",
        "macron" to "emmanuel macron",
        "farage" to "nigel farage",
        "musk" to "elon musk",
        "khan" to "sadiq khan",
        "bezos" to "jeff bezos",
        "zuckerberg" to "mark zuckerberg"
    )

    fun normalise(raw: String): String {
        val collapsed = raw.lowercase().replace(Regex("\\s+"), " ").trim()
        return CANONICAL[collapsed] ?: collapsed
    }

    fun isCapitalizedStopword(tok: String): Boolean = tok in CAP_STOP
}

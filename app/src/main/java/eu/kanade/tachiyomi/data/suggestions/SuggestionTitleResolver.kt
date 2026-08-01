package eu.kanade.tachiyomi.data.suggestions

import tachiyomi.core.common.util.system.logcat

object SuggestionTitleResolver {

    private val suffixRegexes = listOf(
        """\s+Season\s*\d*""",
        """\s+TV\b""",
        """\s+Special\b""",
        """\s+OVA\b""",
        """\s+ONA\b""",
        """\s+Movie\b""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val whitespaceRegex = Regex("""\s+""")
    private val volumeChapterRegex = Regex(
        """(?i)\b(vol|volume|ch|chapter|season|part|book|tome|s\d+)\b\s*\.?\s*\d+""",
    )
    private val nonAlphanumericRegex = Regex("""[^\p{L}\p{N}\s-]""")
    private val consecutiveSpacesRegex = Regex(" +")

    // Markers denoting another entry in the same franchise rather than another work. Ordered
    // longest-match-first, so "final season" is consumed before the bare "season". The
    // trailing-number rule folds "Steins;Gate 0" into its parent — an accepted trade-off.
    private val franchiseMarkerRegexes = listOf(
        """\b(the\s+)?final\s+season\b""",
        """\b\d+\s*(st|nd|rd|th)?\s+season\b""",
        """\bseason\s*\d+\b""",
        """\bpart\s*\d+\b""",
        """\b\d+(st|nd|rd|th)\b""",
        """\b(movie|film|ova|ona|special|specials|tv|series|cour)\b""",
        """\b(ii|iii|iv|vi|vii|viii|ix|xi|xii)\b""",
        """\b\d+\s*$""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val originalTitlePatterns = listOf(
        Regex("""Original Title:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE),
        Regex("""Original:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE),
        Regex("""Alternative Title:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE),
        Regex("""Romaji:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE),
    )

    fun normalizeQuery(title: String): String {
        var normalized = title.trim()
        suffixRegexes.forEach { normalized = normalized.replace(it, "") }
        return normalized.trim().replace(whitespaceRegex, " ")
    }

    fun parseOriginalTitle(description: String?): String? {
        if (description.isNullOrBlank()) return null
        for (pattern in originalTitlePatterns) {
            val match = pattern.find(description) ?: continue
            val cleaned = match.groupValues[1].trim().trimEnd('.', ',', '"', '\'')
            if (cleaned.isNotEmpty()) return cleaned
        }
        return null
    }

    // e.g. `/manga/12345--one-piece` becomes `one piece`.
    fun parseSlugTitle(url: String): String? {
        val lastSegment = url.substringBefore("?").substringAfterLast("/").trim()
        if (lastSegment.isBlank()) return null
        val slug = if (lastSegment.contains("--")) lastSegment.substringAfter("--") else lastSegment
        if (slug.all { it.isDigit() }) return null

        return slug.replace("-", " ")
            .replace("_", " ")
            .trim()
            .ifBlank { null }
    }

    // Order matters: providers try these in sequence and keep the first hit.
    fun resolveCandidates(
        title: String,
        description: String?,
        url: String? = null,
        alternativeTitles: List<String> = emptyList(),
    ): List<String> = buildList {
        add(title)
        parseOriginalTitle(description)?.let { add(it) }
        url?.let { rawUrl -> parseSlugTitle(rawUrl)?.let { add(it) } }
        addAll(alternativeTitles)
    }
        .flatMap { raw ->
            val trimmed = raw.trim()
            val normalized = normalizeQuery(trimmed)
            if (normalized != trimmed) listOf(trimmed, normalized) else listOf(trimmed)
        }
        .filter { it.isNotBlank() }
        .distinct()
        .also { candidates ->
            logcat { "SuggestionTitleResolver: resolved ${candidates.size} candidates for '$title': $candidates" }
        }

    fun scoreMatch(candidate: String, target: String): Int {
        val c = candidate.lowercase().trim()
        val t = target.lowercase().trim()
        if (c.isEmpty() || t.isEmpty()) return 0
        if (c == t) return 100
        if (c.startsWith(t) || t.startsWith(c)) return 75
        if (c.contains(t) || t.contains(c)) return 50

        val cTokens = c.split(whitespaceRegex).filter { it.length > 1 }.toSet()
        val tTokens = t.split(whitespaceRegex).filter { it.length > 1 }.toSet()
        if (cTokens.isEmpty() || tTokens.isEmpty()) return 0

        val intersection = cTokens.intersect(tTokens)
        val ratio = intersection.size.toDouble() / maxOf(cTokens.size, tTokens.size).toDouble()
        return (ratio * 50).toInt()
    }

    fun cleanTitle(title: String): String {
        var cleaned = removeTextInBrackets(title.lowercase())
        cleaned = cleaned.replace(volumeChapterRegex, " ")
        cleaned = cleaned.replace(nonAlphanumericRegex, " ")
        return cleaned.trim().replace(consecutiveSpacesRegex, " ")
    }

    private fun removeTextInBrackets(text: String): String {
        var depth = 0
        return buildString {
            for (char in text) {
                when (char) {
                    '(', '[', '<', '{' -> depth++
                    ')', ']', '>', '}' -> if (depth > 0) depth--
                    else -> if (depth == 0) append(char)
                }
            }
        }
    }

    // Max of normalized Levenshtein similarity and token Jaccard similarity, in 0..1.
    fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0

        val maxLen = maxOf(len1, len2)

        val dp = IntArray(len2 + 1) { it }
        for (i in 1..len1) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..len2) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else minOf(prev + 1, dp[j] + 1, dp[j - 1] + 1)
                prev = temp
            }
        }
        val charSim = 1.0 - (dp[len2].toDouble() / maxLen.toDouble())

        val tokens1 = s1.split(whitespaceRegex).filter { it.length > 1 }.toSet()
        val tokens2 = s2.split(whitespaceRegex).filter { it.length > 1 }.toSet()
        val jaccardSim = if (tokens1.isEmpty() || tokens2.isEmpty()) {
            0.0
        } else {
            tokens1.intersect(tokens2).size.toDouble() / tokens1.union(tokens2).size.toDouble()
        }

        return maxOf(charSim, jaccardSim)
    }

    fun normalizeEntryUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.trim()
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .lowercase()
            .ifBlank { null }
    }

    fun isSameProviderEntry(item: SuggestionItem, entryUrl: String?): Boolean {
        val normalizedEntry = normalizeEntryUrl(entryUrl) ?: return false
        if (normalizeEntryUrl(item.providerUrl) == normalizedEntry) return true
        val providerId = item.providerId ?: return false
        return normalizeEntryUrl(providerId.substringAfter(':', missingDelimiterValue = providerId)) == normalizedEntry
    }

    // Every season/OVA/movie of a work keys to the same string: showing the user four more
    // seasons of what they are already looking at is noise, not a suggestion. Falls back to
    // the cleaned title so entries actually named "Movie" or "1" keep an identity of their own.
    fun franchiseKey(title: String): String {
        val cleaned = cleanTitle(title)
        var key = cleaned
        franchiseMarkerRegexes.forEach { key = key.replace(it, " ") }
        key = key.trim().replace(consecutiveSpacesRegex, " ")
        return key.ifBlank { cleaned }
    }

    fun isFranchiseDuplicate(titleA: String, titleB: String): Boolean {
        val keyA = franchiseKey(titleA)
        val keyB = franchiseKey(titleB)
        if (keyA.isBlank() || keyB.isBlank()) return false
        if (keyA == keyB) return true
        if (calculateSimilarity(keyA, keyB) > 0.95) return true
        return containsFranchise(keyA, keyB)
    }

    // Catches spin-offs carrying the parent's full name, which similarity alone rates too low
    // because of the added prefix. The shorter side needs two words or it would swallow
    // unrelated works that merely reuse one ("Berserk" vs "Berserk of Gluttony").
    private fun containsFranchise(keyA: String, keyB: String): Boolean {
        val tokensA = keyA.split(whitespaceRegex).filter { it.isNotBlank() }
        val tokensB = keyB.split(whitespaceRegex).filter { it.isNotBlank() }
        val (shorter, longer) = if (tokensA.size <= tokensB.size) tokensA to tokensB else tokensB to tokensA
        if (shorter.size < MIN_CONTAINED_TOKENS || shorter.size == longer.size) return false
        return longer.windowed(shorter.size).any { it == shorter }
    }

    private const val MIN_CONTAINED_TOKENS = 2
}

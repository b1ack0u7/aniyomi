package mihon.domain.library.model.search

enum class LibraryField(vararg val aliases: String, val fieldOnly: Boolean = false) {
    TITLE("title"),
    AUTHOR("author"),
    ARTIST("artist"),
    DESCRIPTION("description", "desc"),
    GENRE("genre", "tag"),
    SOURCE("source", "src"),
    LANGUAGE("language", "lang", fieldOnly = true),
    SOURCE_ID("source_id", "sourceid", "src_id", "srcid", fieldOnly = true),
    ;

    companion object {
        private val lookup = entries.flatMap { field ->
            field.aliases.map { it.lowercase() to field }
        }.toMap()

        fun fromString(value: String): LibraryField? = lookup[value.lowercase()]
    }
}

enum class ComparisonField(vararg val aliases: String) {
    ID("id"),
    DATE_ADDED("added"),
    FETCH_INTERVAL("fetchinterval", "fi"),
    NEXT_UPDATE("nextupdate", "nu"),
    UNREAD("unread", "unseen"),
    READ("read", "seen", "watched"),
    TOTAL("total", "chapters", "episodes"),
    ;

    companion object {
        private val lookup = entries.flatMap { field ->
            field.aliases.map { it.lowercase() to field }
        }.toMap()

        fun fromString(value: String): ComparisonField? = lookup[value.lowercase()]
    }
}

enum class Comparator(val symbol: String) {
    GTE(">="),
    LTE("<="),
    GT(">"),
    LT("<"),
    EQ("="),
    ;

    fun <T : Comparable<T>> apply(a: T, b: T): Boolean = when (this) {
        GTE -> a >= b
        LTE -> a <= b
        GT -> a > b
        LT -> a < b
        EQ -> a == b
    }

    companion object {
        private val lookup = entries.associateBy { it.symbol }

        fun fromString(value: String): Comparator? = lookup[value]
    }
}

sealed interface QueryNode {
    companion object {
        fun from(query: String): QueryNode {
            val tokens = LibrarySearchLexer.tokenize(query)
            if (tokens.count { it == LibrarySearchLexer.Token.LParen } > 64) {
                return GeneralQueryNode(query, false)
            }
            return LibrarySearchParser(tokens).parse()
        }
    }
}

data class AndNode(val children: List<QueryNode>) : QueryNode

data class OrNode(val children: List<QueryNode>) : QueryNode

data class NotNode(val child: QueryNode) : QueryNode

object EmptyQueryNode : QueryNode

data class GeneralQueryNode(val value: String, val negated: Boolean) : QueryNode

data class FieldQueryNode(val field: LibraryField, val value: String, val negated: Boolean) : QueryNode

data class ComparisonQueryNode(
    val field: ComparisonField,
    val value: String,
    val queryComparator: Comparator,
    val negated: Boolean,
) : QueryNode

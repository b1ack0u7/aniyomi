package mihon.domain.library.model.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibrarySearchParserTest {
    @Test
    fun `and binds before or and parentheses override precedence`() {
        QueryNode.from("title:one || genre:action && -author:two") shouldBe OrNode(
            listOf(
                FieldQueryNode(LibraryField.TITLE, "one", false),
                AndNode(
                    listOf(
                        FieldQueryNode(LibraryField.GENRE, "action", false),
                        FieldQueryNode(LibraryField.AUTHOR, "two", true),
                    ),
                ),
            ),
        )
        QueryNode.from("-(title:one || title:two)") shouldBe NotNode(
            OrNode(
                listOf(
                    FieldQueryNode(LibraryField.TITLE, "one", false),
                    FieldQueryNode(LibraryField.TITLE, "two", false),
                ),
            ),
        )
    }

    @Test
    fun `operators work without whitespace and stay literal in quotes`() {
        QueryNode.from("genre:action&&unread:>0||title=one") shouldBe
            QueryNode.from("genre:action && unread:>0 || title=one")
        QueryNode.from("title:'A&&B'||author:'C||D'") shouldBe OrNode(
            listOf(
                FieldQueryNode(LibraryField.TITLE, "A&&B", false),
                FieldQueryNode(LibraryField.AUTHOR, "C||D", false),
            ),
        )
        QueryNode.from("title:'A & B'") shouldBe FieldQueryNode(LibraryField.TITLE, "A & B", false)
    }

    @Test
    fun `colon comparisons preserve id queries and support anime vocabulary`() {
        QueryNode.from("id:42") shouldBe ComparisonQueryNode(ComparisonField.ID, "42", Comparator.EQ, false)
        QueryNode.from("unread:>0") shouldBe ComparisonQueryNode(ComparisonField.UNREAD, "0", Comparator.GT, false)
        QueryNode.from("unseen>=2") shouldBe ComparisonQueryNode(ComparisonField.UNREAD, "2", Comparator.GTE, false)
        QueryNode.from("watched:3") shouldBe ComparisonQueryNode(ComparisonField.READ, "3", Comparator.EQ, false)
        QueryNode.from("episodes<12") shouldBe ComparisonQueryNode(ComparisonField.TOTAL, "12", Comparator.LT, false)
    }

    @Test
    fun `deeply nested input falls back to text without overflowing the stack`() {
        val query = "(".repeat(2_000) + "title:one" + ")".repeat(2_000)
        QueryNode.from(query) shouldBe GeneralQueryNode(query, false)
    }

    @Test
    fun `quotes empty fields commas and double negation are supported`() {
        QueryNode.from("title:\"\" , --genre:'martial arts'") shouldBe AndNode(
            listOf(
                FieldQueryNode(LibraryField.TITLE, "", false),
                FieldQueryNode(LibraryField.GENRE, "martial arts", false),
            ),
        )
        QueryNode.from("unknown:value") shouldBe GeneralQueryNode("unknown:value", false)
        QueryNode.from("(genre:action &&") shouldBe FieldQueryNode(LibraryField.GENRE, "action", false)
    }
}

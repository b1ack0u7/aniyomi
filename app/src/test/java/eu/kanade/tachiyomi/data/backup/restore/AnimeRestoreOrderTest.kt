package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreStore.Index
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimeRestoreOrderTest {
    @Test
    fun `parents precede their children without duplicating shared ancestors`() {
        val child = entry(1, parentId = 3)
        val unrelated = entry(2)
        val parent = entry(3, parentId = 4)
        val grandparent = entry(4)
        val sibling = entry(5, parentId = 3)

        val plan = orderAnimeRestore(listOf(child, unrelated, parent, grandparent, sibling))

        plan.ordered shouldBe listOf(grandparent, parent, child, unrelated, sibling)
        plan.invalid shouldBe emptySet()
    }

    @Test
    fun `entries without dependencies keep their input order`() {
        val entries = listOf(entry(4), entry(2), entry(1), entry(3))

        orderAnimeRestore(entries).ordered shouldBe entries
    }

    @Test
    fun `ten thousand dependent seasons require no recursive call stack`() {
        val entries = List(10_000) { offset ->
            entry(offset.toLong(), parentId = (offset + 1L).takeIf { it < 10_000 })
        }

        val plan = orderAnimeRestore(entries)

        plan.ordered shouldBe entries.asReversed()
        plan.invalid shouldBe emptySet()
    }

    @Test
    fun `cycles and every dependent are invalid while unrelated entries still restore`() {
        val dependentBeforeCycle = entry(1, parentId = 2)
        val firstCycleMember = entry(2, parentId = 3)
        val secondCycleMember = entry(3, parentId = 2)
        val unrelated = entry(4)
        val dependentAfterCycle = entry(5, parentId = 1)

        val plan = orderAnimeRestore(
            listOf(dependentBeforeCycle, firstCycleMember, secondCycleMember, unrelated, dependentAfterCycle),
        )

        plan.ordered shouldBe listOf(unrelated)
        plan.invalid shouldBe setOf(1L, 2L, 3L, 5L)
    }

    @Test
    fun `self references are invalid`() {
        val plan = orderAnimeRestore(listOf(entry(1, parentId = 1)))

        plan.ordered shouldBe emptyList()
        plan.invalid shouldBe setOf(1L)
    }

    @Test
    fun `missing parents and absent legacy IDs can restore`() {
        val missingParent = entry(1, parentId = 999)
        val noId = entry(2).copy(backupId = null)

        val plan = orderAnimeRestore(listOf(missingParent, noId))

        plan.ordered shouldBe listOf(missingParent, noId)
        plan.invalid shouldBe emptySet()
    }

    @Test
    fun `duplicate backup IDs resolve parents to the last input entry`() {
        val child = entry(1, parentId = 2)
        val firstParent = entry(2)
        val lastParent = entry(3).copy(backupId = 2)

        val plan = orderAnimeRestore(listOf(child, firstParent, lastParent))

        plan.ordered shouldBe listOf(lastParent, child, firstParent)
        plan.invalid shouldBe emptySet()
    }

    private fun entry(offset: Long, parentId: Long? = null) = Index(
        offset = offset,
        length = 1,
        source = 10,
        url = "/$offset",
        title = "Anime $offset",
        lastModifiedAt = 0,
        isExisting = false,
        backupId = offset,
        parentId = parentId,
    )
}

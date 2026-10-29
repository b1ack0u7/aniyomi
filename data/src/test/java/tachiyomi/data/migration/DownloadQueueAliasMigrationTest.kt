package tachiyomi.data.migration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.migration.LibrarySchemaMigrationTest.Fixture

class DownloadQueueAliasMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `migration retains old parent and child identities including unchanged children`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                title(2, favorite = false)
                item(1, 1)
                item(2, 2)
                title(3, favorite = true, url = "/other")
                item(3, 3)

                migrate()

                scalar("SELECT item_id FROM download_queue_alias WHERE old_parent_id = 1 AND old_item_id = 1") shouldBe
                    "1"
                scalar("SELECT item_id FROM download_queue_alias WHERE old_parent_id = 2 AND old_item_id = 2") shouldBe
                    "1"
                scalar("SELECT item_id FROM download_queue_alias WHERE old_parent_id = 3 AND old_item_id = 3") shouldBe
                    "3"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `children duplicated inside one parent retain independent queue aliases`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                item(1, 1)
                item(2, 1)

                migrate()

                scalar("SELECT count(*) FROM $newItem") shouldBe "1"
                scalar("SELECT item_id FROM download_queue_alias WHERE old_parent_id = 1 AND old_item_id = 2") shouldBe
                    "1"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `deleting canonical item leaves tombstone instead of aliasing a reused id`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                item(1, 1)
                item(2, 1)
                migrate()

                execute("PRAGMA foreign_keys = ON")
                execute("DELETE FROM $newItem WHERE id = 1")

                scalar("SELECT count(*) FROM download_queue_alias") shouldBe "2"
                scalar("SELECT count(*) FROM download_queue_alias WHERE item_id IS NULL") shouldBe "2"
                assertIntegrity()
            }
        }
    }
}

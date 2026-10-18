package eu.kanade.tachiyomi.data.library

import androidx.work.NetworkType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryUpdateConstraintsTest {

    @Test
    fun `default updates require a connected network and a sufficient battery`() {
        val constraints = libraryUpdateConstraints(emptySet())

        constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        constraints.requiresBatteryNotLow() shouldBe true
        constraints.requiresCharging() shouldBe false
    }
}

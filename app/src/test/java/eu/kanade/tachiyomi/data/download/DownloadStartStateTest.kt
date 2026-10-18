package eu.kanade.tachiyomi.data.download

import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DownloadStartStateTest {

    @Test
    fun `automatic start can activate a fresh queue without clearing challenge state`() {
        val fixture = Fixture()
        fixture.values["challenge_1"] = true

        fixture.state().prepareAutomaticStart() shouldBe true

        fixture.values["resume"] shouldBe true
        fixture.values["challenge_1"] shouldBe true
    }

    @Test
    fun `manual pause survives recreation and automatic updates`() {
        val fixture = Fixture()
        fixture.state().pauseByUser()

        fixture.state().prepareAutomaticStart() shouldBe false
        fixture.values["resume"] shouldBe false
        fixture.values["manual_paused"] shouldBe true
    }

    @Test
    fun `manual start releases manual pause without deleting source retry settings`() {
        val fixture = Fixture()
        fixture.values["challenge_1"] = true
        fixture.state().pauseByUser()

        fixture.state().resumeManually()

        fixture.state().canStartAutomatically() shouldBe true
        fixture.values["resume"] shouldBe true
        fixture.values["challenge_1"] shouldBe true
    }

    @Test
    fun `network suspension remains eligible for automatic start`() {
        val fixture = Fixture()
        fixture.values["resume"] = false

        fixture.state().prepareAutomaticStart() shouldBe true

        fixture.values["resume"] shouldBe true
    }

    private class Fixture {
        val values = mutableMapOf<String, Boolean>()
        private val preferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

        init {
            every { preferences.getBoolean(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { preferences.edit() } returns editor
            every { editor.putBoolean(any(), any()) } answers {
                values[firstArg()] = secondArg()
                editor
            }
        }

        fun state() = DownloadStartState(preferences)
    }
}

package eu.kanade.presentation.more.settings.screen.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class PlayerSettingsMainScreenTest {

    @Test
    fun `player settings screen survives saved state serialization from either entry point`() {
        listOf(true, false).forEach { mainSettings ->
            val screen = PlayerSettingsMainScreen(mainSettings)
            val bytes = ByteArrayOutputStream().use { buffer ->
                ObjectOutputStream(buffer).use { it.writeObject(screen) }
                buffer.toByteArray()
            }
            val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
                it.readObject() as PlayerSettingsMainScreen
            }

            restored.key shouldBe screen.key
            PlayerSettingsMainScreen::class.java.getDeclaredField("mainSettings").apply {
                isAccessible = true
            }.getBoolean(restored) shouldBe mainSettings
        }
    }
}

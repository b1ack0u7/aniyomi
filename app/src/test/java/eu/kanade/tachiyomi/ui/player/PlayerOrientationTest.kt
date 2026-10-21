package eu.kanade.tachiyomi.ui.player

import android.content.pm.ActivityInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlayerOrientationTest {

    @Test
    fun `reversing landscape keeps the landscape axis`() {
        PlayerOrientation.Landscape.toActivityOrientation(null) shouldBe ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape.toActivityOrientation(null) shouldBe
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape.toActivityOrientation(null) shouldBe
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    @Test
    fun `portrait and free choices preserve their requested orientation`() {
        PlayerOrientation.Portrait.toActivityOrientation(null) shouldBe ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait.toActivityOrientation(null) shouldBe
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait.toActivityOrientation(null) shouldBe
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Free.toActivityOrientation(null) shouldBe ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    @Test
    fun `video orientation follows the video aspect and tolerates unloaded video`() {
        PlayerOrientation.Video.toActivityOrientation(16.0 / 9.0) shouldBe
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        listOf(null, 0.0, 1.0, 9.0 / 16.0).forEach {
            PlayerOrientation.Video.toActivityOrientation(it) shouldBe ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
}

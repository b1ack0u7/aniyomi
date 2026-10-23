package tachiyomi.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "xyz.b1ack0u7.aniyomi.mi.benchmark",
        profileBlock = {
            pressHome()
            startActivityAndWait()

            device.selectNavigationTab("Anime")
            device.selectNavigationTab("Manga")
            device.selectNavigationTab("Updates")
            device.selectNavigationTab("More")

            device.awaitObject(By.text("History")).click()
            device.awaitObject(By.desc("Navigate up"))
            device.awaitObject(By.desc("Search"))
            device.awaitObject(By.text("History"))

            device.pressBack()
            device.awaitObject(By.selected(true).hasChild(By.text("More")))
            device.awaitObject(By.text("History"))

            val settings = By.text("Settings")
            if (!device.hasObject(settings)) {
                val moreList = device.awaitObject(By.scrollable(true))
                for (attempt in 0 until 5) {
                    if (device.hasObject(settings)) break
                    if (!moreList.scroll(Direction.DOWN, 0.8f)) break
                }
            }
            device.awaitObject(settings).click()
            device.awaitObject(By.desc("Navigate up"))
            device.awaitObject(By.text("Appearance"))
            device.awaitObject(By.text("Settings"))
        },
    )

    private fun UiDevice.selectNavigationTab(label: String) {
        val selector = By.clazz("android.view.View")
            .focusable(true)
            .hasChild(By.text(label))
            .hasChild(By.clazz("android.view.View"))
        val tab = awaitObject(selector)
        if (!tab.isSelected) tab.click()
        awaitObject(By.copy(selector).selected(true))
        waitForIdle()
    }

    private fun UiDevice.awaitObject(selector: BySelector): UiObject2 = checkNotNull(
        wait(Until.findObject(selector), 10_000),
    ) {
        "Missing $selector. Complete onboarding and use English with the default navigation layout."
    }
}

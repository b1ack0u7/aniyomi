package eu.kanade.tachiyomi.data.track.kitsu

import java.text.DecimalFormat

internal enum class KitsuRatingSystem(val preferenceValue: String, private val twentyScale: List<Int>) {
    SIMPLE("simple", (2..20 step 6).toList()),
    REGULAR("regular", (2..20 step 2).toList()),
    ADVANCED("advanced", (2..20).toList()),
    ;

    fun scoreList(): List<String> {
        val format = DecimalFormat("0.#")
        return when (this) {
            SIMPLE -> listOf("-", "😡", "😐", "😊", "😀")
            REGULAR -> (0..10).map { "${format.format(it / 2.0)} ★" }
            ADVANCED -> listOf("0") + twentyScale.map { format.format(it / 2.0) }
        }
    }

    // Local tracks and backups keep the original 0-10 representation for downgrade compatibility.
    fun indexToScore(index: Int): Double = twentyScale.getOrNull(index - 1)?.div(2.0) ?: 0.0

    fun displayScore(score: Double): String = scoreList()[twentyScale.indexOfLast { it <= score * 2 } + 1]

    companion object {
        fun fromPreference(value: String?): KitsuRatingSystem =
            entries.firstOrNull { it.preferenceValue.equals(value?.trim(), ignoreCase = true) } ?: ADVANCED
    }
}

package eu.kanade.tachiyomi.ui.player

fun buildVideoFilterChain(debanding: Debanding, useYuv420p: Boolean, tryHWDecoding: Boolean): String? {
    val filters = buildList {
        if (debanding == Debanding.CPU) add("gradfun=radius=12")
        // Surface-backed hardware frames cannot safely pass through format=yuv420p.
        if (useYuv420p && !tryHWDecoding) add("format=yuv420p")
    }
    return filters.takeIf { it.isNotEmpty() }?.joinToString(",")
}

fun buildVideoFilterHwdec(debanding: Debanding, tryHWDecoding: Boolean): String {
    return when {
        !tryHWDecoding -> Decoder.SW.value
        // gradfun needs CPU frames; request a copy path only for explicit CPU debanding.
        debanding == Debanding.CPU -> Decoder.AutoCopy.value
        else -> Decoder.Auto.value
    }
}

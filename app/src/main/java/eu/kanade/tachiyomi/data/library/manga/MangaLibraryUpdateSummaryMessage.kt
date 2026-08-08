package eu.kanade.tachiyomi.data.library.manga

import android.content.Context
import eu.kanade.tachiyomi.data.library.LibraryUpdateSummary
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR

fun LibraryUpdateSummary.toMangaMessage(context: Context): String = when {
    failed > 0 -> context.stringResource(MR.strings.notification_update_error, failed)
    newItems > 0 -> context.pluralStringResource(
        AYMR.plurals.library_update_summary_chapters,
        newItems,
        newItems,
    )
    else -> context.stringResource(AYMR.strings.library_update_summary_no_chapters)
}

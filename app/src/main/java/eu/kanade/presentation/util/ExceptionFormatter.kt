package eu.kanade.presentation.util

import android.content.Context
import aniyomi.core.common.torrent.DisabledTorrServerException
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import eu.kanade.tachiyomi.util.system.isOnline
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.causeChain
import tachiyomi.domain.items.chapter.model.NoChaptersException
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.domain.source.anime.model.AnimeSourceNotInstalledException
import tachiyomi.domain.source.manga.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date

context(Context)
val Throwable.formattedMessage: String
    get() {
        val causes = causeChain()
        val exception = causes.firstOrNull { it is CloudflareChallengeException }
            ?: causes.firstOrNull {
                it is SourceRequestPausedException || it is DisabledTorrServerException ||
                    it is HttpException || it is UnknownHostException ||
                    it is NoChaptersException || it is NoEpisodesException ||
                    it is SourceNotInstalledException || it is AnimeSourceNotInstalledException
            }
            ?: this
        when (exception) {
            is CloudflareChallengeException -> return stringResource(MR.strings.information_cloudflare_bypass_failure)
            is SourceRequestPausedException -> {
                val retryAt = exception.retryAt
                return if (retryAt == null) {
                    stringResource(MR.strings.information_cloudflare_bypass_failure)
                } else {
                    stringResource(
                        AYMR.strings.smart_download_waiting,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(retryAt)),
                    )
                }
            }
            is DisabledTorrServerException -> return stringResource(AYMR.strings.torrserver_disabled)
            is HttpException -> return stringResource(MR.strings.exception_http, exception.code)
            is UnknownHostException -> {
                return if (!isOnline()) {
                    stringResource(MR.strings.exception_offline)
                } else {
                    stringResource(MR.strings.exception_unknown_host, exception.message ?: "")
                }
            }
            is NoChaptersException, is NoEpisodesException -> return stringResource(
                MR.strings.no_results_found,
            )
            is SourceNotInstalledException, is AnimeSourceNotInstalledException -> return stringResource(
                MR.strings.loader_not_implemented_error,
            )
        }
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }

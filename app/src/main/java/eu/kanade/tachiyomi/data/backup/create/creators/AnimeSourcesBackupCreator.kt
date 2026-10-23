package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeSource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeSourcesBackupCreator(
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
) {

    operator fun invoke(sourceIds: Set<Long>): List<BackupAnimeSource> {
        return sourceIds
            .asSequence()
            .map(animeSourceManager::getOrStub)
            .map { it.toBackupSource() }
            .toList()
    }
}

private fun AnimeSource.toBackupSource() =
    BackupAnimeSource(
        name = this.name,
        sourceId = this.id,
    )

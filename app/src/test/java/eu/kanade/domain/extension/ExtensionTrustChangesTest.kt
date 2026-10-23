package eu.kanade.domain.extension

import eu.kanade.domain.extension.anime.interactor.TrustAnimeExtension
import eu.kanade.domain.extension.manga.interactor.TrustMangaExtension
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.extensionrepo.anime.repository.AnimeExtensionRepoRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionTrustChangesTest {
    @Test
    fun `trust changes include repository keys and manual trust without index refresh noise`() = runTest {
        for (anime in listOf(true, false)) {
            val trusted = MutableStateFlow(emptySet<String>())
            val repos = MutableStateFlow(emptyList<ExtensionRepo>())
            val preferences = mockk<SourcePreferences> {
                every { trustedExtensions().changes() } returns trusted
            }
            val changes = if (anime) {
                val repository = mockk<AnimeExtensionRepoRepository> {
                    every { subscribeAll() } returns repos
                }
                TrustAnimeExtension(repository, preferences).changes()
            } else {
                val repository = mockk<MangaExtensionRepoRepository> {
                    every { subscribeAll() } returns repos
                }
                TrustMangaExtension(repository, preferences).changes()
            }
            var events = 0
            val job = backgroundScope.launch { changes.collect { events++ } }
            runCurrent()
            events shouldBe 0

            val repo = ExtensionRepo("https://repo.example", "Repo", null, "https://repo.example", "key")
            repos.value = listOf(repo)
            runCurrent()
            events shouldBe 1

            repos.value = listOf(repo.copy(name = "Refreshed metadata"))
            runCurrent()
            events shouldBe 1

            trusted.value = setOf("package:1:manual-key")
            runCurrent()
            events shouldBe 2

            trusted.value = emptySet()
            runCurrent()
            events shouldBe 3

            repos.value = emptyList()
            runCurrent()
            events shouldBe 4
            job.cancel()
        }
    }
}

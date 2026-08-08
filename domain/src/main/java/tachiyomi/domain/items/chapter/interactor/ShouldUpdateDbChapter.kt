package tachiyomi.domain.items.chapter.interactor

import tachiyomi.domain.items.chapter.model.Chapter

class ShouldUpdateDbChapter {

    fun await(dbChapter: Chapter, sourceChapter: Chapter): Boolean {
        return dbChapter.scanlator != sourceChapter.scanlator ||
            dbChapter.name != sourceChapter.name ||
            dbChapter.chapterNumber != sourceChapter.chapterNumber ||
            dbChapter.sourceOrder != sourceChapter.sourceOrder ||
            // Sources that don't expose a real upload date report the current time instead, which
            // differs on every fetch. Only a first-time date counts, otherwise the whole chapter
            // list is rewritten on each library update.
            (dbChapter.dateUpload == 0L && sourceChapter.dateUpload != 0L)
    }
}

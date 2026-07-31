package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

private val EMPTY_JSON_OBJECT = JsonObject(emptyMap())

/**
 * Shared empty object, used as the default value of the source metadata carried by
 * [SManga.memo] and [SChapter.memo].
 */
val JsonObject.Companion.EMPTY: JsonObject
    get() = EMPTY_JSON_OBJECT

package eu.kanade.presentation.entries

enum class DownloadAction {
    NEXT_1_ITEM,
    NEXT_5_ITEMS,
    NEXT_10_ITEMS,
    NEXT_25_ITEMS,
    UNVIEWED_ITEMS,
}

enum class EditCoverAction {
    EDIT,
    DELETE,
}

enum class EntryScreenItem {
    INFO_BOX,
    ACTION_ROW,
    DESCRIPTION_WITH_TAG,
    SUGGESTIONS,
    ITEM_HEADER,
    ITEM,
    SHOW_ALL_ITEMS,
    AIRING_TIME,
}

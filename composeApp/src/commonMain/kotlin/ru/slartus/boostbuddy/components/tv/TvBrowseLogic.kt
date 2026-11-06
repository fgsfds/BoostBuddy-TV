package ru.slartus.boostbuddy.components.tv

import androidx.compose.runtime.Immutable

/**
 * An asynchronous focus request belongs to one navigation intent. Any later
 * focus move invalidates it, so arriving data cannot interrupt text editing.
 */
@Immutable
internal data class TvFocusState(
    val revision: Long = 0,
    val sidebarHasFocus: Boolean = false,
    val contentRequestPending: Boolean = true,
    val allowHeaderFallback: Boolean = false,
    val focusedContentKey: String? = null,
) {
    fun requestContent(allowHeaderFallback: Boolean = false) = copy(
        revision = revision + 1,
        sidebarHasFocus = false,
        contentRequestPending = true,
        allowHeaderFallback = allowHeaderFallback,
        focusedContentKey = null,
    )

    fun requestSidebar() = copy(
        revision = revision + 1,
        sidebarHasFocus = true,
        contentRequestPending = false,
        allowHeaderFallback = false,
        focusedContentKey = null,
    )

    fun contentFocused(key: String? = null) = copy(
        revision = revision + 1,
        sidebarHasFocus = false,
        contentRequestPending = false,
        allowHeaderFallback = false,
        focusedContentKey = key,
    )

    // Android may focus the first sidebar control as the window gains focus.
    // Keep the untouched initial grid/bookmark request until real input occurs.
    fun sidebarFocused() =
        if (revision == 0L && contentRequestPending) this else requestSidebar()

    fun canRequestContent(requestRevision: Long): Boolean =
        revision == requestRevision && contentRequestPending && !sidebarHasFocus

    fun canRequestSidebar(requestRevision: Long): Boolean =
        revision == requestRevision && sidebarHasFocus
}

/** Keep unfinished text when the same submitted query is shown again. */
@Immutable
internal data class TvSearchDraft(
    val query: String = "",
    val text: String = query,
) {
    val submittedQuery: String get() = text.trim()

    fun synchronize(query: String): TvSearchDraft =
        if (query == this.query) this else TvSearchDraft(query)
}

/**
 * A refresh may remove or reorder the previously focused item. Prefer its
 * identity, then the closest surviving position instead of jumping to the top.
 */
internal fun restoredTvGridIndex(
    keys: List<String>,
    focusedKey: String?,
    focusedIndex: Int,
): Int? {
    if (keys.isEmpty()) return null
    val matchingIndex = focusedKey?.let(keys::indexOf) ?: -1
    return if (matchingIndex >= 0) matchingIndex else focusedIndex.coerceIn(keys.indices)
}

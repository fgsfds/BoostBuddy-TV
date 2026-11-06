// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ru.slartus.boostbuddy.components.tv.TvSearchDraft

/** Remember this above RootScreen so a player or creator route retains TV navigation state. */
@Stable
class TvBrowseState internal constructor() {
    var section by mutableStateOf(TvSection.Feed)
    var sidebarExpanded by mutableStateOf(true)
    internal var sidebarFocusKey by mutableStateOf("feed")
    internal var searchDraft by mutableStateOf(TvSearchDraft())
    internal var searchFocusRequested by mutableStateOf(false)
    internal val grids = mutableMapOf<String, TvGridBookmark>()

    internal fun bookmark(route: String): TvGridBookmark = grids.getOrPut(route) { TvGridBookmark() }

    /** Call after entering authentication, so a new account does not inherit browse state. */
    fun clear() {
        section = TvSection.Feed
        sidebarExpanded = true
        sidebarFocusKey = "feed"
        searchDraft = TvSearchDraft()
        searchFocusRequested = false
        grids.clear()
    }

    companion object {
        private const val SAVE_VERSION = "tv-browse-v2"

        internal val Saver = listSaver<TvBrowseState, String>(
            save = { state ->
                listOf(
                    SAVE_VERSION,
                    state.section.name,
                    state.sidebarExpanded.toString(),
                    state.sidebarFocusKey,
                    state.searchDraft.query,
                    state.searchDraft.text,
                    state.searchFocusRequested.toString(),
                ) +
                    state.grids.flatMap { (route, bookmark) ->
                        listOf(
                            route, bookmark.index.toString(), bookmark.offset.toString(),
                            bookmark.focusedKey.orEmpty(), bookmark.focusedIndex.toString(),
                        )
                    }
            },
            restore = { saved ->
                TvBrowseState().apply {
                    val versioned = saved.firstOrNull() == SAVE_VERSION
                    val start = if (versioned) 1 else 0
                    section = TvSection.entries.firstOrNull { it.name == saved.getOrNull(start) } ?: TvSection.Feed
                    sidebarExpanded = saved.getOrNull(start + 1)?.toBooleanStrictOrNull() ?: true
                    sidebarFocusKey = saved.getOrNull(start + 2) ?: "feed"
                    if (versioned) {
                        searchDraft = TvSearchDraft(saved.getOrNull(4).orEmpty(), saved.getOrNull(5).orEmpty())
                        searchFocusRequested = saved.getOrNull(6)?.toBooleanStrictOrNull() ?: false
                    }
                    val bookmarkStart = if (versioned) 7 else 3
                    val bookmarkSize = if (versioned) 5 else 4
                    saved.drop(bookmarkStart).chunked(bookmarkSize).filter { it.size == bookmarkSize }.forEach { entry ->
                        grids[entry[0]] = TvGridBookmark(
                            index = entry[1].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            offset = entry[2].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            focusedKey = entry[3].ifEmpty { null },
                            focusedIndex = entry.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0)
                                ?: entry[1].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        )
                    }
                }
            },
        )
    }
}

enum class TvSection { Feed, Subscriptions, Search }

internal class TvGridBookmark(
    var index: Int = 0,
    var offset: Int = 0,
    var focusedKey: String? = null,
    var focusedIndex: Int = 0,
)

@Composable
fun rememberTvBrowseState(): TvBrowseState = rememberSaveable(saver = TvBrowseState.Saver) { TvBrowseState() }

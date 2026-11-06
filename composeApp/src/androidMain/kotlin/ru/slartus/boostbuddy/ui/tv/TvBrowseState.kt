package ru.slartus.boostbuddy.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Remember this above RootScreen so a player or creator route retains TV navigation state. */
@Stable
class TvBrowseState internal constructor() {
    var section by mutableStateOf(TvSection.Feed)
    var sidebarExpanded by mutableStateOf(true)
    internal var sidebarFocusKey by mutableStateOf("feed")
    internal val grids = mutableMapOf<String, TvGridBookmark>()

    internal fun bookmark(route: String): TvGridBookmark = grids.getOrPut(route) { TvGridBookmark() }

    companion object {
        internal val Saver = listSaver<TvBrowseState, String>(
            save = { state ->
                listOf(state.section.name, state.sidebarExpanded.toString(), state.sidebarFocusKey) +
                    state.grids.flatMap { (route, bookmark) ->
                        listOf(route, bookmark.index.toString(), bookmark.offset.toString(), bookmark.focusedKey.orEmpty())
                    }
            },
            restore = { saved ->
                TvBrowseState().apply {
                    section = TvSection.entries.firstOrNull { it.name == saved.getOrNull(0) } ?: TvSection.Feed
                    sidebarExpanded = saved.getOrNull(1)?.toBooleanStrictOrNull() ?: true
                    sidebarFocusKey = saved.getOrNull(2) ?: "feed"
                    saved.drop(3).chunked(4).filter { it.size == 4 }.forEach { entry ->
                        grids[entry[0]] = TvGridBookmark(
                            index = entry[1].toIntOrNull() ?: 0,
                            offset = entry[2].toIntOrNull() ?: 0,
                            focusedKey = entry[3].ifEmpty { null },
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
)

@Composable
fun rememberTvBrowseState(): TvBrowseState = rememberSaveable(saver = TvBrowseState.Saver) { TvBrowseState() }

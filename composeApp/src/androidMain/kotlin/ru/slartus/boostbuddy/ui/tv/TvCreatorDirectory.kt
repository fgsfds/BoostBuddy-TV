// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.slartus.boostbuddy.components.subscribes.SubscribesViewState
import ru.slartus.boostbuddy.components.tv.restoredTvGridIndex
import ru.slartus.boostbuddy.data.repositories.Blog

@Composable
internal fun TvCreatorDirectory(
    subscriptions: SubscribesViewState.ProgressState,
    browseState: TvBrowseState,
    focus: TvContentFocus,
    onCreatorSelected: (Blog) -> Unit,
    onRefresh: () -> Unit,
) {
    val creators = remember(subscriptions) { subscriptions.creatorBlogs() }
    val refreshRequester = remember { FocusRequester() }
    val retry = { focus.enterContent(allowHeaderFallback = false); onRefresh() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 23.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Подписки", color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("Выберите автора, чтобы открыть его видео", color = TvColors.Secondary, fontSize = 12.sp)
            }
            TvActionButton("Обновить", onRefresh, Modifier.focusRequester(refreshRequester).leftToSidebar(focus), Icons.Rounded.Refresh, focus::contentFocused)
        }
        when (subscriptions) {
            SubscribesViewState.ProgressState.Init,
            SubscribesViewState.ProgressState.Loading -> TvLoadingPanel("Загружаем авторов…", focus, refreshRequester)
            is SubscribesViewState.ProgressState.Error -> TvStatusPanel("Не удалось загрузить подписки", subscriptions.description, Icons.Rounded.WarningAmber, "Повторить", retry, focus)
            is SubscribesViewState.ProgressState.Loaded -> {
                if (creators.isEmpty()) {
                    TvStatusPanel("Подписок пока нет", "Авторы, на которых вы подписаны в Boosty, появятся здесь.", Icons.Rounded.Subscriptions, "Обновить", retry, focus)
                } else {
                    TvCreatorGrid(creators, browseState, focus, onCreatorSelected)
                }
            }
        }
    }
}

@Composable
private fun TvCreatorGrid(
    creators: List<Blog>,
    browseState: TvBrowseState,
    focus: TvContentFocus,
    onCreatorSelected: (Blog) -> Unit,
) {
    val bookmark = remember { browseState.bookmark("subscriptions") }
    val grid = remember { LazyGridState(bookmark.index, bookmark.offset) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val keys = remember(creators) { creators.map { it.blogUrl } }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.distinctUntilChanged()
            .collect { (index, offset) -> bookmark.index = index; bookmark.offset = offset }
    }
    LaunchedEffect(focus.request, keys) {
        if (focus.sidebarHasFocus) return@LaunchedEffect
        if (focus.focusedContentKey?.let { it !in keys } == true) {
            focus.enterContent(allowHeaderFallback = false)
            return@LaunchedEffect
        }
        val request = focus.request
        if (!focus.canRequestContent(request)) return@LaunchedEffect
        val index = restoredTvGridIndex(keys, bookmark.focusedKey, bookmark.focusedIndex)
            ?: return@LaunchedEffect
        val key = keys[index]
        withFrameNanos { }
        if (!focus.canRequestContent(request)) return@LaunchedEffect
        if (grid.layoutInfo.visibleItemsInfo.none { it.key == key }) grid.scrollToItem(index)
        if (requestTvFocus({ requesters[key] }, { focus.canRequestContent(request) })) {
            focus.completeContentRequest(request, key)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 920.dp) 4 else 3
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(creators, key = { _, creator -> creator.blogUrl }) { index, creator ->
                TvFocusableSurface(
                    onClick = {
                        bookmark.focusedKey = creator.blogUrl
                        bookmark.focusedIndex = index
                        browseState.searchFocusRequested = false
                        browseState.sidebarFocusKey = "creator:${creator.blogUrl}"
                        onCreatorSelected(creator)
                    },
                    onFocused = {
                        bookmark.focusedKey = creator.blogUrl
                        bookmark.focusedIndex = index
                        focus.itemFocused(creator.blogUrl)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(requesters.getOrPut(creator.blogUrl) { FocusRequester() })
                        .onPreviewKeyEvent { event ->
                            when {
                                event.key == Key.DirectionLeft && index % columns == 0 -> {
                                    if (event.type == KeyEventType.KeyDown) focus.enterSidebar()
                                    true
                                }
                                event.key == Key.DirectionRight && (index % columns == columns - 1 || index == creators.lastIndex) -> true
                                else -> false
                            }
                        },
                ) { focused ->
                    Column(
                        Modifier.fillMaxWidth().background(if (focused) TvColors.Focused else TvColors.Surface).padding(horizontal = 14.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TvAvatar(creator.owner.avatarUrl, creator.owner.name, Modifier.size(70.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(creator.owner.name, color = TvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(5.dp))
                        Text(creator.title.ifBlank { creator.blogUrl }, color = TvColors.Secondary, fontSize = 12.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

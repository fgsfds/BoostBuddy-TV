package ru.slartus.boostbuddy.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.seiko.imageloader.rememberImagePainter
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.slartus.boostbuddy.components.common.ProgressState
import ru.slartus.boostbuddy.components.feed.FeedPostItem
import ru.slartus.boostbuddy.components.tv.TvVideoCard
import ru.slartus.boostbuddy.components.tv.formatVideoDuration
import ru.slartus.boostbuddy.ui.common.keyboardAsState

@Composable
internal fun TvVideoBrowseContent(
    routeKey: String,
    title: String,
    subtitle: String,
    cards: List<TvVideoCard>,
    items: List<FeedPostItem>,
    progress: ProgressState,
    isRefreshing: Boolean,
    hasMore: Boolean,
    pageCursor: String?,
    browseState: TvBrowseState,
    focus: TvContentFocus,
    searchVisible: Boolean,
    searchQuery: String,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRetryPage: () -> Unit,
    onPlay: (TvVideoCard) -> Unit,
    avatarUrl: String? = null,
) {
    var unavailableVideo by remember { mutableStateOf<TvVideoCard?>(null) }
    val refreshRequester = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (avatarUrl != null) {
                TvAvatar(avatarUrl, title, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, color = TvColors.Text, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = TvColors.Secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isRefreshing) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), color = TvColors.Accent, strokeWidth = 2.dp)
            TvActionButton(
                "Обновить", onRefresh,
                modifier = Modifier.focusRequester(refreshRequester).leftToSidebar(focus),
                icon = Icons.Rounded.Refresh,
                onFocused = focus::contentFocused,
            )
        }
        if (searchVisible) {
            TvSearchBar(searchQuery, focus, onSearch)
            Spacer(Modifier.height(18.dp))
        }
        if (searchVisible && searchQuery.isBlank()) {
            TvStatusPanel(
                title = "Найдите видео",
                message = "Введите название и нажмите «Найти».",
                icon = Icons.Rounded.Search,
            )
        } else when (progress) {
            ProgressState.Init, ProgressState.Loading -> TvLoadingPanel("Загружаем видео…")
            is ProgressState.Error -> TvStatusPanel(
                title = "Не удалось загрузить видео",
                message = progress.description,
                icon = Icons.Rounded.WarningAmber,
                action = "Повторить",
                onAction = onRetry,
                focus = focus,
            )
            ProgressState.Loaded -> TvVideoGrid(
                routeKey = routeKey,
                cards = cards,
                items = items,
                hasMore = hasMore,
                pageCursor = pageCursor,
                isRefreshing = isRefreshing,
                browseState = browseState,
                focus = focus,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onRetryPage = onRetryPage,
                onPlay = { card ->
                    if (card.isPlayable) onPlay(card) else unavailableVideo = card
                },
            )
        }
    }
    unavailableVideo?.let { card ->
        TvUnavailableDialog(card, onDismiss = { unavailableVideo = null; focus.enterContent() })
    }
}

@Composable
private fun TvSearchBar(query: String, focus: TvContentFocus, onSearch: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(query) }
    val requester = remember { FocusRequester() }
    val findRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val isKeyboardOpen by keyboardAsState()
    LaunchedEffect(query) { draft = query }
    val submit = {
        val value = draft.trim()
        if (value.isNotBlank()) {
            keyboard?.hide()
            onSearch(value)
            focus.enterContent()
        }
    }
    LaunchedEffect(focus.request, query.isBlank()) {
        if (query.isBlank() && !focus.sidebarHasFocus) {
            requestTvFocus({ requester }, { !focus.sidebarHasFocus })
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f).focusRequester(requester).onFocusChanged { if (it.isFocused) focus.contentFocused() }
                .onPreviewKeyEvent { event ->
                    if (!isKeyboardOpen) {
                        // With the keyboard closed, remote arrows leave the text caret.
                        // Leave Center and open-keyboard editing to the native text field.
                        val navigate: (() -> Unit)? = when (event.key) {
                            Key.DirectionLeft -> ({ focus.enterSidebar() })
                            Key.DirectionRight -> ({ findRequester.requestFocus() })
                            Key.DirectionDown -> ({
                                if (!focusManager.moveFocus(FocusDirection.Down)) findRequester.requestFocus()
                            })
                            Key.DirectionUp -> ({ focusManager.moveFocus(FocusDirection.Up) })
                            else -> null
                        }
                        if (navigate != null) {
                            if (event.type == KeyEventType.KeyDown) navigate()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyDown) submit()
                        true
                    } else false
                },
            placeholder = { Text("Название видео или публикации", color = TvColors.Secondary, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = TvColors.Secondary) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvColors.Text, fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TvColors.Accent,
                unfocusedBorderColor = TvColors.Line,
                focusedContainerColor = TvColors.Surface,
                unfocusedContainerColor = TvColors.Surface,
                cursorColor = TvColors.Accent,
            ),
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        TvActionButton(
            "Найти", submit,
            modifier = Modifier.focusRequester(findRequester),
            icon = Icons.Rounded.Search,
            onFocused = focus::contentFocused,
        )
    }
}

@Composable
private fun TvVideoGrid(
    routeKey: String,
    cards: List<TvVideoCard>,
    items: List<FeedPostItem>,
    hasMore: Boolean,
    pageCursor: String?,
    isRefreshing: Boolean,
    browseState: TvBrowseState,
    focus: TvContentFocus,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryPage: () -> Unit,
    onPlay: (TvVideoCard) -> Unit,
) {
    val bookmark = remember(routeKey) { browseState.bookmark(routeKey) }
    val grid = remember(routeKey) { LazyGridState(bookmark.index, bookmark.offset) }
    val requesters = remember(routeKey) { mutableMapOf<String, FocusRequester>() }
    var handledFocusRequest by remember(routeKey) { mutableIntStateOf(-1) }
    var lastAutoPage by remember(routeKey) { mutableStateOf<String?>(null) }
    val loadingMore = items.lastOrNull() is FeedPostItem.LoadingItem
    val pageError = items.lastOrNull() as? FeedPostItem.ErrorItem
    val closeToEnd by remember(grid) {
        derivedStateOf {
            val layout = grid.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && lastVisible >= layout.totalItemsCount - 7
        }
    }
    LaunchedEffect(grid, routeKey) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) -> bookmark.index = index; bookmark.offset = offset }
    }
    LaunchedEffect(routeKey, focus.request, cards.isNotEmpty()) {
        if (cards.isNotEmpty() && !focus.sidebarHasFocus && handledFocusRequest != focus.request) {
            val index = cards.indexOfFirst { it.key == bookmark.focusedKey }.takeIf { it >= 0 } ?: 0
            val key = cards[index].key
            withFrameNanos { }
            if (grid.layoutInfo.visibleItemsInfo.none { it.key == key }) grid.scrollToItem(index)
            if (requestTvFocus({ requesters[key] }, { !focus.sidebarHasFocus })) handledFocusRequest = focus.request
        }
    }
    // A Boosty page may contain only text or images. Advance through those pages as well.
    val pageRequestKey = "$pageCursor:${items.count { it is FeedPostItem.PostItem }}"
    LaunchedEffect(pageRequestKey, closeToEnd, cards.size, loadingMore, pageError, hasMore, isRefreshing) {
        if (hasMore && !isRefreshing && !loadingMore && pageError == null &&
            (cards.size < 6 || closeToEnd) && lastAutoPage != pageRequestKey
        ) {
            lastAutoPage = pageRequestKey
            onLoadMore()
        }
    }
    if (cards.isEmpty()) {
        when {
            pageError != null -> TvStatusPanel("Не удалось продолжить загрузку", pageError.description, Icons.Rounded.WarningAmber, "Повторить", onRetryPage, focus)
            hasMore && (loadingMore || lastAutoPage != pageRequestKey) -> TvLoadingPanel("Ищем видео в публикациях…")
            hasMore -> TvStatusPanel("В этой подборке пока нет видео", "Можно загрузить более ранние публикации.", Icons.Rounded.VideoLibrary, "Загрузить ещё", onLoadMore, focus)
            else -> TvStatusPanel("Видео пока нет", "Здесь появятся видео из доступных публикаций.", Icons.Rounded.VideoLibrary, "Обновить", onRefresh, focus)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 920.dp) 4 else 3
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(17.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp),
        ) {
            itemsIndexed(cards, key = { _, card -> card.key }, contentType = { _, _ -> "video" }) { index, card ->
                TvVideoTile(
                    card = card,
                    requester = requesters.getOrPut(card.key) { FocusRequester() },
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        when {
                            event.key == Key.DirectionLeft && index % columns == 0 -> {
                                if (event.type == KeyEventType.KeyDown) focus.enterSidebar()
                                true
                            }
                            event.key == Key.DirectionRight && (index % columns == columns - 1 || index == cards.lastIndex) -> true
                            else -> false
                        }
                    },
                    onFocused = { bookmark.focusedKey = card.key; focus.contentFocused() },
                    onClick = { bookmark.focusedKey = card.key; onPlay(card) },
                )
            }
            if (loadingMore) item("loading_more", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = TvColors.Accent, strokeWidth = 2.dp)
                    Text("  Загружаем ещё…", color = TvColors.Secondary, fontSize = 13.sp)
                }
            } else if (pageError != null) item("page_error", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(pageError.description, color = TvColors.Secondary, fontSize = 13.sp, maxLines = 3, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    TvActionButton("Повторить", onRetryPage, Modifier.leftToSidebar(focus), Icons.Rounded.Refresh, focus::contentFocused)
                }
            } else if (hasMore) item("load_more", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    TvActionButton("Загрузить ещё", onLoadMore, Modifier.leftToSidebar(focus), onFocused = focus::contentFocused)
                }
            } else item("end", span = { GridItemSpan(maxLineSpan) }) {
                Text("Все видео загружены", color = TvColors.Secondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
        }
    }
}

@Composable
private fun TvVideoTile(
    card: TvVideoCard,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "videoFocus")
    TvFocusableSurface(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused }
            .zIndex(if (focused) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(12.dp),
    ) { hasFocus ->
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(3.dp).clip(RoundedCornerShape(9.dp)).background(TvColors.Surface)) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(36.dp).align(Alignment.Center), tint = TvColors.Secondary)
                if (card.thumbnailUrl.isNotBlank()) Image(
                    painter = rememberImagePainter(card.thumbnailUrl),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (card.isLocked) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)))
                    Row(Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = .78f)).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(12.dp), tint = Color.White)
                        Text("  Подписка", color = Color.White, fontSize = 10.sp)
                    }
                }
                val duration = formatVideoDuration(card.durationSeconds)
                if (duration.isNotEmpty()) Text(
                    duration,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .8f)).padding(horizontal = 5.dp, vertical = 2.dp),
                )
                if (card.progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = .6f))) {
                        Box(Modifier.fillMaxWidth(card.progress).height(3.dp).background(TvColors.Accent))
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                Text(card.title, color = TvColors.Text, fontSize = 15.sp, fontWeight = if (hasFocus) FontWeight.SemiBold else FontWeight.Medium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                Spacer(Modifier.height(6.dp))
                Text(card.creatorName, color = if (hasFocus) TvColors.Text else TvColors.Secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.post.createdAtString, color = TvColors.Secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun TvLoadingPanel(message: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(36.dp), color = TvColors.Accent, strokeWidth = 3.dp)
        Spacer(Modifier.height(18.dp))
        Text(message, color = TvColors.Secondary, fontSize = 15.sp)
    }
}

@Composable
internal fun TvStatusPanel(
    title: String,
    message: String,
    icon: ImageVector,
    action: String? = null,
    onAction: () -> Unit = {},
    focus: TvContentFocus? = null,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus?.request, action) {
        if (action != null && focus?.sidebarHasFocus == false) {
            requestTvFocus({ requester }, { focus?.sidebarHasFocus == false })
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(48.dp), tint = TvColors.Accent)
        Spacer(Modifier.height(16.dp))
        Text(title, color = TvColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(message, color = TvColors.Secondary, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            TvActionButton(action, onAction, Modifier.focusRequester(requester).let { if (focus != null) it.leftToSidebar(focus) else it }, onFocused = { focus?.contentFocused() })
        }
    }
}

@Composable
private fun TvUnavailableDialog(card: TvVideoCard, onDismiss: () -> Unit) {
    val requester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvColors.Sidebar,
        titleContentColor = TvColors.Text,
        textContentColor = TvColors.Secondary,
        icon = { Icon(if (card.isLocked) Icons.Rounded.Lock else Icons.Rounded.WarningAmber, null, tint = TvColors.Accent) },
        title = { Text(if (card.isLocked) "Нужна подписка" else "Видео недоступно") },
        text = { Text(if (card.isLocked) "Доступ к этому видео определяется вашей подпиской на Boosty. После изменения подписки обновите ленту." else "Ссылка для воспроизведения пока недоступна. Обновите ленту и попробуйте ещё раз.") },
        confirmButton = {
            LaunchedEffect(Unit) { requestTvFocus({ requester }) }
            TvActionButton("Понятно", onDismiss, Modifier.focusRequester(requester))
        },
    )
}

internal fun Modifier.leftToSidebar(focus: TvContentFocus): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.DirectionLeft) {
        if (event.type == KeyEventType.KeyDown) focus.enterSidebar()
        true
    } else false
}

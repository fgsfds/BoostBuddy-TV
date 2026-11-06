package ru.slartus.boostbuddy.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seiko.imageloader.rememberImagePainter
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.slartus.boostbuddy.components.subscribes.SubscribesViewState
import ru.slartus.boostbuddy.data.repositories.Blog

internal object TvColors {
    val Background = Color(0xFF101116)
    val Sidebar = Color(0xFF17181F)
    val Surface = Color(0xFF22242D)
    val Focused = Color(0xFF343742)
    val Accent = Color(0xFFFF8B45)
    val Text = Color(0xFFF5F5F7)
    val Secondary = Color(0xFFA5A8B7)
    val Line = Color(0xFF30323D)
}

@Stable
internal class TvContentFocus {
    var request by mutableIntStateOf(1)
        private set
    var sidebarHasFocus by mutableStateOf(false)
    internal var sidebarAction: () -> Unit = {}

    fun enterContent() {
        sidebarHasFocus = false
        request++
    }

    fun contentFocused() { sidebarHasFocus = false }
    fun enterSidebar() {
        if (sidebarHasFocus) return
        sidebarHasFocus = true
        sidebarAction()
    }
}

/** One native shell for the feed, subscriptions directory, search, and creator routes. */
@Composable
internal fun TvBrowseScreen(
    browseState: TvBrowseState,
    subscriptions: SubscribesViewState.ProgressState,
    selectedCreator: String?,
    onSectionSelected: (TvSection) -> Unit,
    onCreatorSelected: (Blog) -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onRefreshSubscriptions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (TvContentFocus) -> Unit,
) {
    val focus = remember { TvContentFocus() }
    val scope = rememberCoroutineScope()
    val sidebarBookmark = remember(browseState) { browseState.bookmark("sidebar") }
    val sidebarListState = rememberLazyListState(sidebarBookmark.index, sidebarBookmark.offset)
    LaunchedEffect(sidebarListState) {
        snapshotFlow { sidebarListState.firstVisibleItemIndex to sidebarListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) ->
                sidebarBookmark.index = index
                sidebarBookmark.offset = offset
            }
    }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val creators = remember(subscriptions) { subscriptions.creatorBlogs() }
    val sidebarKeys = remember(creators) {
        listOf("feed", "subscriptions", "search", "settings", "logout", "divider") + creators.map { "creator:${it.blogUrl}" }
    }
    val sidebarWidth by animateDpAsState(if (browseState.sidebarExpanded) 224.dp else 76.dp, label = "sidebarWidth")
    val requestSidebar: () -> Unit = {
        focus.sidebarHasFocus = true
        browseState.sidebarExpanded = true
        scope.launch {
            val target = browseState.sidebarFocusKey.takeIf { it in sidebarKeys } ?: "feed"
            val index = sidebarKeys.indexOf(target).coerceAtLeast(0)
            sidebarListState.scrollToItem(index)
            val restored = requestTvFocus({ requesters[target] })
            if (!restored) {
                sidebarListState.scrollToItem(0)
                if (!requestTvFocus({ requesters["feed"] })) focus.sidebarHasFocus = false
            }
        }
    }
    SideEffect { focus.sidebarAction = requestSidebar }

    // First Back returns to the navigation rail. A second Back follows the app's root navigation.
    BackHandler(enabled = !focus.sidebarHasFocus) { focus.enterSidebar() }

    Row(modifier.fillMaxSize().background(TvColors.Background)) {
        Column(
            Modifier.width(sidebarWidth).fillMaxHeight().background(TvColors.Sidebar)
                .padding(top = 20.dp, bottom = 12.dp),
        ) {
            Row(
                Modifier.height(52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvFocusableSurface(
                    onClick = { browseState.sidebarExpanded = !browseState.sidebarExpanded },
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    onFocused = { focus.sidebarHasFocus = true },
                ) { focused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (browseState.sidebarExpanded) Icons.Rounded.ChevronLeft else Icons.Rounded.Menu,
                            contentDescription = if (browseState.sidebarExpanded) "Свернуть меню" else "Развернуть меню",
                            tint = if (focused) TvColors.Text else TvColors.Accent,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                }
                if (browseState.sidebarExpanded) {
                    Text("BoostBuddy", color = TvColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = sidebarListState,
                modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val navigation = listOf(
                    Triple("feed", "Лента", Icons.Rounded.Home),
                    Triple("subscriptions", "Подписки", Icons.Rounded.Subscriptions),
                    Triple("search", "Поиск", Icons.Rounded.Search),
                    Triple("settings", "Настройки", Icons.Rounded.Settings),
                    Triple("logout", "Выйти", Icons.Rounded.Logout),
                )
                items(navigation, key = { it.first }) { (key, label, icon) ->
                    val section = when (key) {
                        "feed" -> TvSection.Feed
                        "subscriptions" -> TvSection.Subscriptions
                        "search" -> TvSection.Search
                        else -> null
                    }
                    TvSidebarItem(
                        title = label,
                        icon = icon,
                        expanded = browseState.sidebarExpanded,
                        selected = selectedCreator == null && section != null && browseState.section == section,
                        requester = requesters.getOrPut(key) { FocusRequester() },
                        onFocused = { focus.sidebarHasFocus = true; browseState.sidebarFocusKey = key },
                        onEnterContent = { browseState.sidebarExpanded = false; focus.enterContent() },
                        onClick = {
                            if (section != null) {
                                onSectionSelected(section)
                                focus.enterContent()
                            } else when (key) {
                                "settings" -> onSettings()
                                "logout" -> onLogout()
                            }
                        },
                    )
                }
                item("divider") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        HorizontalDivider(color = TvColors.Line)
                        if (browseState.sidebarExpanded) {
                            Spacer(Modifier.height(14.dp))
                            Text("АВТОРЫ", color = TvColors.Secondary, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                items(creators, key = { "creator:${it.blogUrl}" }) { creator ->
                    val key = "creator:${creator.blogUrl}"
                    TvSidebarItem(
                        title = creator.owner.name,
                        avatarUrl = creator.owner.avatarUrl,
                        expanded = browseState.sidebarExpanded,
                        selected = selectedCreator == creator.blogUrl,
                        requester = requesters.getOrPut(key) { FocusRequester() },
                        onFocused = { focus.sidebarHasFocus = true; browseState.sidebarFocusKey = key },
                        onEnterContent = { browseState.sidebarExpanded = false; focus.enterContent() },
                        onClick = {
                            browseState.sidebarFocusKey = key
                            if (selectedCreator != creator.blogUrl) onCreatorSelected(creator)
                            focus.enterContent()
                        },
                    )
                }
                when (subscriptions) {
                    SubscribesViewState.ProgressState.Init,
                    SubscribesViewState.ProgressState.Loading -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = TvColors.Accent, strokeWidth = 2.dp)
                        }
                    }
                    is SubscribesViewState.ProgressState.Error -> item("subscriptions_error") {
                        TvSidebarItem(
                            title = "Повторить загрузку",
                            icon = Icons.Rounded.Refresh,
                            expanded = browseState.sidebarExpanded,
                            selected = false,
                            requester = requesters.getOrPut("subscriptions_error") { FocusRequester() },
                            onFocused = { focus.sidebarHasFocus = true },
                            onEnterContent = { focus.enterContent() },
                            onClick = onRefreshSubscriptions,
                        )
                    }
                    is SubscribesViewState.ProgressState.Loaded -> if (creators.isEmpty() && browseState.sidebarExpanded) item("no_creators") {
                        Text("Подписок пока нет", color = TvColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                    }
                }
            }
            if (browseState.sidebarExpanded) {
                Row(Modifier.padding(start = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = TvColors.Secondary, modifier = Modifier.size(13.dp))
                    Text("  Назад · меню", color = TvColors.Secondary, fontSize = 11.sp)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 26.dp, top = 23.dp, bottom = 16.dp)) {
            content(focus)
        }
    }
}

@Composable
private fun TvSidebarItem(
    title: String,
    expanded: Boolean,
    selected: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onEnterContent: () -> Unit,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    avatarUrl: String? = null,
) {
    TvFocusableSurface(
        onClick = onClick,
        selected = selected,
        onFocused = onFocused,
        modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(requester)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionRight) {
                    if (event.type == KeyEventType.KeyDown) onEnterContent()
                    true
                } else false
            },
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, title, tint = if (selected && !focused) TvColors.Accent else TvColors.Text, modifier = Modifier.size(24.dp))
            } else {
                TvAvatar(avatarUrl, title, Modifier.size(30.dp))
            }
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(title, color = TvColors.Text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun TvFocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(10.dp),
    onFocused: () -> Unit = {},
    content: @Composable (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        }.semantics { this.selected = selected }
            .clip(shape)
            .background(when { focused -> TvColors.Focused; selected -> TvColors.Accent.copy(alpha = 0.14f); else -> Color.Transparent })
            .border(if (focused) 2.dp else 1.dp, when { focused -> TvColors.Text; selected -> TvColors.Accent.copy(alpha = .5f); else -> Color.Transparent }, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
    ) { content(focused) }
}

@Composable
internal fun TvAvatar(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(TvColors.Line), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "B", color = TvColors.Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (!url.isNullOrBlank()) Image(rememberImagePainter(url), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onFocused: () -> Unit = {},
) {
    TvFocusableSurface(onClick, modifier.height(42.dp), shape = RoundedCornerShape(9.dp), onFocused = onFocused) { focused ->
        Row(
            Modifier.fillMaxHeight().background(if (focused) Color.Transparent else TvColors.Surface).padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = TvColors.Text)
            Text(text, color = TvColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

internal fun SubscribesViewState.ProgressState.creatorBlogs(): List<Blog> = when (this) {
    is SubscribesViewState.ProgressState.Loaded -> (listOfNotNull(myBlog) + items.map { it.blog }).distinctBy { it.blogUrl }
    else -> emptyList()
}

/** Lazy items and dialog windows may attach a frame after the route becomes active. */
internal suspend fun requestTvFocus(
    requester: () -> FocusRequester?,
    canRequest: () -> Boolean = { true },
): Boolean {
    repeat(3) {
        withFrameNanos { }
        if (!canRequest()) return false
        if (runCatching { requester()?.let { it.requestFocus(); true } ?: false }.getOrDefault(false)) return true
    }
    return false
}

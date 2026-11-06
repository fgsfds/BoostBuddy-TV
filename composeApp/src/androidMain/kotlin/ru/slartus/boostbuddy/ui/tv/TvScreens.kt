package ru.slartus.boostbuddy.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import ru.slartus.boostbuddy.components.blog.BlogComponent
import ru.slartus.boostbuddy.components.main.MainComponent
import ru.slartus.boostbuddy.components.subscribes.SubscribesViewState
import ru.slartus.boostbuddy.components.tv.videoCardsFromPosts
import ru.slartus.boostbuddy.data.repositories.Blog

/** Android-only TV entry. Keep [browseState] above the root navigation stack. */
@Composable
fun TvMainScreen(
    component: MainComponent,
    browseState: TvBrowseState,
    modifier: Modifier = Modifier,
) {
    val feed by component.feedComponent.viewStates.subscribeAsState()
    val subscriptions by component.subscribesComponent.viewStates.subscribeAsState()
    val cards = remember(feed.items) { videoCardsFromPosts(feed.items) }
    val searchVisible = browseState.section == TvSection.Search
    TvBrowseScreen(
        browseState = browseState,
        subscriptions = subscriptions.progressProgressState,
        selectedCreator = null,
        onSectionSelected = { section ->
            browseState.section = section
            if (section == TvSection.Feed && feed.searchQuery.isNotBlank()) component.feedComponent.search("")
        },
        onCreatorSelected = component.subscribesComponent::onMyBlogClicked,
        onSettings = component.topBarComponent::onSettingsClicked,
        onLogout = component.topBarComponent::onLogoutClicked,
        onRefreshSubscriptions = component.subscribesComponent::refresh,
        modifier = modifier,
    ) { focus ->
        if (browseState.section == TvSection.Subscriptions) {
            TvCreatorDirectory(
                subscriptions = subscriptions.progressProgressState,
                browseState = browseState,
                focus = focus,
                onCreatorSelected = component.subscribesComponent::onMyBlogClicked,
                onRefresh = component.subscribesComponent::refresh,
            )
        } else {
            TvVideoBrowseContent(
                routeKey = if (searchVisible) "search:${feed.searchQuery}" else "feed",
                title = if (searchVisible) "Поиск" else "Лента",
                subtitle = if (searchVisible) "Видео из публикаций Boosty" else "Последние видео из ваших подписок",
                cards = cards,
                items = feed.items,
                progress = feed.progressState,
                isRefreshing = feed.isRefreshing,
                hasMore = feed.hasMore,
                pageCursor = feed.extra?.offset,
                browseState = browseState,
                focus = focus,
                searchVisible = searchVisible,
                searchQuery = feed.searchQuery,
                onSearch = component.feedComponent::search,
                onRefresh = component.feedComponent::refresh,
                onLoadMore = component.feedComponent::onScrolledToEnd,
                onRetry = component.feedComponent::onRepeatClicked,
                onRetryPage = component.feedComponent::onErrorItemClicked,
                onPlay = { card -> component.feedComponent.onVideoItemClicked(card.post, card.video) },
            )
        }
    }
}

/** The retained main controller supplies the same subscriptions and settings on creator pages. */
@Composable
fun TvBlogScreen(
    component: BlogComponent,
    mainComponent: MainComponent?,
    browseState: TvBrowseState,
    modifier: Modifier = Modifier,
) {
    val blog by component.viewStates.subscribeAsState()
    val subscriptions = if (mainComponent != null) {
        val state by mainComponent.subscribesComponent.viewStates.subscribeAsState()
        state.progressProgressState
    } else {
        SubscribesViewState.ProgressState.Loaded(kotlinx.collections.immutable.persistentListOf(), null)
    }
    val cards = remember(blog.items) { videoCardsFromPosts(blog.items) }
    val openCreator: (Blog) -> Unit = { creator ->
        mainComponent?.subscribesComponent?.onMyBlogClicked(creator)
    }
    TvBrowseScreen(
        browseState = browseState,
        subscriptions = subscriptions,
        selectedCreator = blog.blog.blogUrl,
        onSectionSelected = { section ->
            browseState.section = section
            if (section == TvSection.Feed) mainComponent?.feedComponent?.search("")
            component.onBackClicked()
        },
        onCreatorSelected = openCreator,
        onSettings = { mainComponent?.topBarComponent?.onSettingsClicked() },
        onLogout = { mainComponent?.topBarComponent?.onLogoutClicked() },
        onRefreshSubscriptions = { mainComponent?.subscribesComponent?.refresh() },
        modifier = modifier,
    ) { focus ->
        TvVideoBrowseContent(
            routeKey = "blog:${blog.blog.blogUrl}:${blog.searchQuery}",
            title = blog.blog.title.ifBlank { blog.blog.owner.name },
            subtitle = blog.blog.owner.name,
            avatarUrl = blog.blog.owner.avatarUrl,
            cards = cards,
            items = blog.items,
            progress = blog.progressState,
            isRefreshing = blog.isRefreshing,
            hasMore = blog.hasMore,
            pageCursor = blog.extra?.offset,
            browseState = browseState,
            focus = focus,
            searchVisible = false,
            searchQuery = blog.searchQuery,
            onSearch = component::onSearchQueryChange,
            onRefresh = component::onPullToRefresh,
            onLoadMore = component::onScrolledToEnd,
            onRetry = component::onRepeatClicked,
            onRetryPage = component::onErrorItemClicked,
            onPlay = { card -> component.onVideoItemClicked(card.post, card.video) },
        )
    }
}

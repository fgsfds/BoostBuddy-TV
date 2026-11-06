// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.feed

import androidx.compose.runtime.Immutable
import com.arkivanov.decompose.ComponentContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.slartus.boostbuddy.components.video.usableOptions
import ru.slartus.boostbuddy.components.BaseComponent
import ru.slartus.boostbuddy.components.common.ProgressState
import ru.slartus.boostbuddy.data.Inject
import ru.slartus.boostbuddy.data.repositories.Blog
import ru.slartus.boostbuddy.data.repositories.Owner
import ru.slartus.boostbuddy.data.repositories.PostRepository
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import ru.slartus.boostbuddy.data.repositories.models.Content
import ru.slartus.boostbuddy.data.repositories.models.Extra
import ru.slartus.boostbuddy.data.repositories.models.Poll
import ru.slartus.boostbuddy.data.repositories.models.PollOption
import ru.slartus.boostbuddy.data.repositories.models.Post
import ru.slartus.boostbuddy.data.repositories.models.Posts
import ru.slartus.boostbuddy.navigation.NavigationRouter
import ru.slartus.boostbuddy.navigation.NavigationTree
import ru.slartus.boostbuddy.navigation.navigateTo
import ru.slartus.boostbuddy.utils.messageOrThrow

@Immutable
sealed class FeedPostItem(val key: String, val contentType: String) {
    data class PostItem(val post: Post) : FeedPostItem(post.id, "post")
    data object LoadingItem : FeedPostItem("loading", "loading")
    data class ErrorItem(val description: String) : FeedPostItem("error", "error")
}

abstract class PostsFeedComponent<State : Any, Action>(
    componentContext: ComponentContext,
    initState: State,
) : BaseComponent<State, Action>(
    componentContext,
    initState
) {
    // Resolve the container only when a dependency is used. Feed loading can be
    // exercised independently without creating or replacing global app DI.
    private val navigationRouter by lazy { Inject.instance<NavigationRouter>() }
    private val settingsRepository by lazy { Inject.instance<SettingsRepository>() }
    private val postRepository by lazy { Inject.instance<PostRepository>() }

    private var fetchJob: Job? = null
    private var fetchGeneration = 0L
    private val successfulCursors = mutableSetOf<String>()

    protected abstract val extra: Extra?
    protected abstract val viewStateItems: List<FeedPostItem>

    protected fun subscribeToken() {
        scope.launch {
            settingsRepository.sessionFlow
                .map { it.sessionId to it.isAuthenticated }
                .distinctUntilChanged()
                .collect { (sessionId, authenticated) -> sessionChanged(sessionId, authenticated) }
        }
    }

    protected open fun sessionChanged(sessionId: Long, authenticated: Boolean) {
        clearFeed()
        if (authenticated) fetchData()
    }

    // Read the atomic snapshot as well as observing it: a logout can happen before
    // its flow collector gets a turn to cancel an in-flight request.
    protected open fun authenticatedSessionId(): Long? = settingsRepository.sessionSnapshot()
        .takeIf { it.isAuthenticated }?.sessionId

    protected fun isCurrentSession(sessionId: Long?): Boolean =
        sessionId != null && authenticatedSessionId() == sessionId

    private fun clearFeed() {
        fetchGeneration++
        fetchJob?.cancel()
        successfulCursors.clear()
        onNewItems(emptyList<FeedPostItem>().toImmutableList())
        onIsRefreshingChanged(false)
        onProgressStateChanged(ProgressState.Init)
    }

    protected abstract suspend fun fetch(offset: String?): Result<Posts>

    protected abstract fun onProgressStateChanged(progressState: ProgressState)

    protected abstract fun onNewItems(
        items: ImmutableList<FeedPostItem>,
        extra: Extra? = null
    )

    protected open fun onIsRefreshingChanged(isRefreshing: Boolean) = Unit

    private fun fetchData(isRefresh: Boolean = false) {
        val sessionId = authenticatedSessionId() ?: return clearFeed()
        val generation = ++fetchGeneration
        fetchJob?.cancel()
        successfulCursors.clear()
        // A cursor belongs to one search/filter snapshot. A failed refresh must
        // never continue paging the previous query or leave its footer visible.
        onNewItems(viewStateItems.filterIsInstance<FeedPostItem.PostItem>().toImmutableList())
        onIsRefreshingChanged(isRefresh)
        onProgressStateChanged(ProgressState.Loading)
        fetchJob = scope.launch {
            try {
                val response = fetch(null)
                coroutineContext.ensureActive()
                if (generation != fetchGeneration || !isCurrentSession(sessionId)) return@launch
                if (response.isSuccess) {
                    val data = response.getOrNull()
                    val newItems = data?.items.orEmpty()
                        .map { FeedPostItem.PostItem(it) }
                        .distinctBy { it.key }
                        .toImmutableList()
                    onNewItems(items = newItems, extra = usableCursor(data?.extra))
                    onProgressStateChanged(ProgressState.Loaded)
                } else {
                    onProgressStateChanged(ProgressState.Error(
                        response.exceptionOrNull()?.messageOrThrow() ?: "Unable to load videos"
                    ))
                }
            } finally {
                if (generation == fetchGeneration) onIsRefreshingChanged(false)
            }
        }
    }

    private fun fetchData(offset: String) {
        if (fetchJob?.isActive == true) return
        val sessionId = authenticatedSessionId() ?: return clearFeed()
        val generation = fetchGeneration
        val previousExtra = extra
        onNewItems(viewStateItems.plusItem(FeedPostItem.LoadingItem), previousExtra)
        fetchJob = scope.launch {
            val response = fetch(offset)
            coroutineContext.ensureActive()
            if (generation != fetchGeneration || !isCurrentSession(sessionId)) return@launch
            if (response.isSuccess) {
                val data = response.getOrNull()
                successfulCursors.add(offset)
                val nextExtra = usableCursor(data?.extra)?.let {
                    if (it.offset in successfulCursors) it.copy(isLast = true) else it
                }
                val newItems = viewStateItems.plusItems(
                    data?.items.orEmpty().map { FeedPostItem.PostItem(it) }
                )
                onNewItems(newItems, nextExtra)
                onProgressStateChanged(ProgressState.Loaded)
            } else {
                onNewItems(viewStateItems.plusItem(FeedPostItem.ErrorItem(
                    response.exceptionOrNull()?.messageOrThrow() ?: "Unable to load more videos"
                )), previousExtra)
            }
        }
    }

    private fun usableCursor(value: Extra?): Extra? = value?.let {
        if (it.offset.isBlank()) it.copy(isLast = true) else it
    }

    protected fun fetchNext() {
        if (fetchJob?.isActive == true) return
        val page = extra?.takeUnless { it.isLast } ?: return
        fetchData(page.offset)
    }

    fun onVideoItemClicked(post: Post, postData: Content.OkVideo) {
        if (!post.hasAccess) {
            navigationRouter.navigateTo(NavigationTree.Message("Your account does not have access to this post."))
            return
        }
        if (postData.playerUrls.usableOptions().isEmpty()) {
            navigationRouter.navigateTo(NavigationTree.Message("This video is not ready to play. Refresh the feed and try again later."))
            return
        }
        navigationRouter.navigateTo(
            NavigationTree.VideoType(
                blogUrl = post.user.blogUrl,
                postId = post.id,
                postData = postData
            )
        )
    }

    fun refresh() {
        fetchData()
    }

    open fun onPullToRefresh() {
        fetchData(isRefresh = true)
    }

    fun onErrorItemClicked() {
        fetchNext()
    }

    fun onCommentsClicked(post: Post) {
        navigationRouter.navigateTo(NavigationTree.BlogPost(post))
    }

    private suspend fun refreshPoll(post: Post, pollId: Int, generation: Long, sessionId: Long?) {
        if (generation != fetchGeneration || !isCurrentSession(sessionId)) return
        val pollResponse = postRepository.getPoll(post.user.blogUrl, pollId)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (pollResponse.isSuccess && generation == fetchGeneration && isCurrentSession(sessionId)) {
            val updatedPoll = pollResponse.getOrThrow()
            replacePoll(updatedPoll)
        }
    }

    fun onPollOptionClicked(post: Post, poll: Poll, pollOption: PollOption) {
        val generation = fetchGeneration
        val sessionId = authenticatedSessionId()
        scope.launch {
            if (generation != fetchGeneration || !isCurrentSession(sessionId)) return@launch
            if (poll.isMultiple) {
                val newPoll = if (pollOption.id in poll.checked)
                    poll.copy(checked = poll.checked - pollOption.id)
                else
                    poll.copy(checked = poll.checked + pollOption.id)
                replacePoll(newPoll)
            } else {
                if (pollOption.id in poll.answer)
                    postRepository.deletePollVote(poll.id)
                else
                    postRepository.pollVote(poll.id, listOf(pollOption.id))

                refreshPoll(post, poll.id, generation, sessionId)
            }
        }
    }

    fun onVoteClicked(post: Post, poll: Poll) {
        val generation = fetchGeneration
        val sessionId = authenticatedSessionId()
        scope.launch {
            if (generation != fetchGeneration || !isCurrentSession(sessionId)) return@launch
            postRepository.pollVote(poll.id, poll.checked.toList())

            refreshPoll(post, poll.id, generation, sessionId)
        }
    }

    fun onDeleteVoteClicked(post: Post, poll: Poll) {
        val generation = fetchGeneration
        val sessionId = authenticatedSessionId()
        scope.launch {
            if (generation != fetchGeneration || !isCurrentSession(sessionId)) return@launch
            postRepository.deletePollVote(poll.id)

            refreshPoll(post, poll.id, generation, sessionId)
        }
    }

    fun onBlogClicked(post: Post) {
        navigationRouter.navigateTo(
            NavigationTree.Blog(
                Blog(
                    title = "",
                    blogUrl = post.user.blogUrl,
                    owner = Owner(
                        name = post.user.name,
                        avatarUrl = post.user.avatarUrl
                    )
                )
            )
        )
    }

    private fun replacePoll(newPoll: Poll) {
        onNewItems(viewStateItems.map { item ->
            if (item is FeedPostItem.PostItem && item.post.poll?.id == newPoll.id)
                item.copy(post = item.post.copy(poll = newPoll))
            else item
        }.toImmutableList(), extra)
    }

    companion object {
        private fun List<FeedPostItem>.plusItems(items: List<FeedPostItem>): ImmutableList<FeedPostItem> =
            dropLastWhile { it !is FeedPostItem.PostItem }
                .plus(items)
                .distinctBy { it.key }
                .toImmutableList()

        private fun List<FeedPostItem>.plusItem(item: FeedPostItem): ImmutableList<FeedPostItem> =
            dropLastWhile { it !is FeedPostItem.PostItem }
                .plus(item)
                .distinctBy { it.key }
                .toImmutableList()
    }
}

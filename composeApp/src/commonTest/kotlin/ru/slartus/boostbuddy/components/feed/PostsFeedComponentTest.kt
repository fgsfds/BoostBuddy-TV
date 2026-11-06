package ru.slartus.boostbuddy.components.feed

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import ru.slartus.boostbuddy.components.common.ProgressState
import ru.slartus.boostbuddy.data.repositories.models.Extra
import ru.slartus.boostbuddy.data.repositories.models.Poll
import ru.slartus.boostbuddy.data.repositories.models.PollOption
import ru.slartus.boostbuddy.data.repositories.models.Post
import ru.slartus.boostbuddy.data.repositories.models.PostCount
import ru.slartus.boostbuddy.data.repositories.models.Posts
import ru.slartus.boostbuddy.data.repositories.models.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PostsFeedComponentTest {
    private val dispatcher = StandardTestDispatcher()
    private val components = mutableListOf<TestFeed>()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() {
        components.forEach { it.scope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun aCancelledOlderRefreshCannotReplaceTheNewResults() = runTest(dispatcher) {
        val old = CompletableDeferred<Posts>()
        var call = 0
        val feed = feed {
            if (call++ == 0) Result.success(withContext(NonCancellable) { old.await() })
            else page("new")
        }
        feed.refresh()
        runCurrent()
        feed.refresh()
        runCurrent()
        old.complete(Posts(listOf(post("old")), Extra("old-cursor", false)))
        runCurrent()
        assertEquals(listOf("new"), feed.postIds())
        assertEquals("new-cursor", feed.viewStates.value.extra?.offset)
    }

    @Test
    fun failedRefreshInvalidatesTheOldCursorAndFooter() = runTest(dispatcher) {
        var call = 0
        val feed = feed {
            if (call++ == 0) page("old") else Result.failure(IllegalStateException("offline"))
        }
        feed.refresh()
        runCurrent()
        feed.refresh()
        assertNull(feed.viewStates.value.extra)
        runCurrent()
        feed.next()
        runCurrent()
        assertEquals(2, call)
        assertIs<ProgressState.Error>(feed.viewStates.value.progressState)
        assertTrue(feed.viewStates.value.items.all { it is FeedPostItem.PostItem })
    }

    @Test
    fun aLatePageCannotAppendToARefreshedQuery() = runTest(dispatcher) {
        val oldPage = CompletableDeferred<Posts>()
        var firstPages = 0
        val feed = feed { offset ->
            if (offset != null) Result.success(withContext(NonCancellable) { oldPage.await() })
            else page(if (firstPages++ == 0) "old" else "new")
        }
        feed.refresh()
        runCurrent()
        feed.next()
        runCurrent()
        assertIs<FeedPostItem.LoadingItem>(feed.viewStates.value.items.last())
        feed.refresh()
        runCurrent()
        oldPage.complete(Posts(listOf(post("old-page")), null))
        runCurrent()
        assertEquals(listOf("new"), feed.postIds())
    }

    @Test
    fun logoutClearsRowsAndRejectsAnUncooperativeResponse() = runTest(dispatcher) {
        val response = CompletableDeferred<Posts>()
        val feed = feed { Result.success(withContext(NonCancellable) { response.await() }) }
        feed.refresh()
        runCurrent()
        feed.changeSession(null)
        response.complete(Posts(listOf(post("private")), Extra("cursor", false)))
        runCurrent()
        assertTrue(feed.viewStates.value.items.isEmpty())
        assertNull(feed.viewStates.value.extra)
        assertEquals(ProgressState.Init, feed.viewStates.value.progressState)
        assertFalse(feed.viewStates.value.isRefreshing)
    }

    @Test
    fun atomicSessionCheckRejectsResultsBeforeTheLogoutCollectorRuns() = runTest(dispatcher) {
        val response = CompletableDeferred<Posts>()
        val feed = feed { Result.success(response.await()) }
        feed.refresh()
        runCurrent()
        feed.sessionId = null // The flow collector has not processed this snapshot yet.
        response.complete(Posts(listOf(post("private")), null))
        runCurrent()
        assertTrue(feed.viewStates.value.items.isEmpty())
        feed.changeSession(null)
        assertEquals(ProgressState.Init, feed.viewStates.value.progressState)
    }

    @Test
    fun choosingAPollOptionPreservesTheNextPageCursor() = runTest(dispatcher) {
        val option = PollOption(7, "Option", 0, 0)
        val poll = Poll(9, listOf("Question"), true, false, listOf(option), 0, emptyList())
        val post = post("post").copy(poll = poll)
        val cursor = Extra("next-page", false)
        val feed = feed { Result.success(Posts(listOf(post), cursor)) }
        feed.refresh()
        runCurrent()
        feed.onPollOptionClicked(post, poll, option)
        runCurrent()
        assertEquals(cursor, feed.viewStates.value.extra)
        assertEquals(setOf(7), (feed.viewStates.value.items.single() as FeedPostItem.PostItem).post.poll?.checked)
    }

    @Test
    fun repeatedPageCursorsStopPaginationAndDuplicatePostsAreNotAppended() = runTest(dispatcher) {
        var calls = 0
        val feed = feed {
            calls++
            Result.success(Posts(listOf(post("same")), Extra("repeated", false)))
        }
        feed.refresh()
        runCurrent()
        feed.next()
        runCurrent()
        feed.next()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("same"), feed.postIds())
        assertEquals(true, feed.viewStates.value.extra?.isLast)
    }

    private fun feed(fetch: suspend (String?) -> Result<Posts>) =
        TestFeed(fetch).also { components += it }

    private fun page(id: String) = Result.success(Posts(listOf(post(id)), Extra("$id-cursor", false)))
    private fun post(id: String) = Post(id, 0, "", 1, id, emptyList(), emptyList(),
        User("Creator", null, "creator"), PostCount(0, 0), null, true)

    private class TestFeed(private val load: suspend (String?) -> Result<Posts>) :
        PostsFeedComponent<FeedViewState, Any>(DefaultComponentContext(LifecycleRegistry()), FeedViewState()) {
        var sessionId: Long? = 1L
        override val extra get() = viewState.extra
        override val viewStateItems get() = viewState.items
        override fun authenticatedSessionId() = sessionId
        override suspend fun fetch(offset: String?) = load(offset)
        override fun onProgressStateChanged(progressState: ProgressState) {
            viewState = viewState.copy(progressState = progressState)
        }
        override fun onNewItems(items: ImmutableList<FeedPostItem>, extra: Extra?) {
            viewState = viewState.copy(items = items, extra = extra)
        }
        override fun onIsRefreshingChanged(isRefreshing: Boolean) {
            viewState = viewState.copy(isRefreshing = isRefreshing)
        }
        fun next() = fetchNext()
        fun changeSession(id: Long?) {
            sessionId = id
            sessionChanged(id ?: -1, id != null)
        }
        fun postIds() = viewStates.value.items.filterIsInstance<FeedPostItem.PostItem>().map { it.post.id }
    }
}

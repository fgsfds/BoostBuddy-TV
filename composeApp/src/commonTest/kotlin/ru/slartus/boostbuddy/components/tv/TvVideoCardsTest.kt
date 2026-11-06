package ru.slartus.boostbuddy.components.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import ru.slartus.boostbuddy.components.feed.FeedPostItem
import ru.slartus.boostbuddy.data.repositories.models.Content
import ru.slartus.boostbuddy.data.repositories.models.PlayerUrl
import ru.slartus.boostbuddy.data.repositories.models.Post
import ru.slartus.boostbuddy.data.repositories.models.PostCount
import ru.slartus.boostbuddy.data.repositories.models.User
import ru.slartus.boostbuddy.data.repositories.models.VideoQuality

class TvVideoCardsTest {
    @Test
    fun mixedPostsBecomeOneCardPerVideoInOrder() {
        val first = post("one", listOf(Content.Image("image"), video("a"), video("b")))
        val second = post("two", listOf(video("c")))
        val cards = videoCardsFromPosts(listOf(FeedPostItem.PostItem(first), FeedPostItem.PostItem(second)))
        assertEquals(listOf("a", "b", "c"), cards.map { it.video.id })
        assertSame(first, cards.first().post)
        assertTrue(cards.all { it.isPlayable })
    }

    @Test
    fun overlappingPagesKeepStableUniqueCards() {
        val one = FeedPostItem.PostItem(post("one", listOf(video("same"))))
        val two = FeedPostItem.PostItem(post("two", listOf(video("same"))))
        val cards = videoCardsFromPosts(listOf(one, one, FeedPostItem.LoadingItem, two))
        assertEquals(2, cards.size)
        assertEquals(2, cards.map { it.key }.toSet().size)
    }

    @Test
    fun teaserUrlsDoNotTurnLockedContentIntoPlayableVideos() {
        val locked = post("locked", emptyList()).copy(
            hasAccess = false,
            teaser = listOf(video("preview")),
        )
        val card = videoCardsFromPosts(listOf(FeedPostItem.PostItem(locked))).single()
        assertTrue(card.isLocked)
        assertFalse(card.isPlayable)
    }

    @Test
    fun processingVideoKeepsItsCardButCannotBePlayed() {
        val content = video("pending").copy(playerUrls = emptyList())
        val card = videoCardsFromPosts(listOf(FeedPostItem.PostItem(post("one", listOf(content))))).single()
        assertFalse(card.isLocked)
        assertFalse(card.isPlayable)
    }

    @Test
    fun nonVideoPagesAndLoadingRowsDoNotProduceFakeCards() {
        assertTrue(videoCardsFromPosts(listOf(
            FeedPostItem.PostItem(post("text", listOf(Content.Image("image")))),
            FeedPostItem.ErrorItem("Network unavailable"),
        )).isEmpty())
    }

    @Test
    fun durationAndResumeProgressUseSecondsAndClamp() {
        val content = video("a").copy(durationSeconds = 120, timeCode = 30)
        val card = TvVideoCard(post("one", listOf(content)), content, false)
        assertEquals(0.25f, card.progress)
        assertEquals(1f, card.copy(video = content.copy(timeCode = 999)).progress)
        assertEquals(0f, card.copy(video = content.copy(durationSeconds = 0)).progress)
        assertEquals("2:00", formatVideoDuration(120))
        assertEquals("1:01:01", formatVideoDuration(3661))
    }

    private fun video(id: String) = Content.OkVideo(
        id = id, vid = id, title = "Video $id",
        playerUrls = listOf(PlayerUrl(VideoQuality.HLS, "https://example.com/$id.m3u8")),
        previewUrl = "https://example.com/$id.jpg", timeCode = 0,
    )

    private fun post(id: String, data: List<Content>) = Post(
        id = id, createdAt = 0, signedQuery = "", intId = 1, title = "Post $id",
        data = data, teaser = emptyList(), user = User("Creator", null, "creator"),
        count = PostCount(0, 0), poll = null, hasAccess = true,
    )
}

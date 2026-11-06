package ru.slartus.boostbuddy.components.video

import ru.slartus.boostbuddy.data.repositories.models.Content
import ru.slartus.boostbuddy.data.repositories.models.LiveStream
import ru.slartus.boostbuddy.data.repositories.models.PlayerUrl
import ru.slartus.boostbuddy.data.repositories.models.Post
import ru.slartus.boostbuddy.data.repositories.models.PostCount
import ru.slartus.boostbuddy.data.repositories.models.User
import ru.slartus.boostbuddy.data.repositories.models.VideoQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerUrlSelectionTest {
    private val hls = PlayerUrl(VideoQuality.HLS, "https://example.test/new.m3u8")
    private val hd = PlayerUrl(VideoQuality.Q720P, "https://example.test/new.mp4")
    private val video = Content.OkVideo("video", "vid", "Video", listOf(hls, hd), "", 42)
    private val post = Post("post", 0, "", 1, "Post", listOf(video), emptyList(),
        User("Creator", null, "creator"), PostCount(0, 0), null, true)

    @Test
    fun refreshedPostUsesItsFreshUrlForTheSelectedQuality() {
        val playback = post.resolvePlayback("video", VideoQuality.Q720P)!!
        assertEquals(hd, playback.playerUrl)
        assertEquals(42_000L, playback.video.timeCodeMs)
        assertEquals("Post", playback.title)
    }

    @Test
    fun deniedOrRemovedVideoCannotFallBackToAStaleFeedUrl() {
        assertNull(post.copy(hasAccess = false).resolvePlayback("video", VideoQuality.HLS))
        assertNull(post.copy(data = emptyList()).resolvePlayback("video", VideoQuality.HLS))
        assertNull(post.resolvePlayback("different-video", VideoQuality.HLS))
    }

    @Test
    fun blankAndUnsupportedOptionsAreExcludedAndAdaptiveFallbackWorks() {
        val urls = listOf(PlayerUrl(VideoQuality.Q1080P, "  "),
            PlayerUrl(VideoQuality.UNKNOWN, "https://example.test/unknown"), hls, hd)
        assertEquals(listOf(hls, hd), urls.usableOptions())
        assertEquals(hls, urls.pickPlayerUrl(VideoQuality.Q1080P))
        assertEquals(listOf(hd), urls.downloadableOptions())
    }

    @Test
    fun recoveryRequiresAccessToTheSameCurrentlyLiveStream() {
        val stream = LiveStream("stream", 1, "creator", "Live", LiveStream.Status.Live(0),
            0, 0, false, true, "", null, null, video)
        assertEquals(hls, stream.resolvePlayback("stream", null)?.playerUrl)
        assertNull(stream.copy(hasAccess = false).resolvePlayback("stream", null))
        assertNull(stream.copy(status = LiveStream.Status.Scheduled).resolvePlayback("stream", null))
        assertNull(stream.copy(id = "next-stream").resolvePlayback("stream", null))
    }

    @Test
    fun invalidResumeOffsetsCannotBecomeNegativeOrOverflow() {
        assertEquals(0L, video.copy(timeCode = -1).timeCodeMs)
        assertEquals(Long.MAX_VALUE / 1000L * 1000L, video.copy(timeCode = Long.MAX_VALUE).timeCodeMs)
    }
}

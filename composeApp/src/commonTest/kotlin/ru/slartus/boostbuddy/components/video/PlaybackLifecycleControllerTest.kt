package ru.slartus.boostbuddy.components.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackLifecycleControllerTest {
    private class FakePlayer(var position: Long, var playing: Boolean) {
        var released = false
    }

    @Test
    fun stopReleasesSynchronouslyEvenWhenTheUiHasNotProcessedItsUpdate() {
        val pendingUiUpdates = mutableListOf<FakePlayer?>()
        val controller = controller(onChanged = { pendingUiUpdates += it })
        controller.start()
        val player = controller.current!!
        controller.stop()
        assertTrue(player.released)
        assertNull(controller.current)
        // No renderer consumed these notifications; ownership still ended immediately.
        assertEquals(listOf(player, null), pendingUiUpdates)
    }

    @Test
    fun restartCreatesAnotherPlayerWithTheSavedPositionAndPausedIntent() {
        val controller = controller()
        controller.start()
        val first = controller.current!!
        first.position = 23_500
        first.playing = false
        controller.stop()
        controller.start()
        val second = controller.current!!
        assertNotSame(first, second)
        assertEquals(23_500L, second.position)
        assertFalse(second.playing)
        assertFalse(second.released)
    }

    @Test
    fun disposalAfterStopDoesNotResnapshotOrReleaseTheOldPlayer() {
        var snapshots = 0
        var releases = 0
        val controller = PlaybackLifecycleController(
            initialCheckpoint = PlaybackCheckpoint(10_000, true),
            create = { FakePlayer(it.position, it.playWhenReady) },
            snapshot = { snapshots++; PlaybackCheckpoint(it.position, it.playing) },
            release = { releases++; it.released = true; it.position = 0 },
            onChanged = {},
        )
        controller.start()
        controller.start()
        controller.stop()
        controller.stop()
        controller.start()
        assertEquals(1, snapshots)
        assertEquals(1, releases)
        assertEquals(10_000L, controller.current?.position)
    }

    private fun controller(onChanged: (FakePlayer?) -> Unit = {}) = PlaybackLifecycleController(
        initialCheckpoint = PlaybackCheckpoint(0, true),
        create = { FakePlayer(it.position, it.playWhenReady) },
        snapshot = { PlaybackCheckpoint(it.position, it.playing) },
        release = { it.released = true },
        onChanged = onChanged,
    )
}

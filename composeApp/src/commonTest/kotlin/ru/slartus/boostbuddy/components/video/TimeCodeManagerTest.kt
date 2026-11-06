package ru.slartus.boostbuddy.components.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimeCodeManagerTest {
    @Test
    fun finalSaveIncludesTheLastPositionBelowThePeriodicThreshold() = runTest {
        val saves = mutableListOf<Pair<String, Long>>()
        val manager = TimeCodeManager(backgroundScope) { id, seconds ->
            saves += id to seconds
            Result.success(Unit)
        }
        manager.setContentId("video")
        manager.onPositionChanged(4_500)
        runCurrent()
        assertTrue(saves.isEmpty())
        manager.putLastPosition()?.join()
        assertEquals(listOf("video" to 4L), saves)
    }

    @Test
    fun failedResultDoesNotSuppressTheNextProgressSave() = runTest {
        val saves = mutableListOf<Long>()
        val manager = TimeCodeManager(backgroundScope) { _, seconds ->
            saves += seconds
            if (saves.size == 1) Result.failure(IllegalStateException("offline"))
            else Result.success(Unit)
        }
        manager.setContentId("video")
        manager.onPositionChanged(10_000)
        runCurrent()
        manager.onPositionChanged(11_000)
        runCurrent()
        assertEquals(listOf(10L, 11L), saves)
    }

    @Test
    fun finalSaveSurvivesComponentCancellationButDoesNotDelayNavigation() = runTest {
        val componentScope = CoroutineScope(coroutineContext + SupervisorJob())
        val saved = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val manager = TimeCodeManager(componentScope) { _, seconds ->
            release.await()
            saved.complete(seconds)
            Result.success(Unit)
        }
        manager.setContentId("video")
        manager.onPositionChanged(5_000)
        val finalSave = manager.putLastPosition()
        componentScope.cancel()
        release.complete(Unit)
        finalSave?.join()
        assertEquals(5L, saved.await())
    }

    @Test
    fun finalSaveTimesOutInsteadOfLeavingAnUnboundedDetachedRequest() = runTest {
        val manager = TimeCodeManager(backgroundScope) { _, _ -> awaitCancellation() }
        manager.setContentId("video")
        manager.onPositionChanged(5_000)
        val finalSave = manager.putLastPosition()!!
        advanceTimeBy(1_501)
        runCurrent()
        assertTrue(finalSave.isCompleted)
    }

    @Test
    fun newContentDoesNotInheritTheOldResumePositionOrSaveThreshold() = runTest {
        val saves = mutableListOf<Pair<String, Long>>()
        val manager = TimeCodeManager(backgroundScope) { id, seconds ->
            saves += id to seconds
            Result.success(Unit)
        }
        manager.setContentId("first")
        manager.onPositionChanged(20_000)
        runCurrent()
        manager.setContentId("second")
        manager.putLastPosition()?.join()
        manager.onPositionChanged(10_000)
        runCurrent()
        assertEquals(listOf("first" to 20L, "second" to 10L), saves)
    }

    @Test
    fun savesAreSerializedAndPendingOlderPositionsCannotOverwriteTheFinalOne() = runTest {
        val release = CompletableDeferred<Unit>()
        val saves = mutableListOf<Long>()
        val manager = TimeCodeManager(backgroundScope) { _, seconds ->
            if (seconds == 10L) release.await()
            saves += seconds
            Result.success(Unit)
        }
        manager.setContentId("video")
        manager.onPositionChanged(10_000)
        runCurrent()
        manager.onPositionChanged(20_000)
        manager.onPositionChanged(25_000)
        val finalSave = manager.putLastPosition()
        release.complete(Unit)
        finalSave?.join()
        runCurrent()
        assertEquals(listOf(10L, 25L), saves)
    }

    @Test
    fun negativeUnknownPositionsDoNotReplaceAValidResumePoint() = runTest {
        val saves = mutableListOf<Long>()
        val manager = TimeCodeManager(backgroundScope) { _, seconds ->
            saves += seconds
            Result.success(Unit)
        }
        manager.setContentId("video")
        manager.onPositionChanged(7_000)
        manager.onPositionChanged(Long.MIN_VALUE + 1)
        manager.putLastPosition()?.join()
        assertEquals(listOf(7L), saves)
    }

    @Test
    fun seekingBackToStartSavesZeroEvenBeforeAnyPeriodicSave() = runTest {
        val saves = mutableListOf<Long>()
        val manager = TimeCodeManager(backgroundScope) { _, seconds ->
            saves += seconds
            Result.success(Unit)
        }
        manager.setContentId("video")
        manager.onPositionChanged(0)
        manager.putLastPosition()?.join()
        assertEquals(listOf(0L), saves)
    }

    @Test
    fun destructionCanFlushAfterTheComponentScopeHasAlreadyBeenCancelled() = runTest {
        val componentScope = CoroutineScope(coroutineContext + SupervisorJob())
        val saves = mutableListOf<Long>()
        val manager = TimeCodeManager(componentScope) { _, seconds ->
            saves += seconds
            Result.success(Unit)
        }
        manager.setContentId("video")
        manager.onPositionChanged(3_000)
        componentScope.cancel()
        manager.putLastPosition()?.join()
        assertEquals(listOf(3L), saves)
    }
}

package ru.slartus.boostbuddy.components.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TvBrowseLogicTest {
    @Test
    fun automaticInitialSidebarFocusPreservesGridRestoration() {
        val initial = TvFocusState()
        val automatic = initial.sidebarFocused()
        assertSame(initial, automatic)
        assertTrue(automatic.canRequestContent(initial.revision))
        assertTrue(automatic.sidebarFocused().canRequestContent(initial.revision))
    }

    @Test
    fun explicitSidebarInputTakesPriorityOverInitialRestoration() {
        val initial = TvFocusState().sidebarFocused()
        val interaction = initial.requestSidebar()
        assertFalse(interaction.canRequestContent(initial.revision))
        assertFalse(interaction.contentRequestPending)
        assertTrue(interaction.sidebarHasFocus)
    }

    @Test
    fun arrivingDataCannotStealFocusAfterTheUserStartsEditing() {
        val loading = TvFocusState().requestContent()
        val request = loading.revision
        assertTrue(loading.canRequestContent(request))

        val editing = loading.contentFocused()
        assertFalse(editing.canRequestContent(request))
        assertFalse(editing.contentRequestPending)
    }

    @Test
    fun returningToContentInvalidatesAnUnfinishedSidebarRequest() {
        val sidebar = TvFocusState().requestSidebar()
        val request = sidebar.revision
        assertTrue(sidebar.canRequestSidebar(request))

        val content = sidebar.requestContent()
        assertFalse(content.canRequestSidebar(request))
        assertTrue(content.canRequestContent(content.revision))
    }

    @Test
    fun movingToSidebarCancelsTheOldGridFocusRequest() {
        val grid = TvFocusState().requestContent()
        val sidebar = grid.sidebarFocused()
        assertFalse(sidebar.canRequestContent(grid.revision))
        assertFalse(sidebar.canRequestContent(sidebar.revision))
        assertTrue(sidebar.sidebarHasFocus)
    }

    @Test
    fun retryCanRequestFocusAgainAfterItsButtonDisappears() {
        val button = TvFocusState().contentFocused()
        assertFalse(button.contentRequestPending)

        val retry = button.requestContent()
        assertTrue(retry.canRequestContent(retry.revision))
        assertFalse(retry.allowHeaderFallback)
        val restored = retry.contentFocused("video")
        assertFalse(restored.canRequestContent(retry.revision))
        assertEquals("video", restored.focusedContentKey)
    }

    @Test
    fun explicitContentEntryCanUseAHeaderDuringLoading() {
        val entry = TvFocusState().requestSidebar().requestContent(allowHeaderFallback = true)
        assertTrue(entry.allowHeaderFallback)
        val header = entry.contentFocused()
        assertFalse(header.contentRequestPending)
        assertFalse(header.allowHeaderFallback)
    }

    @Test
    fun gridRestorationUsesIdentityAfterReordering() {
        assertEquals(2, restoredTvGridIndex(listOf("c", "b", "a"), "a", 0))
    }

    @Test
    fun removedGridItemRestoresTheNearestSurvivingPosition() {
        assertEquals(2, restoredTvGridIndex(listOf("a", "b", "c", "d"), "removed", 2))
        assertEquals(1, restoredTvGridIndex(listOf("a", "b"), "removed", 20))
        assertEquals(0, restoredTvGridIndex(listOf("a", "b"), null, -2))
    }

    @Test
    fun anEmptyGridHasNoFocusTarget() {
        assertNull(restoredTvGridIndex(emptyList(), "removed", 4))
    }

    @Test
    fun returningToTheSameSearchPreservesUnsubmittedText() {
        val draft = TvSearchDraft(query = "old query", text = "unfinished edit")
        assertSame(draft, draft.synchronize("old query"))
        assertEquals("unfinished edit", draft.synchronize("old query").text)
    }

    @Test
    fun aDifferentSubmittedQueryReplacesTheOldDraft() {
        val draft = TvSearchDraft(query = "old query", text = "unfinished edit")
        assertEquals(TvSearchDraft("new query", "new query"), draft.synchronize("new query"))
    }

    @Test
    fun clearingTheSearchProducesAnEmptyQueryInsteadOfKeepingOldResults() {
        assertEquals("", TvSearchDraft("old query", "   ").submittedQuery)
        assertEquals("new query", TvSearchDraft("old query", "  new query  ").submittedQuery)
    }
}

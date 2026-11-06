package ru.slartus.boostbuddy.data.repositories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SubscribesPaginationTest {
    @Test
    fun loadsPastThirtyAndStopsAtServerTotal() {
        val pagination = SubscribesPagination()
        pagination.append(page(offset = 0, total = 65, rows = (0 until 30).map { row(it) }))
        assertEquals(30, pagination.nextOffset)
        pagination.append(page(offset = 30, total = 65, rows = (30 until 60).map { row(it) }))
        assertEquals(60, pagination.nextOffset)
        pagination.append(page(offset = 60, total = 65, rows = (60 until 65).map { row(it) }))
        assertNull(pagination.nextOffset)
        assertEquals(65, pagination.items.size)
        assertEquals("author64", pagination.items.last().blog.blogUrl)
    }

    @Test
    fun advancesByRawRowsAndDeduplicatesAuthorsAcrossPages() {
        val pagination = SubscribesPagination(pageSize = 3)
        pagination.append(page(0, 6, listOf(row(1), SubscribesResponse.Data(id = 2), row(3))))
        assertEquals(3, pagination.nextOffset)
        pagination.append(page(3, 6, listOf(row(4, blogUrl = "author1"), row(5), row(6))))
        assertNull(pagination.nextOffset)
        assertEquals(listOf("author1", "author3", "author5", "author6"), pagination.items.map { it.blog.blogUrl })
    }

    @Test
    fun honorsServerPageSizeWhenTotalIsAbsent() {
        val pagination = SubscribesPagination(pageSize = 30)
        pagination.append(SubscribesResponse(offset = 0, limit = 2, data = listOf(row(1), row(2))))
        assertEquals(2, pagination.nextOffset)
        pagination.append(SubscribesResponse(offset = 2, limit = 2, data = listOf(row(3))))
        assertNull(pagination.nextOffset)
        assertEquals(3, pagination.items.size)
    }

    @Test
    fun continuesShortPagesWhenTotalStillReportsMoreRows() {
        val pagination = SubscribesPagination()
        pagination.append(page(0, 3, listOf(row(1))))
        assertEquals(1, pagination.nextOffset)
        pagination.append(page(1, 3, listOf(row(2), row(3))))
        assertNull(pagination.nextOffset)
    }

    @Test
    fun emptyPageEndsPaginationEvenWhenTotalIsStale() {
        val pagination = SubscribesPagination()
        pagination.append(page(0, 100, emptyList()))
        assertNull(pagination.nextOffset)
        assertEquals(emptyList(), pagination.items)
    }

    @Test
    fun rejectsIgnoredOffsetsAndRepeatedPagesInsteadOfLooping() {
        val pageRows = listOf(row(1), row(2))
        val ignoredOffset = SubscribesPagination(pageSize = 2)
        ignoredOffset.append(page(0, 10, pageRows))
        assertFailsWith<IllegalStateException> { ignoredOffset.append(page(0, 10, pageRows)) }

        val repeatedPage = SubscribesPagination(pageSize = 2)
        repeatedPage.append(page(0, 10, pageRows))
        assertFailsWith<IllegalStateException> { repeatedPage.append(page(2, 10, pageRows)) }
    }

    @Test
    fun boundsRequestsWhenAnUpstreamNeverEndsTheList() {
        val pagination = SubscribesPagination(pageSize = 1, maxPages = 2)
        pagination.append(SubscribesResponse(data = listOf(row(1))))
        assertFailsWith<IllegalStateException> {
            pagination.append(SubscribesResponse(data = listOf(row(2))))
        }
    }

    @Test
    fun malformedResponseIsNotReportedAsAnEmptyAccount() {
        val pagination = SubscribesPagination()
        assertFailsWith<IllegalStateException> { pagination.append(SubscribesResponse()) }
    }

    private fun page(offset: Int, total: Int, rows: List<SubscribesResponse.Data>) =
        SubscribesResponse(offset = offset, total = total, data = rows)

    private fun row(id: Int, blogUrl: String = "author$id") = SubscribesResponse.Data(
        id = id.toLong(),
        blog = SubscribesResponse.Blog(
            title = "Author $id",
            blogUrl = blogUrl,
            owner = SubscribesResponse.Owner(name = "Author $id"),
        ),
    )
}

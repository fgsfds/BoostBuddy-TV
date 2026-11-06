// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.repositories

import io.ktor.client.call.body
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import ru.slartus.boostbuddy.data.api.BoostyApi
import ru.slartus.boostbuddy.utils.fetchOrError

internal class SubscribesRepository(
    private val boostyApi: BoostyApi
) {
    suspend fun getSubscribes(): Result<List<SubscribeItem>> =
        fetchOrError {
            val pagination = SubscribesPagination()
            while (true) {
                val offset = pagination.nextOffset ?: break
                currentCoroutineContext().ensureActive()
                val response: SubscribesResponse = boostyApi.subscribes(
                    limit = pagination.pageSize,
                    withFollow = true,
                    offset = offset,
                ).body()
                currentCoroutineContext().ensureActive()
                pagination.append(response)
            }
            pagination.items
        }

    suspend fun getMyBlog(blogUrl: String): Result<Blog?> =
        fetchOrError {
            val info = boostyApi.blogInfo(blogUrl)
            val title = info.title ?: return@fetchOrError null
            val owner = info.owner ?: return@fetchOrError null
            val ownerName = owner.name ?: return@fetchOrError null

            val posts = boostyApi.blogPosts(
                blog = blogUrl,
                limit = 1,
                offset = null,
                commentsLimit = 0,
                replyLimit = 0,
                isOnlyAllowed = null,
                fromDate = null,
                toDate = null,
                tagsIds = null,
                onlyBought = null,
            )
            if (posts.data.isNullOrEmpty()) return@fetchOrError null

            Blog(
                title = title,
                blogUrl = blogUrl,
                owner = Owner(name = ownerName, avatarUrl = owner.avatarUrl),
            )
        }
}

/**
 * User subscriptions use a numeric start offset and top-level total/limit fields,
 * unlike the opaque extra.offset cursor on post endpoints. Advance by raw rows,
 * including duplicates and rows we cannot display, so later authors are not skipped.
 *
 * Endpoint shape: beekamai/boosty-api, src/resources/user.ts and src/types/account.ts.
 * Offset progression: staners2/boosty-samsung-tv, server/models.mjs.
 */
internal class SubscribesPagination(
    val pageSize: Int = 30,
    private val maxPages: Int = 1_000,
) {
    var nextOffset: Int? = 0
        private set
    val items: List<SubscribeItem> get() = itemsByBlog.values.toList()

    private val itemsByBlog = linkedMapOf<String, SubscribeItem>()
    private val seenPages = mutableSetOf<List<SubscribesResponse.Data>>()
    private var pagesLoaded = 0

    init {
        require(pageSize > 0 && maxPages > 0)
    }

    fun append(response: SubscribesResponse) {
        val requestedOffset = checkNotNull(nextOffset)
        val rows = checkNotNull(response.data) { "Boosty не вернул список подписок. Попробуйте ещё раз." }
        check(response.offset == null || response.offset == requestedOffset) {
            "Boosty вернул другую страницу подписок. Попробуйте загрузить список ещё раз."
        }
        if (rows.isEmpty()) {
            nextOffset = null
            return
        }
        check(seenPages.add(rows)) {
            "Boosty повторил страницу подписок. Попробуйте загрузить список ещё раз."
        }
        rows.mapNotNull { it.toSubscribeItemOrNull() }.forEach { item ->
            itemsByBlog.getOrPut(item.blog.blogUrl) { item }
        }

        val next = requestedOffset.toLong() + rows.size
        check(next <= Int.MAX_VALUE) { "Слишком большой список подписок." }
        val total = response.total?.takeIf { it >= 0 }
        val effectiveLimit = response.limit?.takeIf { it > 0 } ?: pageSize
        val hasMore = if (total != null) next < total else rows.size >= effectiveLimit
        pagesLoaded++
        check(!hasMore || pagesLoaded < maxPages) {
            "Не удалось закончить загрузку подписок. Попробуйте ещё раз."
        }
        nextOffset = if (hasMore) next.toInt() else null
    }
}

private fun SubscribesResponse.Data.toSubscribeItemOrNull(): SubscribeItem? {
    val responseBlog = blog ?: return null
    val blog = Blog(
        title = responseBlog.title ?: return null,
        blogUrl = responseBlog.blogUrl ?: return null,
        owner = responseBlog.owner?.let {
            Owner(
                name = it.name ?: return null,
                avatarUrl = it.avatarUrl
            )
        } ?: return null,
    )
    return SubscribeItem(blog)
}

data class SubscribeItem(
    val blog: Blog
)

@Serializable
data class Blog(
    val title: String,
    val blogUrl: String,
    val owner: Owner,
)

@Serializable
data class Owner(
    val name: String,
    val avatarUrl: String?,
)


@Serializable
internal data class SubscribesResponse(
    val offset: Int? = null,
    val limit: Int? = null,
    val data: List<Data>? = null,
    val total: Int? = null,
) {

    // @Serializable
    // data class Flags(
    //     val hasTargets: Boolean? = null,
    //     val hasSubscriptionLevels: Boolean? = null,
    //     val showPostDonations: Boolean? = null,
    //     val acceptDonationMessages: Boolean? = null,
    //     val allowGoogleIndex: Boolean? = null,
    //     val allowIndex: Boolean? = null,
    //     val isRssFeedEnabled: Boolean? = null
    // )

    @Serializable
    data class Owner(
        val name: String? = null,
        // val hasAvatar: Boolean? = null,
        val avatarUrl: String? = null,
        // val id: Int? = null
    )

    @Serializable
    data class Blog(
        // val flags: Flags? = null,
        val title: String? = null,
        val owner: Owner? = null,
        val blogUrl: String? = null,
        // val coverUrl: String? = null,
        // val hasAdultContent: Boolean? = null
    )

    @Serializable
    data class Data(
        // val isArchived: Boolean? = null,
// val   price: Double? = null,
        // val customPrice: Double? = null,
        // val nextPayTime: Int? = null,
        // val ownerId: Int? = null,
        // val name: String? = null,
        val id: Long? = null,
        // val offTime: String? = null,
        // val period: Int? = null,
        val blog: Blog? = null,
        // val levelId: Int? = null,
        // val onTime: Int? = null
    )
}

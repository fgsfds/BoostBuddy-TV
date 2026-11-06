// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.tv

import androidx.compose.runtime.Immutable
import ru.slartus.boostbuddy.components.feed.FeedPostItem
import ru.slartus.boostbuddy.components.video.usableOptions
import ru.slartus.boostbuddy.data.repositories.models.Content
import ru.slartus.boostbuddy.data.repositories.models.Post

/** A video keeps its parent post so entitlement and playback routing remain intact. */
@Immutable
data class TvVideoCard(
    val post: Post,
    val video: Content.OkVideo,
    val isLocked: Boolean,
) {
    val key: String get() = "${post.user.blogUrl}/${post.id}/${video.id}"
    val title: String get() = video.title.ifBlank { post.title }.ifBlank { "Video" }
    val creatorName: String get() = post.user.name
    val thumbnailUrl: String get() = video.previewUrl
    val durationSeconds: Long get() = video.durationSeconds.coerceAtLeast(0)
    val isPlayable: Boolean
        get() = !isLocked && video.playerUrls.usableOptions().isNotEmpty()
    val progress: Float
        get() = if (durationSeconds > 0) {
            (video.timeCode.toDouble() / durationSeconds).coerceIn(0.0, 1.0).toFloat()
        } else 0f
}

/**
 * Boosty pages contain mixed posts, sometimes several videos per post. Preserve
 * order and stable identities across overlapping pages. A teaser never grants
 * access to the full post, even if it contains a playable preview URL.
 */
fun videoCardsFromPosts(items: List<FeedPostItem>): List<TvVideoCard> = buildList {
    val seen = mutableSetOf<String>()
    items.filterIsInstance<FeedPostItem.PostItem>().forEach { item ->
        val post = item.post
        val videos = if (post.hasAccess) {
            post.data.filterIsInstance<Content.OkVideo>()
        } else {
            (post.data + post.teaser).filterIsInstance<Content.OkVideo>()
        }
        videos.forEach { video ->
            val card = TvVideoCard(post, video, isLocked = !post.hasAccess)
            if (seen.add(card.key)) add(card)
        }
    }
}

fun formatVideoDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$remainder"
    else "$minutes:$remainder"
}

// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.video

import ru.slartus.boostbuddy.data.repositories.models.PlayerUrl
import ru.slartus.boostbuddy.data.repositories.models.VideoQuality
import ru.slartus.boostbuddy.data.repositories.models.Content
import ru.slartus.boostbuddy.data.repositories.models.LiveStream
import ru.slartus.boostbuddy.data.repositories.models.Post

internal data class ResolvedPlayback(
    val video: Content.OkVideo,
    val playerUrl: PlayerUrl,
    val title: String,
)

/** Never reuse a feed URL after the current post explicitly denies access. */
internal fun Post.resolvePlayback(videoId: String, preferred: VideoQuality?): ResolvedPlayback? {
    if (!hasAccess) return null
    val video = data.filterIsInstance<Content.OkVideo>().find { it.id == videoId } ?: return null
    return ResolvedPlayback(video, video.playerUrls.pickPlayerUrl(preferred) ?: return null, title)
}

/** A recovery must not silently move the viewer into the creator's next stream. */
internal fun LiveStream.resolvePlayback(streamId: String, preferred: VideoQuality?): ResolvedPlayback? {
    if (!hasAccess || id != streamId || status !is LiveStream.Status.Live) return null
    val video = video ?: return null
    return ResolvedPlayback(video, video.playerUrls.pickPlayerUrl(preferred) ?: return null, title)
}

// HLS/DASH идут раньше любого Q*P: адаптивные стримы дают лучший опыт по умолчанию,
// чем фиксированный битрейт.
private val VIDEO_QUALITY_FALLBACK_PRIORITY: List<VideoQuality> = listOf(
    VideoQuality.HLS,
    VideoQuality.DASH,
    VideoQuality.Q4320P,
    VideoQuality.Q2160P,
    VideoQuality.Q1440P,
    VideoQuality.Q1080P,
    VideoQuality.Q720P,
    VideoQuality.Q480P,
    VideoQuality.Q360P,
    VideoQuality.Q240P,
    VideoQuality.Q144P,
)

internal fun List<PlayerUrl>.usableOptions(): List<PlayerUrl> =
    filter { it.url.isNotBlank() && it.quality.used }

// HLS/DASH — манифесты адаптивных стримов (списки сегментов), а не цельный медиафайл,
// поэтому DownloadManager их не скачает в осмысленном виде.
internal fun List<PlayerUrl>.downloadableOptions(): List<PlayerUrl> =
    usableOptions().filter { it.quality != VideoQuality.HLS && it.quality != VideoQuality.DASH }

internal fun List<PlayerUrl>.pickPlayerUrl(preferred: VideoQuality?): PlayerUrl? {
    val available = usableOptions()
    if (available.isEmpty()) return null

    if (preferred != null) {
        available.firstOrNull { it.quality == preferred }?.let { return it }
    }
    for (quality in VIDEO_QUALITY_FALLBACK_PRIORITY) {
        available.firstOrNull { it.quality == quality }?.let { return it }
    }
    return available.firstOrNull()
}

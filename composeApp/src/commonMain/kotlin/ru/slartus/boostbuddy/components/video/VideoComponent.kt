// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.video

import androidx.compose.runtime.Stable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.slartus.boostbuddy.components.BaseComponent
import ru.slartus.boostbuddy.components.video.VideoState.Buffering
import ru.slartus.boostbuddy.components.video.VideoState.Ended
import ru.slartus.boostbuddy.components.video.VideoState.Error
import ru.slartus.boostbuddy.components.video.VideoState.Idle
import ru.slartus.boostbuddy.components.video.VideoState.Ready
import ru.slartus.boostbuddy.components.blog.text
import ru.slartus.boostbuddy.data.Inject
import ru.slartus.boostbuddy.data.repositories.PostRepository
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import ru.slartus.boostbuddy.data.repositories.StreamRepository
import ru.slartus.boostbuddy.data.repositories.VideoRepository
import ru.slartus.boostbuddy.data.repositories.models.Content
import ru.slartus.boostbuddy.data.repositories.models.PlayerUrl
import ru.slartus.boostbuddy.utils.PlatformConfiguration

@Stable
interface VideoComponent {
    val viewStates: Value<VideoViewState>
    fun onVideoStateChanged(videoState: VideoState)
    fun onContentPositionChange(position: Long)
    fun onStopClicked()
    fun onSettingsClicked()
    fun onSettingsSheetDismissed()
    fun onQualityItemClicked(playerUrl: PlayerUrl)
    fun onPlaybackSpeedSelected(speed: Float)
    fun onDownloadQualitySelected(playerUrl: PlayerUrl)
    fun onRetryClicked()
}

data class VideoViewState(
    val postData: Content.OkVideo?,
    val postTitle: String? = null,
    val playerUrl: PlayerUrl,
    val loading: Boolean = true,
    val settingsSheetVisible: Boolean = false,
    val playbackSpeed: Float = 1f,
    val isLive: Boolean = false,
    val liveStartedAtSeconds: Long? = null,
    val streamEnded: Boolean = false,
    val playbackError: Boolean = false,
    val retryToken: Int = 0,
)

val Content.OkVideo.timeCodeMs: Long get() = timeCode.coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L

enum class VideoState {
    Idle, Buffering, Ready, Ended, Error
}

internal class VideoComponentImpl(
    componentContext: ComponentContext,
    private val blogUrl: String,
    private val postId: String,
    postData: Content.OkVideo,
    playerUrl: PlayerUrl,
    private val liveBlogUrl: String? = null,
    liveStartedAtSeconds: Long? = null,
    private val onStopClicked: () -> Unit
) : BaseComponent<VideoViewState, Any>(
    componentContext,
    VideoViewState(
        postData = null,
        playerUrl = playerUrl,
        isLive = liveBlogUrl != null,
        liveStartedAtSeconds = liveStartedAtSeconds,
    )
), VideoComponent {

    private val videoRepository by Inject.lazy<VideoRepository>()
    private val postRepository by Inject.lazy<PostRepository>()
    private val streamRepository by Inject.lazy<StreamRepository>()
    private val settingsRepository by Inject.lazy<SettingsRepository>()
    private val platformConfiguration by Inject.lazy<PlatformConfiguration>()
    private val playbackSessionId = settingsRepository.sessionSnapshot()
        .takeIf { it.isAuthenticated }?.sessionId
    private val videoId = postData.id
    private val timeCodeManager = TimeCodeManager(
        scope = scope,
        saveTimeCode = { id, seconds ->
            if (isPlaybackSessionActive()) videoRepository.putTimeCode(id, seconds)
            else Result.failure(IllegalStateException("Playback session ended"))
        },
    )
    private val liveStreamModel: LiveStreamComponentModel? = liveBlogUrl?.let { url ->
        LiveStreamComponentModel(
            scope = scope,
            streamRepository = streamRepository,
            blogUrl = url,
            isSessionActive = ::isPlaybackSessionActive,
        )
    }
    private var bufferingDebounceJob: Job? = null
    private var autoRecoveryAttempted = false
    private var recoverJob: Job? = null
    private var playbackGeneration = 0L
    private var stopping = false
    private var lastPlaybackPosition: Long? = null

    init {
        lifecycle.doOnStart {
            if (viewState.postData != null && !viewState.streamEnded && !stopping) {
                liveStreamModel?.startHeartbeat()
            }
        }
        lifecycle.doOnStop {
            liveStreamModel?.stopHeartbeat()
            if (liveStreamModel == null) timeCodeManager.putLastPosition()
        }
        lifecycle.doOnDestroy {
            stopping = true
            if (liveStreamModel != null) liveStreamModel.stopHeartbeat()
            else timeCodeManager.putLastPosition()
        }
        scope.launch {
            settingsRepository.sessionFlow
                .map { it.sessionId to it.isAuthenticated }
                .distinctUntilChanged()
                .collect {
                    if (!isPlaybackSessionActive()) {
                        playbackGeneration++
                        recoverJob?.cancel()
                        cancelLoadingDebounce()
                        liveStreamModel?.stopHeartbeat()
                        viewState = viewState.copy(postData = null, loading = false, playbackError = true)
                    }
                }
        }
        refreshPlayback()
        subscribePlaybackSpeed()
    }

    private fun isPlaybackSessionActive(): Boolean {
        val session = settingsRepository.sessionSnapshot()
        return playbackSessionId != null && session.isAuthenticated && session.sessionId == playbackSessionId
    }

    private fun subscribePlaybackSpeed() {
        scope.launch {
            settingsRepository.appSettingsFlow
                .map { it.preferredPlaybackSpeed }
                .distinctUntilChanged()
                .collect { speed ->
                    // Live playback is always 1x, without erasing the saved VOD preference.
                    viewState = viewState.copy(playbackSpeed = if (liveStreamModel != null) 1f else speed)
                }
        }
    }

    override fun onVideoStateChanged(videoState: VideoState) {
        if (stopping || !isPlaybackSessionActive() || viewState.postData == null) return
        when (videoState) {
            Idle -> {
                cancelLoadingDebounce()
                if (!viewState.playbackError) viewState = viewState.copy(loading = true)
            }

            Buffering -> showBufferingLoadingDebounced()
            Ready -> {
                cancelLoadingDebounce()
                autoRecoveryAttempted = false
                viewState = viewState.copy(loading = false, playbackError = false)
            }

            Ended -> {
                cancelLoadingDebounce()
                handlePlaybackEnded()
            }
            Error -> {
                cancelLoadingDebounce()
                if (autoRecoveryAttempted) {
                    viewState = viewState.copy(playbackError = true, loading = false)
                } else {
                    viewState = viewState.copy(loading = true)
                    recoverPlayback()
                }
            }
        }
    }

    // Тонкий буфер у HLS live даёт частые короткие BUFFERING→READY циклы между
    // сегментами — без дебаунса лоадер мигает на каждом сегменте.
    private fun showBufferingLoadingDebounced() {
        if (viewState.loading) return
        bufferingDebounceJob?.cancel()
        bufferingDebounceJob = scope.launch {
            delay(LOADING_DEBOUNCE_MS)
            viewState = viewState.copy(loading = true)
        }
    }

    private fun cancelLoadingDebounce() {
        bufferingDebounceJob?.cancel()
        bufferingDebounceJob = null
    }

    override fun onRetryClicked() {
        viewState = viewState.copy(playbackError = false, loading = true)
        recoverPlayback()
    }

    private fun recoverPlayback() {
        autoRecoveryAttempted = true
        refreshPlayback()
    }

    private fun refreshPlayback() {
        if (stopping || !isPlaybackSessionActive()) {
            viewState = viewState.copy(postData = null, loading = false, playbackError = true)
            return
        }
        val generation = ++playbackGeneration
        recoverJob?.cancel()
        recoverJob = scope.launch {
            val fresh = fetchFreshPlayback()
            coroutineContext.ensureActive()
            if (generation != playbackGeneration || !isPlaybackSessionActive() || stopping) return@launch
            viewState = if (fresh != null) {
                timeCodeManager.setContentId(fresh.video.id)
                if (lifecycle.state == Lifecycle.State.STARTED || lifecycle.state == Lifecycle.State.RESUMED) {
                    liveStreamModel?.startHeartbeat()
                }
                val video = if (liveStreamModel == null && lastPlaybackPosition != null) {
                    fresh.video.copy(timeCode = lastPlaybackPosition!! / 1000L)
                } else fresh.video
                viewState.copy(
                    postData = video,
                    postTitle = fresh.title,
                    playerUrl = fresh.playerUrl,
                    playbackError = false,
                    loading = true,
                    retryToken = viewState.retryToken + 1,
                )
            } else {
                liveStreamModel?.stopHeartbeat()
                viewState.copy(postData = null, playbackError = true, loading = false)
            }
        }
    }

    private suspend fun fetchFreshPlayback(): ResolvedPlayback? {
        // Read preferred quality after the request: the user may change it while
        // a signed URL is being renewed.
        return if (liveBlogUrl != null) {
            streamRepository.fetchActive(liveBlogUrl).getOrNull()
                ?.resolvePlayback(postId, viewState.playerUrl.quality)
        } else {
            postRepository.getPost(blogUrl, postId).getOrNull()
                ?.resolvePlayback(videoId, viewState.playerUrl.quality)
        }
    }

    private fun handlePlaybackEnded() {
        viewState = viewState.copy(loading = false)
        val model = liveStreamModel ?: run {
            timeCodeManager.putLastPosition()
            return
        }
        if (viewState.streamEnded) return
        viewState = viewState.copy(streamEnded = true, loading = false)
        model.stopHeartbeat()
    }

    override fun onContentPositionChange(position: Long) {
        if (liveStreamModel != null || stopping || !isPlaybackSessionActive() || position < 0) return
        lastPlaybackPosition = position
        timeCodeManager.onPositionChanged(position)
    }

    override fun onStopClicked() {
        if (stopping) return
        stopping = true
        playbackGeneration++
        recoverJob?.cancel()
        cancelLoadingDebounce()
        if (liveStreamModel != null) {
            liveStreamModel.stopHeartbeat()
        } else {
            timeCodeManager.putLastPosition()
        }
        onStopClicked.invoke()
    }

    override fun onSettingsClicked() {
        viewState = viewState.copy(settingsSheetVisible = true)
    }

    override fun onSettingsSheetDismissed() {
        viewState = viewState.copy(settingsSheetVisible = false)
    }

    override fun onQualityItemClicked(playerUrl: PlayerUrl) {
        if (playerUrl !in viewState.postData?.playerUrls.orEmpty().usableOptions()) return
        scope.launch {
            settingsRepository.setPreferredQuality(playerUrl.quality)
        }
        viewState = viewState.copy(
            playerUrl = playerUrl,
            settingsSheetVisible = false,
        )
    }

    override fun onPlaybackSpeedSelected(speed: Float) {
        if (liveStreamModel != null || !speed.isFinite() || speed !in 0.5f..3f) return
        // Запись в Settings — единственная точка истины. State обновится через
        // subscribePlaybackSpeed-коллектор, иначе будут два writer'а на одно поле.
        scope.launch {
            settingsRepository.setPreferredPlaybackSpeed(speed)
        }
    }

    override fun onDownloadQualitySelected(playerUrl: PlayerUrl) {
        if (liveStreamModel != null ||
            playerUrl !in viewState.postData?.playerUrls.orEmpty().downloadableOptions()) return
        val title = viewState.postData?.title.orEmpty()
        val fileName = buildDownloadFileName(title, playerUrl)
        platformConfiguration.downloadVideo(
            url = playerUrl.url,
            fileName = fileName,
            onError = null,
        )
        viewState = viewState.copy(settingsSheetVisible = false)
    }

    private fun buildDownloadFileName(title: String, playerUrl: PlayerUrl): String {
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .ifEmpty { "video" }
            .take(80)
        return "${safeTitle}_${playerUrl.quality.text}.mp4"
    }

    private companion object {
        private const val LOADING_DEBOUNCE_MS = 500L
    }
}

// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.ui.widgets

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ru.slartus.boostbuddy.data.ktor.USER_AGENT
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ru.slartus.boostbuddy.components.video.PlaybackCheckpoint
import ru.slartus.boostbuddy.components.video.PlaybackLifecycleController
import ru.slartus.boostbuddy.components.video.VideoState
import ru.slartus.boostbuddy.data.repositories.models.PlayerUrl
import ru.slartus.boostbuddy.data.repositories.models.VideoQuality

private const val SEEK_UPDATE_INTERVAL_MS = 1000L

private data class PlaybackInput(
    val playerUrl: PlayerUrl,
    val title: String,
    val playbackSpeed: Float,
    val isLive: Boolean,
    val retryToken: Int,
)

private class OwnedPlayer(
    val player: ExoPlayer,
    val listener: Player.Listener,
    var playerUrl: PlayerUrl,
    var retryToken: Int,
)

@Composable
actual fun VideoPlayer(
    vid: String,
    playerUrl: PlayerUrl,
    title: String,
    postTitle: String,
    position: Long,
    playbackSpeed: Float,
    isLive: Boolean,
    liveStartedAtSeconds: Long?,
    retryToken: Int,
    onVideoStateChange: (VideoState) -> Unit,
    onContentPositionChange: (Long) -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: (() -> Unit)?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var playingPosition by remember(vid) { mutableLongStateOf(position.coerceAtLeast(0L)) }
    var isEnded by remember(vid) { mutableStateOf(false) }
    var currentPlayer by remember(context, vid) { mutableStateOf<OwnedPlayer?>(null) }
    val input by rememberUpdatedState(PlaybackInput(playerUrl, title, playbackSpeed, isLive, retryToken))
    val reportPosition by rememberUpdatedState<(Long) -> Unit> { value ->
        playingPosition = value
        onContentPositionChange(value)
    }
    val reportState by rememberUpdatedState<(VideoState) -> Unit> { state ->
        isEnded = state == VideoState.Ended
        onVideoStateChange(state)
    }
    val controller = remember(context, vid) {
        PlaybackLifecycleController(
            initialCheckpoint = PlaybackCheckpoint(position.coerceAtLeast(0L), true),
            create = { checkpoint ->
                createPlayer(context, vid, input, checkpoint,
                    onVideoStateChange = { reportState(it) },
                    onContentPositionChange = { reportPosition(it) })
            },
            snapshot = { owned ->
                PlaybackCheckpoint(
                    owned.player.contentPosition.coerceAtLeast(0L),
                    owned.player.playWhenReady,
                ).also { reportPosition(it.position) }
            },
            release = { owned ->
                owned.player.removeListener(owned.listener)
                owned.player.release()
            },
            onChanged = { currentPlayer = it },
        )
    }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.start()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    // The observer releases synchronously; this branch only controls rendering.
    // Android may pause recomposition below STARTED, so it never owns cleanup.
    val owned = currentPlayer ?: return
    val exoPlayer = owned.player
    LaunchedEffect(owned) {
        while (isActive && controller.current === owned) {
            reportPosition(exoPlayer.contentPosition.coerceAtLeast(0L))
            delay(SEEK_UPDATE_INTERVAL_MS)
        }
    }
    LaunchedEffect(owned, playerUrl, retryToken) {
        if (controller.current !== owned ||
            (playerUrl == owned.playerUrl && retryToken == owned.retryToken)) return@LaunchedEffect
        owned.playerUrl = playerUrl
        owned.retryToken = retryToken
        val savedPosition = exoPlayer.contentPosition.coerceAtLeast(0L)
        val wasPlaying = exoPlayer.playWhenReady
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaSource(mediaId = vid, title = title, playerUrl = playerUrl)
        if (isLive) exoPlayer.seekToDefaultPosition() else exoPlayer.seekTo(savedPosition)
        exoPlayer.playWhenReady = wasPlaying
        exoPlayer.prepare()
    }
    LaunchedEffect(owned, playbackSpeed) {
        if (controller.current === owned) exoPlayer.setPlaybackSpeed(playbackSpeed)
    }
    val playbackMode = remember(isLive, liveStartedAtSeconds) {
        if (isLive) PlaybackMode.Live(liveStartedAtSeconds) else PlaybackMode.Vod
    }
    VideoPlayerChrome(
        exoPlayer = exoPlayer,
        isPlayerActive = { controller.current === owned },
        title = title,
        postTitle = postTitle,
        playingPosition = playingPosition,
        isEnded = isEnded,
        playbackMode = playbackMode,
        onStopClick = {
            if (controller.current === owned) reportPosition(exoPlayer.contentPosition.coerceAtLeast(0L))
            onStopClick()
        },
        onSettingsClick = onSettingsClick,
    )
}

@OptIn(UnstableApi::class)
private fun ExoPlayer.setMediaSource(mediaId: String, title: String, playerUrl: PlayerUrl) {
    val mediaItem = MediaItem.Builder()
        .setUri(playerUrl.url)
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(title).build())
        .build()
    when (playerUrl.quality) {
        VideoQuality.HLS -> {
            val dataSourceFactory: DataSource.Factory = mediaHttpFactory()
            val hlsMediaSource = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            setMediaSource(hlsMediaSource)
        }

        VideoQuality.DASH -> {
            val dataSourceFactory: DataSource.Factory = mediaHttpFactory()
            val dashMediaSource = DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            setMediaSource(dashMediaSource)
        }

        else -> setMediaItem(mediaItem)
    }
}

@OptIn(UnstableApi::class)
private fun createPlayer(
    context: Context,
    vid: String,
    input: PlaybackInput,
    checkpoint: PlaybackCheckpoint,
    onVideoStateChange: (VideoState) -> Unit,
    onContentPositionChange: (Long) -> Unit,
): OwnedPlayer {
    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(mediaHttpFactory()))
        .build()
    val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            onContentPositionChange(player.contentPosition)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                ExoPlayer.STATE_IDLE -> onVideoStateChange(VideoState.Idle)
                ExoPlayer.STATE_BUFFERING -> onVideoStateChange(VideoState.Buffering)
                ExoPlayer.STATE_READY -> onVideoStateChange(VideoState.Ready)
                ExoPlayer.STATE_ENDED -> {
                    onContentPositionChange(player.contentPosition)
                    onVideoStateChange(VideoState.Ended)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            onVideoStateChange(VideoState.Error)
        }
    }
    try {
        player.addListener(listener)
        player.setMediaSource(vid, input.title, input.playerUrl)
        if (input.isLive) player.seekToDefaultPosition() else player.seekTo(checkpoint.position)
        player.playWhenReady = checkpoint.playWhenReady
        player.setPlaybackSpeed(input.playbackSpeed)
        player.prepare()
        return OwnedPlayer(player, listener, input.playerUrl, input.retryToken)
    } catch (failure: Throwable) {
        player.removeListener(listener)
        player.release()
        throw failure
    }
}

/** Match metadata requests when resolving signed CDN URLs; never attach API tokens. */
@OptIn(UnstableApi::class)
private fun mediaHttpFactory(): DataSource.Factory =
    DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)

internal fun ExoPlayer.startPlayer() {
    if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
    playWhenReady = true
    play()
}

internal fun ExoPlayer.pausePlayer() {
    playWhenReady = false
    pause()
}

internal fun ExoPlayer.seekToClamped(position: Long) {
    val duration = contentDuration
    val target = if (duration > 0) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
    seekTo(target)
}

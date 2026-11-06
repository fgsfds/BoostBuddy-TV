package ru.slartus.boostbuddy.ui.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.sin
import kotlinx.collections.immutable.toImmutableList
import ru.slartus.boostbuddy.components.common.ProgressState
import ru.slartus.boostbuddy.components.feed.FeedPostItem
import ru.slartus.boostbuddy.components.subscribes.SubscribesViewState
import ru.slartus.boostbuddy.components.tv.videoCardsFromPosts
import ru.slartus.boostbuddy.data.Inject
import ru.slartus.boostbuddy.data.repositories.Blog
import ru.slartus.boostbuddy.data.repositories.Owner
import ru.slartus.boostbuddy.data.repositories.SubscribeItem
import ru.slartus.boostbuddy.data.repositories.models.Content
import ru.slartus.boostbuddy.data.repositories.models.PlayerUrl
import ru.slartus.boostbuddy.data.repositories.models.Post
import ru.slartus.boostbuddy.data.repositories.models.PostCount
import ru.slartus.boostbuddy.data.repositories.models.User
import ru.slartus.boostbuddy.data.repositories.models.VideoQuality
import ru.slartus.boostbuddy.ui.common.LocalPlatformConfiguration
import ru.slartus.boostbuddy.ui.theme.AppTheme
import ru.slartus.boostbuddy.utils.PlatformConfiguration

/**
 * Debug-only screenshot entry for the real TV composables, using entirely local fixtures.
 *
 * No RootComponent, repositories, account, authentication or video requests are created here.
 * This is not a second app launcher. Start explicitly with ADB and --es screen feed,
 * subscriptions, creator or search. The normal TvAppActivity remains unchanged.
 */
class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixtures = createScreenshotFixtures(File(cacheDir, "screenshot-fixtures"))
        val initialScreen = intent.getStringExtra("screen") ?: "feed"
        setContent {
            CompositionLocalProvider(
                LocalPlatformConfiguration provides Inject.instance<PlatformConfiguration>(),
            ) {
                AppTheme(desireDarkMode = true) {
                    ScreenshotContent(fixtures, initialScreen, onClose = ::finish)
                }
            }
        }
    }
}

@Composable
private fun ScreenshotContent(fixtures: ScreenshotFixtures, initialScreen: String, onClose: () -> Unit) {
    val browse = rememberTvBrowseState()
    var creator by remember { mutableStateOf(fixtures.creators.firstOrNull().takeIf { initialScreen == "creator" }) }
    var query by remember { mutableStateOf(if (initialScreen == "search") "свет" else "") }
    remember {
        browse.section = when (initialScreen) {
            "subscriptions" -> TvSection.Subscriptions
            "search" -> TvSection.Search
            else -> TvSection.Feed
        }
        browse.sidebarFocusKey = when (browse.section) {
            TvSection.Subscriptions -> "subscriptions"
            TvSection.Search -> "search"
            TvSection.Feed -> creator?.let { "creator:${it.blogUrl}" } ?: "feed"
        }
        true
    }
    val subscriptions = remember(fixtures) {
        SubscribesViewState.ProgressState.Loaded(
            fixtures.creators.map(::SubscribeItem).toImmutableList(), myBlog = null,
        )
    }
    val items = remember(creator, query, browse.section) {
        fixtures.items.filter { item ->
            (creator == null || item.post.user.blogUrl == creator?.blogUrl) &&
                (browse.section != TvSection.Search || item.post.title.contains(query, ignoreCase = true))
        }
    }
    val cards = remember(items) { videoCardsFromPosts(items) }
    Box(Modifier.fillMaxSize()) {
        TvBrowseScreen(
            browseState = browse,
            subscriptions = subscriptions,
            selectedCreator = creator?.blogUrl,
            onSectionSelected = { creator = null; browse.section = it },
            onCreatorSelected = { creator = it; browse.section = TvSection.Feed },
            onSettings = {},
            onLogout = onClose,
            onRefreshSubscriptions = {},
        ) { focus ->
            if (browse.section == TvSection.Subscriptions && creator == null) {
                TvCreatorDirectory(subscriptions, browse, focus, onCreatorSelected = { creator = it; browse.section = TvSection.Feed }, onRefresh = {})
            } else {
                TvVideoBrowseContent(
                    routeKey = "screenshot:${creator?.blogUrl}:${browse.section}:$query",
                    title = creator?.owner?.name ?: if (browse.section == TvSection.Search) "Поиск" else "Лента",
                    subtitle = creator?.title ?: "Последние видео из ваших подписок",
                    avatarUrl = creator?.owner?.avatarUrl,
                    cards = cards,
                    items = items,
                    progress = ProgressState.Loaded,
                    isRefreshing = false,
                    hasMore = false,
                    pageCursor = null,
                    browseState = browse,
                    focus = focus,
                    searchVisible = browse.section == TvSection.Search,
                    searchQuery = query,
                    onSearch = { query = it },
                    onRefresh = {},
                    onLoadMore = {},
                    onRetry = {},
                    onRetryPage = {},
                    onPlay = {}, // Deliberately offline: fixture cards never start a player or a request.
                )
            }
        }
        Text(
            "DEMO DATA · OFFLINE",
            color = Color(0xFFE6BC92),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 74.dp, end = 26.dp)
                .background(Color(0xF0191A21), RoundedCornerShape(5.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private data class ScreenshotFixtures(val creators: List<Blog>, val items: List<FeedPostItem.PostItem>)

private fun createScreenshotFixtures(directory: File): ScreenshotFixtures {
    directory.mkdirs()
    val names = listOf("Кадр и свет", "Геймпад", "Звукозапись", "Архив кино", "Маршруты", "Мастерская дизайна")
    val descriptions = listOf("Фотография и работа со светом", "Как устроены любимые игры", "Музыка, запись и сведение", "Разборы и язык кино", "Путешествия за город", "Дизайн понятных интерфейсов")
    val titles = listOf(
        "Как свет меняет кадр", "Геймдизайн: почему это работает", "Домашняя студия без лишнего шума",
        "Киноязык: цвет и композиция", "Один день в горах", "Разбираем интерфейс по слоям",
        "Свет на закате: практика", "Большой разбор игровых механик", "Записываем голос дома",
    )
    val durations = listOf(1368L, 2172L, 1115L, 2466L, 1669L, 1880L, 1460L, 3185L, 1290L)
    val creators = names.mapIndexed { index, name ->
        val avatar = File(directory, "avatar-$index.png")
        writeDemoArtwork(avatar, index, avatar = true)
        Blog(descriptions[index], "demo-creator-$index", Owner(name, avatar.toURI().toString()))
    }
    val posts = titles.mapIndexed { index, title ->
        val author = creators[index % creators.size]
        val thumbnail = File(directory, "video-$index.png")
        writeDemoArtwork(thumbnail, index % creators.size, avatar = false)
        val video = Content.OkVideo(
            id = "demo-video-$index", vid = "demo-video-$index", title = title,
            playerUrls = listOf(PlayerUrl(VideoQuality.HLS, "demo://offline/$index")),
            previewUrl = thumbnail.toURI().toString(),
            timeCode = if (index == 0) 430 else 0,
            durationSeconds = durations[index],
        )
        FeedPostItem.PostItem(Post(
            id = "demo-post-$index", intId = index.toLong(), signedQuery = "", title = title,
            createdAt = 1772960400L - index * 86400L,
            data = listOf(video), teaser = emptyList(),
            user = User(author.owner.name, author.owner.avatarUrl, author.blogUrl),
            count = PostCount(0, 0), poll = null, hasAccess = true,
        ))
    }
    return ScreenshotFixtures(creators, posts)
}

/** Original geometric fixture artwork, generated locally; no downloaded images or creator assets. */
private fun writeDemoArtwork(file: File, index: Int, avatar: Boolean) {
    val colors = listOf(
        0xFF164256.toInt() to 0xFF6DA9B7.toInt(), 0xFF28205D.toInt() to 0xFF806BC6.toInt(),
        0xFF45263D.toInt() to 0xFFB86A7C.toInt(), 0xFF614527.toInt() to 0xFFCCA965.toInt(),
        0xFF174846.toInt() to 0xFF71ADA2.toInt(), 0xFF28365B.toInt() to 0xFF739AC5.toInt(),
    )
    val size = if (avatar) 240 else 640
    val height = if (avatar) 240 else 360
    val bitmap = Bitmap.createBitmap(size, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val (dark, light) = colors[index]
    paint.shader = LinearGradient(0f, height.toFloat(), size.toFloat(), 0f, dark, light, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, size.toFloat(), height.toFloat(), paint)
    paint.shader = null
    paint.color = 0x24FFFFFF
    canvas.drawCircle(size * .83f, height * .1f, size * .43f, paint)
    if (avatar) {
        paint.color = 0xFFFFE8CC.toInt()
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 112f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(listOf("К", "Г", "З", "А", "М", "Д")[index], 120f, 160f, paint)
    } else {
        paint.color = 0xDDFBE6CC.toInt()
        when (index) {
            0, 3 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 12f
                canvas.drawCircle(460f, 155f, 80f, paint)
                canvas.drawCircle(460f, 155f, 42f, paint)
                paint.strokeWidth = 3f
                canvas.drawRect(355f, 50f, 565f, 260f, paint)
            }
            1, 5 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 9f
                canvas.drawRoundRect(338f, 62f, 574f, 238f, 28f, 28f, paint)
                canvas.drawLine(368f, 126f, 428f, 126f, paint)
                canvas.drawLine(398f, 96f, 398f, 156f, paint)
                canvas.drawCircle(505f, 120f, 13f, paint)
                canvas.drawCircle(541f, 157f, 13f, paint)
            }
            2 -> {
                paint.strokeWidth = 7f
                for (x in 0..31) {
                    val amplitude = 15f + 65f * kotlin.math.abs(sin(x * .62f))
                    val px = 345f + x * 7f
                    canvas.drawLine(px, 153f - amplitude, px, 153f + amplitude, paint)
                }
            }
            4 -> {
                val path = Path().apply {
                    moveTo(300f, 250f); lineTo(405f, 80f); lineTo(470f, 179f)
                    lineTo(520f, 115f); lineTo(615f, 250f); close()
                }
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFFEFF6F6.toInt()
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 38f
        canvas.drawText(listOf("СВЕТ И КАДР", "МИР ИГР", "ЧИСТЫЙ ЗВУК", "ЯЗЫК КИНО", "НОВЫЙ МАРШРУТ", "ДЕТАЛИ ДИЗАЙНА")[index], 32f, 291f, paint)
        paint.textSize = 20f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.color = 0xC9FFFFFF.toInt()
        canvas.drawText("BOOSTBUDDY TV  /  DEMO", 34f, 326f, paint)
    }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}

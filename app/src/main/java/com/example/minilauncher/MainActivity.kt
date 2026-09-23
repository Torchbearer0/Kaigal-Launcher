@file:OptIn(ExperimentalFoundationApi::class)

package com.example.minilauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- data

data class AppEntry(val label: String, val component: ComponentName) {
    val key: String get() = component.flattenToString()
}

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(query, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map {
            AppEntry(
                label = it.loadLabel(pm).toString(),
                component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun launch(ctx: Context, app: AppEntry) {
    val intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(app.component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    runCatching { ctx.startActivity(intent) }
}

/** Favorites are stored as an ordered, newline-separated list of component names. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun favorites(): List<String> =
        sp.getString("favs", "")!!.split("\n").filter { it.isNotBlank() }

    fun saveFavorites(list: List<String>) =
        sp.edit().putString("favs", list.joinToString("\n")).apply()
}

// ---------------------------------------------------------------- activity

class MainActivity : ComponentActivity() {
    private var homeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LauncherApp(homeTick) }
    }

    // Pressing the Home button while we're already open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeTick++
    }
}

// ---------------------------------------------------------------- UI

private fun glass(size: TextUnit, weight: FontWeight = FontWeight.Light) = TextStyle(
    color = Color.White,
    fontSize = size,
    fontWeight = weight,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f)
)

@Composable
fun LauncherApp(homeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var apps by remember { mutableStateOf(loadApps(ctx)) }
    var favKeys by remember { mutableStateOf(prefs.favorites()) }
    var showAll by remember { mutableStateOf(false) }

    // Refresh the list when apps are installed or removed.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { apps = loadApps(c) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    LaunchedEffect(homeTick) { showAll = false }
    BackHandler(enabled = showAll) { showAll = false }

    fun toggleFavorite(app: AppEntry) {
        favKeys = if (app.key in favKeys) favKeys - app.key else favKeys + app.key
        prefs.saveFavorites(favKeys)
    }

    val byKey = remember(apps) { apps.associateBy { it.key } }
    val favApps = favKeys.mapNotNull { byKey[it] }

    if (showAll) {
        AllAppsScreen(
            apps = apps,
            favKeys = favKeys.toSet(),
            onOpen = { launch(ctx, it) },
            onToggleFavorite = ::toggleFavorite
        )
    } else {
        HomeScreen(
            favApps = favApps,
            onOpen = { launch(ctx, it) },
            onRemove = ::toggleFavorite,
            onOpenAll = { showAll = true }
        )
    }
}

@Composable
fun HomeScreen(
    favApps: List<AppEntry>,
    onOpen: (AppEntry) -> Unit,
    onRemove: (AppEntry) -> Unit,
    onOpenAll: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // swipe up anywhere to open the app list
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -120f) onOpenAll() }
                ) { _, dy -> total += dy }
            }
    ) {
        Clock(Modifier.align(Alignment.TopStart).padding(28.dp))

        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 28.dp, end = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (favApps.isEmpty()) {
                Text("Swipe up, then long-press an app to pin it here.", style = glass(18.sp))
            }
            favApps.forEach { app ->
                Text(
                    app.label,
                    style = glass(34.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .combinedClickable(onClick = { onOpen(app) }, onLongClick = { onRemove(app) })
                        .padding(vertical = 6.dp)
                )
            }
        }

        Text(
            "All apps",
            style = glass(16.sp, FontWeight.Normal),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .clickable { onOpenAll() }
                .padding(28.dp)
        )
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val now by produceState(LocalTime.now()) {
        while (true) { value = LocalTime.now(); delay(15_000) }
    }
    Text(now.format(fmt), style = glass(56.sp, FontWeight.ExtraLight), modifier = modifier)
}

@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    favKeys: Set<String>,
    onOpen: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit
) {
    val listState = rememberLazyListState()

    fun letterOf(a: AppEntry): Char =
        a.label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

    // first list position for each letter, plus the letters that actually exist
    val firstIndex = remember(apps) {
        buildMap<Char, Int> { apps.forEachIndexed { i, a -> putIfAbsent(letterOf(a), i) } }
    }
    val letters = remember(firstIndex) { firstIndex.keys.sorted() }
    var active by remember { mutableStateOf<Char?>(null) }

    LaunchedEffect(active) {
        active?.let { c -> firstIndex[c]?.let { listState.scrollToItem(it) } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .systemBarsPadding()
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 28.dp, end = 64.dp, top = 16.dp, bottom = 16.dp)
        ) {
            items(apps, key = { it.key }) { app ->
                val fav = app.key in favKeys
                Text(
                    app.label + if (fav) "  ★" else "",
                    style = glass(26.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onOpen(app) },
                            onLongClick = { onToggleFavorite(app) }
                        )
                        .padding(vertical = 10.dp)
                )
            }
        }

        // alphabet rail: tap or drag along it to jump through the list
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(48.dp)
                .pointerInput(letters) {
                    fun pick(y: Float) {
                        if (letters.isEmpty()) return
                        val i = (y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        active = letters[i]
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.y)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            event.changes.firstOrNull()?.let { pick(it.position.y) }
                        } while (event.changes.any { it.pressed })
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { c ->
                Text(
                    c.toString(),
                    style = glass(13.sp, if (c == active) FontWeight.Bold else FontWeight.Normal)
                )
            }
        }
    }
}

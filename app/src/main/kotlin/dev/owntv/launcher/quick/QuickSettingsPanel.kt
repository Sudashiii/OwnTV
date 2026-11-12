package dev.owntv.launcher.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.actions.RemoteService
import dev.owntv.launcher.actions.StandbyService
import dev.owntv.launcher.ui.describe
import dev.owntv.launcher.ui.rememberInitialFocus
import dev.owntv.launcher.ui.rememberNetworkState
import dev.owntv.launcher.ui.render.NetworkState
import dev.owntv.launcher.ui.render.ShortcutIcon
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.OpenLauncherSettingsAction
import dev.owntv.layout.model.OpenSystemSettingsAction
import dev.owntv.layout.model.QuickSetting
import dev.owntv.layout.model.QuickSettingType
import dev.owntv.layout.model.SettingsScreen
import dev.owntv.layout.model.StandbyAction
import dev.owntv.layout.model.ThemeColors
import dev.owntv.layout.model.quickSettingsOrDefault
import kotlinx.coroutines.delay
import java.util.Date

/** The panel's colors, taken from the layout's theme so quick settings match the look. */
private data class PanelColors(val surface: Color, val text: Color, val muted: Color, val accent: Color)

private fun ThemeColors.panelColors(): PanelColors {
    fun color(token: String, fallback: Long) = Color(resolve(token) ?: fallback.toInt())
    val background = color("@background", 0xFF0E0F13).copy(alpha = 1f)
    // Glass looks use translucent surfaces; over a video the panel needs a solid one.
    val surface = color("@surface", 0xFF1F2229).compositeOver(background)
    return PanelColors(surface, color("@text", 0xFFECEDEF), color("@textMuted", 0xFF9AA0AA), color("@primary", 0xFF7AA2F7))
}

@Composable
fun QuickSettingsPanel(container: AppContainer, visible: MutableTransitionState<Boolean>, onClose: () -> Unit, onClosed: () -> Unit) {
    val layout by container.layouts.state.collectAsStateWithLifecycle()
    val quick = layout.document.quickSettingsOrDefault
    val colors = remember(layout.document.theme.colors) { layout.document.theme.colors.panelColors() }
    val radius = layout.document.theme.cornerRadius.coerceIn(8, 32).dp

    BackHandler(enabled = visible.targetState) { onClose() }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState) onClosed()
    }
    // Running something from the panel closes it; the activity finishes when that covers it.
    val run: (Action) -> Unit = { action ->
        onClose()
        container.runAnywhere(action)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            }
            AnimatedVisibility(
                visible,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
            ) {
                Column(
                    Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .background(colors.surface, RoundedCornerShape(topStart = radius, bottomStart = radius))
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            quick.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(rememberClockText(), style = MaterialTheme.typography.titleMedium, color = colors.muted)
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        quick.items.forEachIndexed { index, item ->
                            QuickSettingRow(
                                container,
                                item,
                                colors,
                                run,
                                if (index == 0) rememberInitialFocus() else Modifier,
                            )
                        }
                    }
                }
            }
        }
        DimLayer(container.dimmer)
    }
}

@Composable
private fun QuickSettingRow(container: AppContainer, item: QuickSetting, colors: PanelColors, run: (Action) -> Unit, modifier: Modifier) {
    when (item.type) {
        QuickSettingType.Brightness -> {
            val level by container.dimmer.level.collectAsStateWithLifecycle()
            val serviceOn by RemoteService.enabled.collectAsStateWithLifecycle()
            QuickRow(
                colors, item.icon ?: "brightness", item.label ?: "Brightness", "$level %",
                progress = level / 100f,
                note = if (serviceOn || level == BrightnessSteps.MAX) null else "Dims OwnTV only. Turn on \"OwnTV remote\" in Accessibility to dim every app.",
                modifier = modifier,
                onStep = { container.dimmer.set(BrightnessSteps.step(level, it)) },
            )
        }
        QuickSettingType.Volume -> VolumeRow(item, colors, modifier)
        QuickSettingType.SleepTimer -> {
            val endsAt by container.sleepTimer.endsAt.collectAsStateWithLifecycle()
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(endsAt) {
                while (true) {
                    now = SystemClock.elapsedRealtime()
                    if (endsAt == null) break
                    delay(15_000)
                }
            }
            val remaining = endsAt?.let { SleepSteps.remainingMinutes((it - now).coerceAtLeast(0)) }
            val standbyOn by StandbyService.enabled.collectAsStateWithLifecycle()
            QuickRow(
                colors, item.icon ?: "bedtime", item.label ?: "Sleep timer", SleepSteps.format(remaining),
                note = if (remaining != null && !standbyOn) "Without \"OwnTV standby\" in Accessibility the screen only goes black." else null,
                modifier = modifier,
                onStep = { container.sleepTimer.set(SleepSteps.step(remaining, it)) },
                onClick = { container.sleepTimer.set(SleepSteps.step(remaining, +1)) },
            )
        }
        QuickSettingType.Network -> {
            val network = rememberNetworkState()
            val (icon, state) = when (network) {
                NetworkState.Wifi -> "wifi" to "Wi-Fi"
                NetworkState.Ethernet -> "ethernet" to "Ethernet"
                NetworkState.Offline -> "wifi_off" to "Offline"
            }
            QuickRow(colors, item.icon ?: icon, item.label ?: "Network", state, modifier = modifier) {
                run(OpenSystemSettingsAction(SettingsScreen.Network))
            }
        }
        QuickSettingType.Bluetooth -> QuickRow(colors, item.icon ?: "bluetooth", item.label ?: "Bluetooth", null, modifier = modifier) {
            run(OpenSystemSettingsAction(SettingsScreen.Bluetooth))
        }
        QuickSettingType.Standby -> QuickRow(colors, item.icon ?: "power", item.label ?: "Standby", null, modifier = modifier) { run(StandbyAction) }
        QuickSettingType.AndroidSettings -> QuickRow(colors, item.icon ?: "settings", item.label ?: "Android settings", null, modifier = modifier) {
            run(OpenSystemSettingsAction())
        }
        QuickSettingType.OwnTvSettings -> QuickRow(colors, item.icon ?: "tune", item.label ?: "OwnTV settings", null, modifier = modifier) {
            run(OpenLauncherSettingsAction)
        }
        QuickSettingType.Action -> {
            val action = item.action
            QuickRow(colors, item.icon ?: "play", item.label ?: action?.describe() ?: "Action", null, modifier = modifier) {
                action?.let(run)
            }
        }
    }
}

@Composable
private fun VolumeRow(item: QuickSetting, colors: PanelColors, modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var muted by remember { mutableStateOf(audio.isStreamMute(AudioManager.STREAM_MUSIC)) }
    // The remote's own volume buttons change it too while the panel is open.
    DisposableEffect(audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    if (audio.isVolumeFixed || max <= 0) {
        QuickRow(colors, item.icon ?: "volume", item.label ?: "Volume", "On the TV", note = "Use the volume buttons of the remote.", modifier = modifier)
        return
    }
    fun refresh() {
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
    }
    QuickRow(
        colors,
        item.icon ?: if (muted) "volume_off" else "volume",
        item.label ?: "Volume",
        if (muted) "Muted" else "${VolumeSteps.percent(volume, max)} %",
        progress = if (muted) 0f else volume / max.toFloat(),
        modifier = modifier,
        onStep = { direction ->
            runCatching {
                if (muted) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, VolumeSteps.step(volume, max, direction), 0)
            }
            refresh()
        },
        onClick = {
            runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0) }
            refresh()
        },
    )
}

/**
 * One row: icon, label, value and an optional bar. With [onStep], left and right change the value (a "‹ ›"
 * shows while focused); OK runs [onClick].
 */
@Composable
private fun QuickRow(
    colors: PanelColors,
    icon: String,
    label: String,
    value: String?,
    progress: Float? = null,
    note: String? = null,
    modifier: Modifier = Modifier,
    onStep: ((Int) -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(150), label = "scale")
    val content = if (focused) colors.surface else colors.text
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .background(if (focused) colors.text else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val step = onStep ?: return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) step(direction)
                true
            }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShortcutIcon(icon, 24.dp, tint = if (focused) content else colors.accent)
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(
                    if (focused && onStep != null) "‹  $value  ›" else value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) content else colors.muted,
                    maxLines = 1,
                )
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(content.copy(alpha = 0.2f), RoundedCornerShape(2.dp))) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(if (focused) content else colors.accent, RoundedCornerShape(2.dp)),
                )
            }
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) content.copy(alpha = 0.75f) else colors.muted,
                modifier = Modifier.padding(start = 38.dp, top = 6.dp),
            )
        }
    }
}

/** The time in the TV's 12 or 24 hour format, updated on the minute. */
@Composable
private fun rememberClockText(): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    var text by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(format) {
        while (true) {
            text = format.format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    return text
}

/**
 * The brightness layer inside OwnTV's own screens, used while the remote service is off (with it on, the
 * service dims every app, OwnTV included).
 */
@Composable
fun DimLayer(dimmer: ScreenDimmer) {
    val serviceOn by RemoteService.enabled.collectAsStateWithLifecycle()
    val level by dimmer.level.collectAsStateWithLifecycle()
    val alpha = BrightnessSteps.dimAlpha(level)
    if (serviceOn || alpha <= 0f) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
}

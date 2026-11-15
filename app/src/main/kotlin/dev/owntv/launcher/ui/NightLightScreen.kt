package dev.owntv.launcher.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.actions.RemoteService
import dev.owntv.launcher.quick.NightLightConfig
import dev.owntv.launcher.quick.NightMode
import dev.owntv.launcher.quick.NightSteps
import dev.owntv.launcher.quick.ScreenFilter
import dev.owntv.launcher.quick.SunPlace
import dev.owntv.launcher.quick.formatClock
import dev.owntv.launcher.quick.nightUntil
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Settings > Display. */
@Composable
internal fun DisplaySettings(container: AppContainer, nav: Navigator) {
    val filter by container.nightLight.filter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsCard {
        row {
            SettingsRow("night-light", "Night light", nightLightSummary(context, filter), SettingsIcons.NightLight, SettingsColors.Orange) {
                nav.push(Screen.NightLight)
            }
        }
    }
}

/** Settings > Display > Night light: switch it now, the schedule and the look. */
@Composable
fun NightLightScreen(container: AppContainer) {
    val night = container.nightLight
    val config by night.config.collectAsStateWithLifecycle()
    val filter by night.filter.collectAsStateWithLifecycle()
    val place by night.location.place.collectAsStateWithLifecycle()
    val serviceOn by RemoteService.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun update(transform: (NightLightConfig) -> NightLightConfig) = night.update(transform)
    SettingsScaffold(
        "Night light",
        "A warmer, darker picture in the evening. Changes made in quick settings while it is on hold until it switches off.",
    ) {
        if (!serviceOn) {
            Text(
                "Only OwnTV's own screens change. Turn on \"OwnTV remote\" in Accessibility (Settings > Remote & standby > Remote buttons) to cover every app.",
                style = MaterialTheme.typography.bodySmall,
                color = SettingsColors.Muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                row {
                    SettingsRow(
                        "night-now",
                        if (filter.on) "Turn off" else "Turn on",
                        when {
                            config.mode == NightMode.Off -> if (filter.on) null else "Stays on until you turn it off"
                            filter.on -> "Stays off until the next night"
                            else -> "Stays on until the schedule turns it off"
                        },
                        SettingsIcons.NightLight,
                        SettingsColors.Orange,
                        RowEnd.Value(nightLightSummary(context, filter)),
                        modifier = rememberInitialFocus(),
                    ) { night.toggle() }
                }
                if (filter.overridden) {
                    row {
                        SettingsRow(
                            "night-reset",
                            "Back to the schedule",
                            "Undo the changes made by hand, which otherwise hold ${nightUntil(context, filter.nextSwitch)}",
                            SettingsIcons.Undo,
                            SettingsColors.Graphite,
                            RowEnd.None,
                        ) { night.clearOverride() }
                    }
                }
            }
            SettingsCard("Schedule") {
                row {
                    SettingsRow(
                        "night-mode",
                        "Turns on",
                        when (config.mode) {
                            NightMode.Off -> "Only when you switch it on"
                            NightMode.Custom -> "Every day at the times below"
                            NightMode.Sun -> "At sunset, off at sunrise"
                        },
                        SettingsIcons.Bedtime,
                        SettingsColors.Indigo,
                        RowEnd.Value(modeLabel(config.mode)),
                        onStep = { d -> update { it.copy(mode = NightSteps.mode(it.mode, d)) } },
                    ) { update { it.copy(mode = NightSteps.mode(it.mode, +1)) } }
                }
                if (config.mode == NightMode.Sun) {
                    row {
                        SettingsRow(
                            "night-location",
                            "Location",
                            locationSubtitle(context, place, config, night.location.hasPermission()),
                            SettingsIcons.Place,
                            SettingsColors.Green,
                            RowEnd.Value(place?.label ?: "Unknown"),
                        ) { night.location.requestAccess() }
                    }
                }
                if (config.mode == NightMode.Custom) {
                    row {
                        SettingsRow(
                            "night-start", "Starts", null, SettingsIcons.Schedule, SettingsColors.Blue,
                            RowEnd.Value(formatMinute(context, config.startMinute)),
                            onStep = { d -> update { it.copy(startMinute = NightSteps.time(it.startMinute, d)) } },
                        ) {}
                    }
                    row {
                        SettingsRow(
                            "night-end", "Ends", null, SettingsIcons.Schedule, SettingsColors.Blue,
                            RowEnd.Value(formatMinute(context, config.endMinute)),
                            onStep = { d -> update { it.copy(endMinute = NightSteps.time(it.endMinute, d)) } },
                        ) {}
                    }
                }
                row {
                    SettingsRow(
                        "night-fade", "Fade", "How long it takes to turn on and off", SettingsIcons.History, SettingsColors.Teal,
                        RowEnd.Value(if (config.fadeMinutes == 0) "Off" else "${config.fadeMinutes} min"),
                        enabled = config.mode != NightMode.Off,
                        onStep = { d -> update { it.copy(fadeMinutes = NightSteps.fade(it.fadeMinutes, d)) } },
                    ) {}
                }
            }
            SettingsCard("Look") {
                row {
                    SettingsRow(
                        "night-temperature", "Color temperature", "Lower is warmer", SettingsIcons.Thermostat, SettingsColors.Red,
                        RowEnd.Value("${config.temperatureK} K"),
                        onStep = { d -> update { it.copy(temperatureK = NightSteps.temperature(it.temperatureK, d)) } },
                    ) {}
                }
                row {
                    SettingsRow(
                        "night-intensity", "Strength", "How strong the warm color is", SettingsIcons.Palette, SettingsColors.Orange,
                        RowEnd.Value("${config.intensity} %"),
                        onStep = { d -> update { it.copy(intensity = NightSteps.percent(it.intensity, d)) } },
                    ) {}
                }
                row {
                    SettingsRow(
                        "night-darkening", "Darken screen", "How much darker than the day brightness", SettingsIcons.Brightness, SettingsColors.Gray,
                        RowEnd.Value("${config.darkening} %"),
                        onStep = { d -> update { it.copy(darkening = NightSteps.percent(it.darkening, d, NightSteps.MAX_DARKENING)) } },
                    ) {}
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** "On until 07:00", "Off · on at 22:00", "Off". */
internal fun nightLightSummary(context: Context, filter: ScreenFilter): String = when {
    filter.on -> "On ${nightUntil(context, filter.nextSwitch)}"
    filter.nextSwitch != null -> "Off · on at ${formatClock(context, filter.nextSwitch)}"
    else -> "Off"
}

private fun modeLabel(mode: NightMode) = when (mode) {
    NightMode.Off -> "Off"
    NightMode.Custom -> "Custom times"
    NightMode.Sun -> "Sunset to sunrise"
}

private fun locationSubtitle(context: Context, place: SunPlace?, config: NightLightConfig, permission: Boolean): String {
    val fallback = "${formatMinute(context, config.startMinute)}–${formatMinute(context, config.endMinute)}"
    return when {
        place == null -> "Unknown: uses $fallback. OK asks for this TV's location."
        place.source == SunPlace.Source.Device -> "This TV's location. OK looks again."
        permission -> "From the time zone; this TV did not say where it is. OK looks again."
        else -> "From the time zone. OK uses this TV's location instead."
    }
}

/** A time of day in the TV's 12 or 24 hour format. */
private fun formatMinute(context: Context, minute: Int): String {
    val m = NightSteps.minuteOfDay(minute)
    val ms = LocalDate.now().atTime(m / 60, m % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return DateFormat.getTimeFormat(context).format(Date(ms))
}

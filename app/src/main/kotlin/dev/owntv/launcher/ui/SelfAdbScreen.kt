package dev.owntv.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.adb.AdbCommand
import dev.owntv.launcher.adb.AdbCommands
import dev.owntv.launcher.adb.AdbProtocol
import dev.owntv.launcher.adb.SelfAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Lets [Code] run `adb shell` lines on this TV; null where nothing may run (previews). */
val LocalSelfAdb = staticCompositionLocalOf<SelfAdb?> { null }

/** One line of a settings list that says how ADB on this TV is doing. */
fun selfAdbSummary(status: SelfAdb.Status): String = when (status) {
    SelfAdb.Status.Idle -> "Run OwnTV's adb commands on this TV, without a computer"
    SelfAdb.Status.Connecting -> "Connecting…"
    SelfAdb.Status.WaitingForApproval -> "Waiting for \"Allow USB debugging?\" on the TV"
    SelfAdb.Status.Ready -> "Connected · OK on an adb command runs it on this TV"
    is SelfAdb.Status.Failed -> status.problem.help()
}

fun SelfAdb.Problem.help(): String = when (this) {
    SelfAdb.Problem.NotListening ->
        "This TV does not accept debugging yet: turn on USB debugging (on some TVs Network debugging) in Developer options. See Settings > Developer > ADB on this TV."
    SelfAdb.Problem.NotApproved ->
        "The TV did not allow it. Try again and choose Allow on \"Allow USB debugging?\", with \"Always allow\" ticked."
    SelfAdb.Problem.NeedsPairing ->
        "This TV only offers wireless debugging with a pairing code, which OwnTV cannot use. Run the command from a computer."
    SelfAdb.Problem.Broken -> "The connection to the TV broke. Try again."
}

private sealed interface RunState {
    data object Idle : RunState
    data object Running : RunState
    data class Done(val result: AdbProtocol.ShellResult) : RunState
    data class Failed(val error: Throwable) : RunState
}

/** One `adb shell` command run on this TV through [SelfAdb], and how it went. */
@Stable
private class AdbRun(private val adb: SelfAdb, private val command: String, private val scope: CoroutineScope) {
    var state by mutableStateOf<RunState>(RunState.Idle)
        private set

    fun start() {
        if (state == RunState.Running) return
        state = RunState.Running
        scope.launch { state = adb.run(command).fold(onSuccess = { RunState.Done(it) }, onFailure = { RunState.Failed(it) }) }
    }

    fun note(status: SelfAdb.Status): String = when (val current = state) {
        RunState.Idle -> "OK runs it on this TV"
        RunState.Running -> if (status == SelfAdb.Status.WaitingForApproval) {
            "On the TV's \"Allow USB debugging?\" dialog, tick \"Always allow\" and choose Allow"
        } else {
            "Running…"
        }
        is RunState.Done -> when {
            current.result.exitCode == 0 -> "Done" + current.result.output.firstLine()?.let { ": $it" }.orEmpty()
            else -> "Failed" + current.result.exitCode?.let { " (exit $it)" }.orEmpty() + current.result.output.firstLine()?.let { ": $it" }.orEmpty()
        }
        is RunState.Failed -> (current.error as? SelfAdb.AdbException)?.problem?.help() ?: "Failed: ${current.error.message}"
    }
}

@Composable
private fun rememberAdbRun(adb: SelfAdb, command: String): AdbRun {
    val scope = rememberCoroutineScope()
    return remember(adb, command) { AdbRun(adb, command, scope) }
}

/**
 * A command to type into a computer. `adb shell …` lines become rows that run the command on this TV with OK
 * (see [SelfAdb]); other adb commands (push, install) need a computer and stay plain text.
 */
@Composable
fun Code(text: String, modifier: Modifier = Modifier) {
    val adb = LocalSelfAdb.current
    val command = AdbProtocol.shellCommandOf(text)
    if (adb == null || command == null) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color(0xFF9ECBFF),
            modifier = modifier.padding(top = 6.dp, start = 12.dp),
        )
        return
    }
    RunnableCode(text, command, adb, modifier)
}

@Composable
private fun RunnableCode(text: String, command: String, adb: SelfAdb, modifier: Modifier) {
    val run = rememberAdbRun(adb, command)
    val status by adb.status.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, tween(150), label = "scale")
    val colors = MaterialTheme.colorScheme
    val content = if (focused) colors.inverseOnSurface else Color(0xFF9ECBFF)
    Column(
        modifier
            .padding(top = 6.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .background(if (focused) colors.inverseSurface else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null) { run.start() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = content)
        Text(run.note(status), style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp))
    }
}

/** Settings > Developer > ADB commands: every adb command OwnTV uses, what it does, and OK to run it on this TV. */
@Composable
fun AdbCommandsScreen(container: AppContainer) {
    val status by container.adb.status.collectAsStateWithLifecycle()
    val groups = remember { AdbCommands.groups(container.files.adbPath) }
    SettingsScaffold(
        "ADB commands",
        "Every adb command OwnTV uses and what it does. OK runs one on this TV, or type it on a computer.",
    ) {
        // Only when there is news: connecting, waiting for "Allow USB debugging?", or why it failed.
        if (status != SelfAdb.Status.Idle) {
            Text(selfAdbSummary(status), style = MaterialTheme.typography.bodySmall, color = SettingsColors.Muted, modifier = Modifier.padding(bottom = 12.dp))
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            groups.forEachIndexed { groupIndex, group ->
                SettingsCard(group.title) {
                    group.commands.forEachIndexed { index, command ->
                        row {
                            AdbCommandRow(
                                command,
                                container.adb,
                                if (groupIndex == 0 && index == 0) rememberInitialFocus() else Modifier,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AdbCommandRow(command: AdbCommand, adb: SelfAdb, modifier: Modifier) {
    val shell = command.shellCommand
    val run = shell?.let { rememberAdbRun(adb, it) }
    val status by adb.status.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }
    val text = SettingsColors.Text
    Column(
        modifier
            .fillMaxWidth()
            .background(if (focused) SettingsColors.Focus else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            // Rows that need a computer still take focus, so the list scrolls to them with the remote.
            .clickable(interactionSource = null, indication = null) { run?.start() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(command.title, style = MaterialTheme.typography.titleMedium, color = text)
        Text(
            command.description,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) text.copy(alpha = 0.85f) else SettingsColors.Muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            command.line,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (focused) text else Color(0xFF9ECBFF),
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            run?.note(status) ?: "Type it on a computer connected to this TV with adb",
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) text.copy(alpha = 0.8f) else SettingsColors.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun String.firstLine(): String? = lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(160)

/** Settings > Developer > ADB on this TV: how to let OwnTV run its adb commands itself, and a connection test. */
@Composable
fun SelfAdbScreen(container: AppContainer) {
    val status by container.adb.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SettingsScaffold(
        "ADB on this TV",
        "OwnTV can run the adb commands it shows on this TV itself, like a computer connected to it.",
    ) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                SettingsItem("Connect", selfAdbSummary(status), modifier = rememberInitialFocus()) {
                    if (status != SelfAdb.Status.Connecting && status != SelfAdb.Status.WaitingForApproval) {
                        scope.launch { container.adb.test() }
                    }
                }
            }
            item {
                SettingsItem("Open Developer options", "Turn on USB debugging there") { container.actions.openDeveloperSettings() }
            }
            item {
                HelpText("1. In Settings > System > Developer options turn on USB debugging (and Network debugging, if listed). No Developer options? System > About, press OK on \"Android TV OS build\" seven times.")
                HelpText("2. Choose Connect. The TV asks \"Allow USB debugging?\" once: tick \"Always allow\", then choose Allow (left of Cancel).")
                HelpText("Then OK on an `adb shell` command in OwnTV runs it here. OwnTV only runs the commands it shows. TVs that offer only wireless debugging with a pairing code need a computer to open port ${SelfAdb.PORT} once (until the next restart):")
                Code(AdbCommands.OpenAdbPort.line)
            }
        }
    }
}

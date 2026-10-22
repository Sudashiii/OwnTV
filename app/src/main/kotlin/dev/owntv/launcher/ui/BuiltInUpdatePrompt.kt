package dev.owntv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.owntv.launcher.data.layout.BuiltInUpdate

/** What the update changes for the user, in the words the prompt and Settings share. */
fun BuiltInUpdate.description(): String =
    "A new OwnTV version updated the ${ref.kind.label} \"$name\". You haven't changed your layout, so it can be replaced " +
        "with the new version. Your current layout stays in Layout history."

/** One click to switch to the newer built-in layout, or "Not now". Back counts as "Not now". */
@Composable
fun BuiltInUpdatePrompt(update: BuiltInUpdate, onUpdate: () -> Unit, onLater: () -> Unit) {
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier
                .widthIn(min = 420.dp, max = 520.dp)
                .background(Color(0xFF1C1E24), RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text("New version of \"${update.name}\"", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                update.description(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFDADDE3),
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onUpdate, modifier = rememberInitialFocus()) { Text("Update now") }
                OutlinedButton(onClick = onLater) { Text("Not now") }
            }
            Text(
                "You can still update later in launcher settings.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA9AFBA),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

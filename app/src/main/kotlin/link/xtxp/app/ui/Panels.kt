package link.xtxp.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import uniffi.xtxp_mobile.PendingChange
import uniffi.xtxp_mobile.Remote
import java.text.DateFormat
import java.util.Date

private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Shown while offline: the local changes that `+` would merge. */
@Composable
fun PendingPanel(pending: List<PendingChange>, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(true) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (pending.size) {
                        0 -> "Offline. No local changes yet."
                        1 -> "Offline. 1 local change."
                        else -> "Offline. ${pending.size} local changes."
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (pending.isNotEmpty()) Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "Nothing merges either way until you tap + or go back online.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (open && pending.isNotEmpty()) {
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    pending.forEach { c ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(time(c.atMs), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                            Text(c.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** The `>` sheet: the sessions this one trusts, both ways for now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotesSheet(
    remotes: List<Remote>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Remotes", style = MaterialTheme.typography.titleLarge)
            Text(
                "Sessions this one trusts. Changes merge both ways.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(4.dp))
            if (remotes.isEmpty()) Text("No remotes. Add one to merge with it.")
            remotes.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        val error = r.lastError
                        when {
                            error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            r.lastSyncMs != null -> Text("⇄ both ways · merged at ${time(r.lastSyncMs!!)}", style = MaterialTheme.typography.bodySmall)
                            else -> Text("⇄ both ways · not merged yet", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { onRemove(r.address) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
            Spacer(Modifier.padding(4.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Add a session") },
                placeholder = { Text("host/doc@main") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (address.isNotBlank()) { onAdd(address); address = "" } }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onAdd(address); address = "" },
                enabled = address.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
            ) { Text("Add") }
        }
    }
}

/** A document with nothing in it yet: fetch it, or start offline. */
@Composable
fun EmptyDocument(onFetch: () -> Unit, onSample: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing here yet.", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onFetch) { Text("Fetch from remotes") }
        OutlinedButton(onClick = onSample) { Text("Start offline with a sample drawing") }
    }
}

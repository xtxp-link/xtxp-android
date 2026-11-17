package link.xtxp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import uniffi.xtxp_mobile.Attr
import uniffi.xtxp_mobile.Row as ElementRow

/** One attribute as the editor holds it while the person types. */
private class Field(val name: String, val display: String, val original: String?) {
    var value by mutableStateOf(original ?: "")
    var removed by mutableStateOf(false)
}

/**
 * Edits one element: its attributes (change, remove, add) and, when it has
 * one run of text, its text. Save sends everything as one edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementEditor(
    row: ElementRow,
    onSave: (set: List<Attr>, remove: List<String>, text: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val fields = remember(row) {
        mutableStateListOf<Field>().apply { row.attrs.forEach { add(Field(it.name, it.display, it.value)) } }
    }
    var text by remember(row) { mutableStateOf(row.editableText) }
    var newName by remember(row) { mutableStateOf("") }
    var newValue by remember(row) { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("<${row.name}>", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            fields.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = f.value,
                        onValueChange = { f.value = it },
                        label = { Text(f.display) },
                        enabled = !f.removed,
                        singleLine = f.display != "d" && f.display != "style",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { f.removed = !f.removed }) { Text(if (f.removed) "Keep" else "Remove") }
                }
            }
            text?.let { t ->
                OutlinedTextField(
                    value = t,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Add an attribute", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.trim() },
                    label = { Text("Name") },
                    placeholder = { Text("fill") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = newValue,
                    onValueChange = { newValue = it },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    val set = fields.filter { !it.removed && it.value != it.original }
                        .map { Attr(it.name, it.display, it.value) }
                        .toMutableList()
                    if (newName.isNotBlank()) set += Attr(newName, newName, newValue)
                    val remove = fields.filter { it.removed }.map { it.name }
                    val newText = text?.takeIf { it != row.editableText }
                    onSave(set, remove, newText)
                }) { Text("Save") }
            }
        }
    }
}

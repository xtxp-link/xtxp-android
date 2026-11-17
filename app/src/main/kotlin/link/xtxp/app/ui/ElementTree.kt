package link.xtxp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import link.xtxp.core.key
import uniffi.xtxp_mobile.Row as ElementRow

/** The document's elements as an indented list. Tap one to edit it. */
@Composable
fun ElementTree(
    rows: List<ElementRow>,
    selected: String?,
    onEdit: (ElementRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { row ->
            ElementRowView(row, selected = row.key == selected, modifier = Modifier.clickable { onEdit(row) })
        }
    }
}

@Composable
private fun ElementRowView(row: ElementRow, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .padding(start = (16 + 20 * row.depth.toInt()).dp, end = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("<${row.name}>", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
            if (row.label.isNotEmpty()) {
                Text(row.label, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.text.isNotEmpty()) {
                Text(
                    "“${row.text}”",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

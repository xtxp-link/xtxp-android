package link.xtxp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import link.xtxp.core.Hit
import link.xtxp.core.RowSlot
import link.xtxp.core.hitTest
import uniffi.xtxp_mobile.Drop
import uniffi.xtxp_mobile.Row as ElementRow

/**
 * The document's elements as an indented list. Long-press a row and drag it:
 * the top of another row drops before it, the bottom after it, and the
 * middle into it as its last child.
 */
@Composable
fun ElementTree(
    rows: List<ElementRow>,
    onMove: (dragged: ElementRow, target: ElementRow, drop: Drop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val currentRows by rememberUpdatedState(rows)
    val currentOnMove by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<Int?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun slots() = list.layoutInfo.visibleItemsInfo.map { RowSlot(it.index, it.offset, it.size) }
    val hit: Hit? = dragging?.let { hitTest(pointerY, slots(), it) }

    LazyColumn(
        state = list,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        dragging = slots().firstOrNull { start.y >= it.top && start.y < it.top + it.height }?.index
                        pointerY = start.y
                        dragOffset = 0f
                    },
                    onDrag = { change, amount ->
                        if (dragging != null) {
                            change.consume()
                            pointerY += amount.y
                            dragOffset += amount.y
                        }
                    },
                    onDragEnd = {
                        val from = dragging
                        val to = from?.let { hitTest(pointerY, slots(), it) }
                        dragging = null
                        val rs = currentRows
                        if (from != null && to != null && from in rs.indices && to.index in rs.indices) {
                            currentOnMove(rs[from], rs[to.index], to.drop)
                        }
                    },
                    onDragCancel = { dragging = null },
                )
            },
    ) {
        itemsIndexed(rows, key = { _, r -> "${r.client}:${r.clock}" }) { index, row ->
            val lifted = dragging == index
            ElementRowView(
                row = row,
                drop = hit?.takeIf { it.index == index }?.drop,
                modifier = Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer { translationY = if (lifted) dragOffset else 0f }
                    .then(if (lifted) Modifier.shadow(8.dp) else Modifier),
            )
        }
    }
}

@Composable
private fun ElementRowView(row: ElementRow, drop: Drop?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val line = colors.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(colors.surface)
            .then(
                when (drop) {
                    Drop.INTO -> Modifier
                        .background(colors.primary.copy(alpha = 0.12f))
                        .border(2.dp, line, RoundedCornerShape(4.dp))
                    Drop.BEFORE -> Modifier.drawBehind {
                        drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 3.dp.toPx())
                    }
                    Drop.AFTER -> Modifier.drawBehind {
                        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 3.dp.toPx())
                    }
                    null -> Modifier
                },
            )
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

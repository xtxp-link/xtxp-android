package link.xtxp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import link.xtxp.core.SplitBar

/** The full-width `< + >` bar: offline, merge, remotes. */
@Composable
fun SplitBarView(
    bar: SplitBar,
    onLeft: () -> Unit,
    onPlus: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        if (bar.offline) colors.tertiaryContainer to colors.onTertiaryContainer
        else colors.primaryContainer to colors.onPrimaryContainer
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(container),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(1f, bar.leftDescription, onLeft) {
            if (bar.left == SplitBar.Glyph.PAUSE) PauseGlyph(content) else Glyph("‹", content)
            Spacer(Modifier.width(6.dp))
            Text(bar.leftLabel, color = content, style = MaterialTheme.typography.labelLarge)
        }
        VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 12.dp), color = content.copy(alpha = 0.3f))
        Segment(1.4f, "Merge both ways now. ${bar.plusLabel}", onPlus, enabled = bar.plusEnabled) {
            if (bar.syncing) {
                CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp)
            } else {
                Glyph("+", content.copy(alpha = if (bar.plusEnabled) 1f else 0.4f))
            }
            Spacer(Modifier.width(6.dp))
            Text(bar.plusLabel, color = content, style = MaterialTheme.typography.labelLarge)
        }
        VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 12.dp), color = content.copy(alpha = 0.3f))
        Segment(1f, "Show remotes. ${bar.rightLabel}", onRight) {
            Text(
                bar.rightLabel,
                color = if (bar.failing) colors.error else content,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(6.dp))
            Glyph("›", content)
        }
    }
}

@Composable
private fun RowScope.Segment(
    weight: Float,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun Glyph(text: String, color: Color) {
    Text(text, color = color, fontSize = 26.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PauseGlyph(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(2) { Box(Modifier.size(width = 4.dp, height = 16.dp).background(color, RoundedCornerShape(1.dp))) }
    }
}

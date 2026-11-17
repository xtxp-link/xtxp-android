package link.xtxp.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import link.xtxp.app.DocumentViewModel
import link.xtxp.core.SplitBar
import link.xtxp.core.key

/** What fills the screen under the bar. The drawing is home; ⋮ opens the rest. */
private enum class View { DRAWING, SOURCE, ELEMENTS }

@Composable
fun DocumentScreen(vm: DocumentViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var showRemotes by remember { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(View.DRAWING) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = view != View.DRAWING) { view = View.DRAWING }

    val bar = SplitBar(
        offline = state.offline,
        pending = state.pending.size,
        syncing = syncing,
        remotes = state.remotes.size,
        failing = state.remotes.any { it.lastError != null },
    )
    Scaffold(
        topBar = {
            SplitBarView(
                bar,
                onLeft = { vm.toggleOffline() },
                onPlus = { vm.merge() },
                onRight = { showRemotes = true },
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (state.offline) PendingPanel(state.pending)
            Box(Modifier.fillMaxSize()) {
                when {
                    state.empty -> EmptyDocument(onFetch = { vm.merge() })
                    view == View.DRAWING -> Drawing(
                        svg = state.svg,
                        selected = selected,
                        onSelect = { vm.select(it) },
                        onMoved = { key, dx, dy -> vm.translate(key, dx, dy) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    view == View.SOURCE -> Source(state.source, onClose = { view = View.DRAWING })
                    view == View.ELEMENTS -> Column(Modifier.fillMaxSize()) {
                        Header("Elements", onClose = { view = View.DRAWING })
                        ElementTree(
                            state.rows,
                            selected = selected,
                            onSelect = { vm.select(it.key) },
                            onMove = { a, b, drop -> vm.move(a, b, drop) },
                        )
                    }
                }
                if (view == View.DRAWING) {
                    MoreMenu(
                        onSource = { view = View.SOURCE },
                        onElements = { view = View.ELEMENTS },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }
        }
    }
    if (showRemotes) {
        RemotesSheet(
            remotes = state.remotes,
            onAdd = { vm.addRemote(it) },
            onRemove = { vm.removeRemote(it) },
            onDismiss = { showRemotes = false },
        )
    }
}

/** The ⋮ button over the drawing's top right corner, for views of the document. */
@Composable
private fun MoreMenu(onSource: () -> Unit, onElements: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 2.dp,
        ) {
            TextButton(
                onClick = { open = true },
                modifier = Modifier.semantics { contentDescription = "More" },
            ) { Text("⋮", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("View source") }, onClick = { open = false; onSource() })
            DropdownMenuItem(text = { Text("Elements") }, onClick = { open = false; onElements() })
        }
    }
}

@Composable
private fun Header(title: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClose) { Text("Done") }
    }
}

/** The document's XML in canonical form, selectable for copying. */
@Composable
private fun Source(source: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("Source", onClose)
        SelectionContainer(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(pretty(source), fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
        }
    }
}

/**
 * Canonical form has no whitespace between tags; this breaks the line
 * before each tag that follows another tag, indented by depth. Text is
 * left as it is.
 */
internal fun pretty(xml: String): String {
    val out = StringBuilder()
    var depth = 0
    var i = 0
    var prevWasTag = false
    while (i < xml.length) {
        if (xml[i] == '<') {
            val end = xml.indexOf('>', i).let { if (it < 0) xml.length - 1 else it }
            val tag = xml.substring(i, end + 1)
            val closing = tag.startsWith("</")
            val selfClosing = tag.endsWith("/>") || tag.startsWith("<?") || tag.startsWith("<!")
            if (closing) depth = (depth - 1).coerceAtLeast(0)
            if (prevWasTag && out.isNotEmpty()) out.append('\n').append("  ".repeat(depth))
            out.append(tag)
            if (!closing && !selfClosing) depth++
            prevWasTag = true
            i = end + 1
        } else {
            val next = xml.indexOf('<', i).let { if (it < 0) xml.length else it }
            out.append(xml, i, next)
            prevWasTag = false
            i = next
        }
    }
    return out.toString()
}

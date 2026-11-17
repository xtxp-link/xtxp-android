package link.xtxp.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import link.xtxp.app.DocumentViewModel
import link.xtxp.core.SplitBar
import link.xtxp.core.key

@Composable
fun DocumentScreen(vm: DocumentViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var showRemotes by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

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
            if (state.empty) {
                EmptyDocument(onFetch = { vm.merge() })
            } else {
                Drawing(
                    svg = state.svg,
                    selected = selected,
                    onSelect = { vm.select(it) },
                    onMoved = { key, dx, dy -> vm.translate(key, dx, dy) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                HorizontalDivider()
                ElementTree(
                    state.rows,
                    selected = selected,
                    onSelect = { vm.select(it.key) },
                    onMove = { a, b, drop -> vm.move(a, b, drop) },
                    modifier = Modifier.weight(1f),
                )
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

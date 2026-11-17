package link.xtxp.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import link.xtxp.core.DocState
import link.xtxp.core.reason
import uniffi.xtxp_mobile.Attr
import uniffi.xtxp_mobile.Row

class DocumentViewModel(app: Application) : AndroidViewModel(app) {
    private val doc = Documents.get(app)
    private val schedule = SyncSchedule(app)

    val state: StateFlow<DocState> = doc.state

    /** True while WorkManager is merging. */
    val syncing: StateFlow<Boolean> = WorkManager.getInstance(app).let { work ->
        combine(SyncSchedule.ALL.map { work.getWorkInfosForUniqueWorkFlow(it) }) { all ->
            all.any { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

    private val _selected = MutableStateFlow<String?>(null)
    /** The element picked on the drawing or in the list, as `client:clock`. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun select(key: String?) {
        _selected.value = key
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch {
            doc.refresh()
            if (!state.value.offline) schedule.online()
        }
    }

    /** `<`: offline stops merging both ways; tapping the pause sign comes back. */
    fun toggleOffline() = viewModelScope.launch {
        val offline = !state.value.offline
        doc.setOffline(offline).onFailure { _messages.emit(it.reason) }
        if (offline) schedule.offline() else schedule.online()
    }

    /** `+`: merge both ways now, online or offline. */
    fun merge() {
        schedule.mergeNow()
    }

    /** The element editor's Save. */
    fun editElement(row: Row, set: List<Attr>, remove: List<String>, text: String?) = viewModelScope.launch {
        doc.editElement(row, set, remove, text)
            .onSuccess { if (!state.value.offline) schedule.soon() }
            .onFailure { _messages.emit(it.reason) }
    }

    /** Shows a message from the drawing in the snackbar. */
    fun say(message: String) {
        _messages.tryEmit(message)
    }

    /** A drag on the drawing, in the element's parent's user units. */
    fun translate(key: String, dx: Double, dy: Double) = viewModelScope.launch {
        doc.translate(key, dx, dy)
            .onSuccess { if (!state.value.offline) schedule.soon() }
            .onFailure { _messages.emit(it.reason) }
    }

    fun addRemote(address: String) = viewModelScope.launch {
        doc.addRemote(address).onFailure { _messages.emit(it.reason) }
    }

    fun removeRemote(address: String) = viewModelScope.launch {
        doc.removeRemote(address).onFailure { _messages.emit(it.reason) }
    }
}

package link.xtxp.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.xtxp_mobile.Drop
import uniffi.xtxp_mobile.Muppet
import uniffi.xtxp_mobile.PendingChange
import uniffi.xtxp_mobile.Remote
import uniffi.xtxp_mobile.Row
import uniffi.xtxp_mobile.SyncReport
import uniffi.xtxp_mobile.XtxpException

/** Everything the screen draws, read from the muppet in one go. */
data class DocState(
    val rows: List<Row> = emptyList(),
    val pending: List<PendingChange> = emptyList(),
    val remotes: List<Remote> = emptyList(),
    val offline: Boolean = false,
    val empty: Boolean = true,
    /** The drawing, each element marked with `data-xtxp-node="client:clock"`. */
    val svg: String = "",
    /** The document itself, in canonical form: what View source shows. */
    val source: String = "",
    val revision: ULong = 0u,
)

/**
 * The phone's session of one document, stored in [dir]. Every call runs on
 * [io], because the muppet writes its files as it goes.
 */
class Document(dir: String, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val muppet = Muppet.open(dir)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DocState> = _state.asStateFlow()

    private fun read() = DocState(
        rows = muppet.rows(),
        pending = muppet.pending(),
        remotes = muppet.remotes(),
        offline = muppet.isPaused(),
        empty = muppet.isEmpty(),
        svg = muppet.svg(),
        source = muppet.canonical(),
        revision = muppet.revision(),
    )

    suspend fun refresh() = withContext(io) { _state.value = read() }

    private suspend fun <T> act(block: () -> T): Result<T> = withContext(io) {
        val result = try {
            Result.success(block())
        } catch (e: XtxpException) {
            Result.failure(e)
        }
        _state.value = read()
        result
    }

    suspend fun move(dragged: Row, target: Row, drop: Drop) =
        act { muppet.moveNode(dragged.client, dragged.clock, target.client, target.clock, drop) }

    /** Drags the element marked [key] by `(dx, dy)` in its parent's user units. */
    suspend fun translate(key: String, dx: Double, dy: Double) = act {
        val (client, clock) = parseKey(key) ?: throw XtxpException.Invalid("not a node: $key")
        muppet.translate(client, clock, dx, dy)
    }

    suspend fun setOffline(offline: Boolean) = act { muppet.setPaused(offline) }

    suspend fun addRemote(address: String) = act { muppet.addRemote(address) }

    suspend fun removeRemote(address: String) = act { muppet.removeRemote(address) }

    suspend fun startWithSample() = act { muppet.startWithSample() }

    /** Blocking network work: WorkManager calls this, not the UI. */
    suspend fun sync(force: Boolean): Result<SyncReport> = act { muppet.sync(force) }

    fun canonical(): String = muppet.canonical()
}

/** The muppet's own words for what went wrong, for a snackbar. */
val Throwable.reason: String
    get() = when (this) {
        is XtxpException.Invalid -> msg
        is XtxpException.Store -> msg
        is XtxpException.Network -> msg
        else -> message ?: toString()
    }

/** The key a row and the drawing share for one element: `client:clock`. */
val Row.key: String get() = "$client:$clock"

fun parseKey(key: String): Pair<ULong, UInt>? {
    val (a, b) = key.split(':').takeIf { it.size == 2 } ?: return null
    return (a.toULongOrNull() ?: return null) to (b.toUIntOrNull() ?: return null)
}

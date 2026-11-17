package link.xtxp.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import uniffi.xtxp_mobile.Drop
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin bindings against the real muppet, built for this machine. */
class DocumentTest {
    @Test
    fun aFreshDocumentIsEmptyAndTrustsTheWorker() = runTest {
        val d = Document(Files.createTempDirectory("xtxp").toString(), StandardTestDispatcher(testScheduler))
        val s = d.state.value
        assertTrue(s.empty)
        assertFalse(s.offline)
        assertEquals(listOf("xtxp-worker.campey.workers.dev/xtxp-logo@main"), s.remotes.map { it.address })
    }

    @Test
    fun dragsGoPendingAndSurviveAReopen() = runTest {
        val dir = Files.createTempDirectory("xtxp").toString()
        val d = Document(dir, StandardTestDispatcher(testScheduler))
        assertTrue(d.startWithSample().isSuccess)
        d.setOffline(true)
        val rows = d.state.value.rows
        val sun = rows.first { it.label == "#sun" }
        val ground = rows.first { it.label == "Ground" }
        assertTrue(d.move(sun, ground, Drop.INTO).isSuccess)
        assertEquals(listOf("Moved <circle #sun> into <g Ground>"), d.state.value.pending.map { it.summary })

        // Offline means no merging at all, so an automatic sync is skipped.
        assertTrue(d.sync(force = false).getOrThrow().skipped)

        val reopened = Document(dir, StandardTestDispatcher(testScheduler))
        assertEquals(d.canonical(), reopened.canonical())
        assertTrue(reopened.state.value.offline)
        assertEquals(1, reopened.state.value.pending.size)
    }

    @Test
    fun aBadDropIsAFailureNotACrash() = runTest {
        val d = Document(Files.createTempDirectory("xtxp").toString(), StandardTestDispatcher(testScheduler))
        d.startWithSample()
        val rows = d.state.value.rows
        val result = d.move(rows.first { it.label == "Sky" }, rows.first { it.label == "#sun" }, Drop.INTO)
        assertTrue(result.isFailure)
        assertEquals("an element can't move into itself", result.exceptionOrNull()?.reason)
        assertTrue(d.state.value.pending.isEmpty())
    }

    @Test
    fun badRemoteAddressesAreRefused() = runTest {
        val d = Document(Files.createTempDirectory("xtxp").toString(), StandardTestDispatcher(testScheduler))
        assertTrue(d.addRemote("not an address").isFailure)
        assertTrue(d.addRemote("http://localhost:8787/demo@main").isSuccess)
        assertEquals(2, d.state.value.remotes.size)
        d.removeRemote("xtxp-worker.campey.workers.dev/xtxp-logo@main")
        assertEquals(listOf("localhost:8787"), d.state.value.remotes.map { it.host })
        assertNull(d.state.value.remotes.single().lastSyncMs)
    }

    @Test
    fun draggingOnTheDrawingTranslatesTheElement() = runTest {
        val d = Document(Files.createTempDirectory("xtxp").toString(), StandardTestDispatcher(testScheduler))
        d.startWithSample()
        val sun = d.state.value.rows.first { it.label == "#sun" }
        assertTrue(d.state.value.svg.contains("data-xtxp-node=\"${sun.key}\""))
        assertTrue(d.translate(sun.key, 4.0, -1.5).isSuccess)
        assertTrue(d.canonical().contains("transform=\"translate(4 -1.5)\""), d.canonical())
        assertEquals("Dragged <circle #sun> by (4, -2)", d.state.value.pending.single().summary)
        assertTrue(d.translate("nonsense", 1.0, 1.0).isFailure)
    }
}

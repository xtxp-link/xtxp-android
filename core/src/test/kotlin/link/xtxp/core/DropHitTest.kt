package link.xtxp.core

import uniffi.xtxp_mobile.Drop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DropHitTest {
    private val slots = (0 until 4).map { RowSlot(index = it + 10, top = it * 100 - 30, height = 100) }

    @Test
    fun quartersPickBeforeIntoAndAfter() {
        assertEquals(Hit(11, Drop.BEFORE), hitTest(75f, slots, dragged = 10))
        assertEquals(Hit(11, Drop.INTO), hitTest(120f, slots, dragged = 10))
        assertEquals(Hit(11, Drop.AFTER), hitTest(149f, slots, dragged = 10))
    }

    @Test
    fun theDraggedRowAndEmptySpaceLandNowhere() {
        assertNull(hitTest(0f, slots, dragged = 10))
        assertNull(hitTest(500f, slots, dragged = 10))
    }

    @Test
    fun theBarSaysWhatEachPartDoes() {
        val online = SplitBar(offline = false, pending = 0, syncing = false, remotes = 1, failing = false)
        assertEquals(SplitBar.Glyph.BACK, online.left)
        assertEquals("Merge", online.plusLabel)
        val offline = online.copy(offline = true, pending = 3)
        assertEquals(SplitBar.Glyph.PAUSE, offline.left)
        assertEquals("3 changes", offline.plusLabel)
        assertEquals(false, offline.copy(syncing = true).plusEnabled)
        assertEquals(false, online.copy(remotes = 0).plusEnabled)
    }
}

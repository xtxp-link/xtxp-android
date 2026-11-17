package link.xtxp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitBarTest {
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

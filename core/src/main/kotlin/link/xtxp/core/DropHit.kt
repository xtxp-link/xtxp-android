package link.xtxp.core

import uniffi.xtxp_mobile.Drop

/** A row as the list laid it out: its index, top and height, in list pixels. */
data class RowSlot(val index: Int, val top: Int, val height: Int)

/** Where a dragged row would land if it were let go now. */
data class Hit(val index: Int, val drop: Drop)

/**
 * The row under [pointerY] and which part of it: the top quarter drops
 * before it, the bottom quarter after it, and the middle into it as its last
 * child. A row can't take itself, so dropping on [dragged] lands nowhere.
 */
fun hitTest(pointerY: Float, slots: List<RowSlot>, dragged: Int): Hit? {
    val slot = slots.firstOrNull { pointerY >= it.top && pointerY < it.top + it.height } ?: return null
    if (slot.index == dragged) return null
    val within = (pointerY - slot.top) / slot.height
    val drop = when {
        within < 0.25f -> Drop.BEFORE
        within >= 0.75f -> Drop.AFTER
        else -> Drop.INTO
    }
    return Hit(slot.index, drop)
}

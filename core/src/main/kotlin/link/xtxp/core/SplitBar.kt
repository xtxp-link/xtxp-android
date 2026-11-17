package link.xtxp.core

/**
 * What the full-width `< + >` bar shows.
 *
 * - `<` takes the phone's session offline: no merging either way. While
 *   offline it shows a pause sign, and tapping it again comes back online.
 * - `+` merges with the remotes now, both ways, online or offline. It shows
 *   how many local changes are waiting.
 * - `>` opens the remotes: the sessions this one trusts.
 */
data class SplitBar(
    val offline: Boolean,
    val pending: Int,
    val syncing: Boolean,
    val remotes: Int,
    val failing: Boolean,
) {
    val left: Glyph get() = if (offline) Glyph.PAUSE else Glyph.BACK
    val leftLabel: String get() = if (offline) "Offline" else "Online"
    val leftDescription: String
        get() = if (offline) "Offline. Tap to go back online." else "Online. Tap to go offline."

    val plusEnabled: Boolean get() = !syncing && remotes > 0
    val plusLabel: String
        get() = when {
            syncing -> "Merging…"
            pending == 1 -> "1 change"
            pending > 1 -> "$pending changes"
            else -> "Merge"
        }

    val rightLabel: String get() = if (remotes == 1) "1 remote" else "$remotes remotes"

    enum class Glyph { BACK, PAUSE }
}

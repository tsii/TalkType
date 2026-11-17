package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V0.1.54b (PLAN_HOSTING): the U2-card button matrix
 * [SetupActivity.u2CardActions] as a PURE function — every cell pinned.
 * The U2 TEXT sets have NO select button (never the active dictation set),
 * so the matrix is: download (offered) / delete (READY) / nothing
 * (RAM-locked, space-locked, importing).
 */
class U2CardActionsTest {

    private val D = ButtonKind.DOWNLOAD
    private val X = ButtonKind.DELETE

    private fun actions(locked: Boolean, ready: Boolean, importing: Boolean, offerable: Boolean) =
        u2CardActions(locked = locked, ready = ready, importing = importing, offerable = offerable)

    @Test
    fun `RAM below 8 GB offers no download button`() {
        assertEquals(emptySet<ButtonKind>(), actions(locked = true, ready = false, importing = false, offerable = true))
        assertEquals(emptySet<ButtonKind>(), actions(locked = true, ready = false, importing = false, offerable = false))
    }

    @Test
    fun `RAM ok and space ok offers the download`() {
        assertEquals(setOf(D), actions(locked = false, ready = false, importing = false, offerable = true))
    }

    @Test
    fun `READY keeps delete only - no download, no re-download promise`() {
        assertEquals(setOf(X), actions(locked = false, ready = true, importing = false, offerable = true))
        assertEquals(setOf(X), actions(locked = false, ready = true, importing = false, offerable = false))
    }

    @Test
    fun `importing hides every button`() {
        assertEquals(emptySet<ButtonKind>(), actions(locked = false, ready = false, importing = true, offerable = true))
        assertEquals(emptySet<ButtonKind>(), actions(locked = false, ready = true, importing = true, offerable = false))
    }

    @Test
    fun `RAM ok but space floor fails offers nothing`() {
        assertEquals(emptySet<ButtonKind>(), actions(locked = false, ready = false, importing = false, offerable = false))
    }
}

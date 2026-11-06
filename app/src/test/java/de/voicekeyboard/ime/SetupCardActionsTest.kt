package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C2 (Oracle §20b, orphaned-delete fix): the model-card button matrix
 * [SetupActivity.cardActions] as a PURE function — every cell of
 * locked x ready x active pinned. The C2 rule: a LOCKED set with READY
 * files keeps SELECT+DELETE, so the base-URL retreat (BASE_URL_QWEN = "",
 * VK-2) can never orphan the loaded ~1.7 GB with no UI way to free them.
 */
class SetupCardActionsTest {

    private val S = ButtonKind.SELECT
    private val D = ButtonKind.DOWNLOAD
    private val X = ButtonKind.DELETE

    private fun actions(locked: Boolean, ready: Boolean, active: Boolean, offerable: Boolean = true) =
        cardActions(locked = locked, ready = ready, active = active, offerable = offerable)

    // ---- C2: locked + READY keeps select/delete (orphaned-delete fix) ----

    @Test
    fun `C2 locked and ready and non-active keeps select and delete`() {
        // the Qwen case today: BASE_URL_QWEN empty (locked) but files on disk
        assertEquals(setOf(S, X), actions(locked = true, ready = true, active = false))
    }

    @Test
    fun `locked and ready and ACTIVE offers nothing - delete never on the active set`() {
        assertEquals(emptySet<ButtonKind>(), actions(locked = true, ready = true, active = true))
    }

    // ---- full matrix ----

    @Test
    fun `ready and non-active and not locked keeps select and delete`() {
        assertEquals(setOf(S, X), actions(locked = false, ready = true, active = false))
    }

    @Test
    fun `ready and active offers nothing`() {
        assertEquals(emptySet<ButtonKind>(), actions(locked = false, ready = true, active = true))
    }

    @Test
    fun `locked and not ready offers nothing - neither download nor delete`() {
        assertEquals(emptySet<ButtonKind>(), actions(locked = true, ready = false, active = false))
    }

    @Test
    fun `not locked and not ready and offerable offers download only`() {
        assertEquals(setOf(D), actions(locked = false, ready = false, active = false))
    }

    @Test
    fun `not locked and not ready and space-blocked offers nothing`() {
        assertEquals(emptySet<ButtonKind>(), actions(locked = false, ready = false, active = false, offerable = false))
    }

    @Test
    fun `active but broken and offerable still offers re-download`() {
        // semantics of the old render: active only gates the READY card
        assertEquals(setOf(D), actions(locked = false, ready = false, active = true))
    }
}

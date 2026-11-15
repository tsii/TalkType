package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V0.1.52 (user finding): in a letter overlay that reaches a DIGIT, the digit has
 * to sit as close as possible to the finger (the menu is centred over the key, so
 * that is the middle cell). Every other character stays in its relative order —
 * the function is a pure permutation, nothing is added or dropped.
 */
class LongPressDigitNearestTest {

    @Test
    fun `odd cell count - digit moves into the exact middle slot`() {
        // 'e' in de-de: é è ê ë ę € + hint 3 => 7 cells, middle index = 3
        val cells = LongPressMenu.withHint(
            listOf("é", "è", "ê", "ë", "ę", "€"), "3", LongPressMenu.MAX_CELLS,
        )
        assertEquals(listOf("é", "è", "ê", "ë", "ę", "€", "3"), cells)
        assertEquals(
            listOf("é", "è", "ê", "3", "ë", "ę", "€"),
            LongPressMenu.digitNearestToFinger(cells, "3"),
        )
    }

    @Test
    fun `even cell count - digit takes one of the two centre slots`() {
        val cells = listOf("ü", "ú", "ù", "û", "7")           // 5 cells
        assertEquals(
            listOf("ü", "ú", "7", "ù", "û"),
            LongPressMenu.digitNearestToFinger(cells, "7"),
        )
        val four = listOf("ä", "á", "à", "4")                  // 4 cells -> index 1
        assertEquals(
            listOf("ä", "4", "á", "à"),
            LongPressMenu.digitNearestToFinger(four, "4"),
        )
    }

    @Test
    fun `single alternate plus digit keeps working and stays a permutation`() {
        val cells = listOf("x", "9")
        assertEquals(listOf("x", "9"), LongPressMenu.digitNearestToFinger(cells, "9"))
    }

    @Test
    fun `no digit and no hint - list untouched`() {
        val cells = listOf("é", "è", "ê")
        assertEquals(cells, LongPressMenu.digitNearestToFinger(cells, null))
        assertEquals(cells, LongPressMenu.digitNearestToFinger(cells, " "))
        assertEquals(cells, LongPressMenu.digitNearestToFinger(cells, "5"))
    }

    @Test
    fun `inventory and cell count are preserved - pure permutation`() {
        val cells = LongPressMenu.withHint(listOf("í", "ì", "î", "ï"), "8", LongPressMenu.MAX_CELLS)
        val moved = LongPressMenu.digitNearestToFinger(cells, "8")
        assertEquals(cells.size, moved.size)
        assertEquals(cells.sorted(), moved.sorted())
        assertEquals("centre slot holds the digit", "8", moved[(moved.size - 1) / 2])
    }
}

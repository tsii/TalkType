package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E7-Fallback-Kaskade als reine JVM-Tests (kein Robolectric, keine
 * Android-Simulation): Modus-Sequenz TYPED → UNTYPED → ABORT und das
 * „beide Versuche fehlgeschlagen → abbrechen"-Verhalten.
 */
class FgsStartFallbackTest {

    @Test fun firstAttemptIsTyped() {
        assertEquals(ForegroundStartPolicy.Mode.TYPED, ForegroundStartPolicy.firstAttempt)
    }

    @Test fun typedFailureFallsBackToUntyped() {
        assertEquals(
            ForegroundStartPolicy.Mode.UNTYPED,
            ForegroundStartPolicy.nextAfter(ForegroundStartPolicy.Mode.TYPED),
        )
    }

    @Test fun secondFailureAborts() {
        assertEquals(
            ForegroundStartPolicy.Mode.ABORT,
            ForegroundStartPolicy.nextAfter(ForegroundStartPolicy.Mode.UNTYPED),
        )
    }

    @Test fun fullSequenceIsTypedUntypedAbort() {
        var mode = ForegroundStartPolicy.firstAttempt
        val sequence = mutableListOf(mode)
        while (!ForegroundStartPolicy.isAbort(mode)) {
            mode = ForegroundStartPolicy.nextAfter(mode)
            sequence += mode
        }
        assertEquals(
            listOf(
                ForegroundStartPolicy.Mode.TYPED,
                ForegroundStartPolicy.Mode.UNTYPED,
                ForegroundStartPolicy.Mode.ABORT,
            ),
            sequence,
        )
    }

    @Test fun bothFailuresLeadToAbort() {
        val first = ForegroundStartPolicy.firstAttempt
        val second = ForegroundStartPolicy.nextAfter(first)
        val third = ForegroundStartPolicy.nextAfter(second)
        assertTrue("both failed -> abort", ForegroundStartPolicy.isAbort(third))
    }

    @Test fun abortIsTerminal() {
        // Nach ABORT gibt es keinen weiteren Versuch — isAbort bleibt true
        val abort = ForegroundStartPolicy.nextAfter(ForegroundStartPolicy.Mode.UNTYPED)
        assertTrue(ForegroundStartPolicy.isAbort(abort))
        assertFalse(ForegroundStartPolicy.isAbort(ForegroundStartPolicy.Mode.TYPED))
        assertFalse(ForegroundStartPolicy.isAbort(ForegroundStartPolicy.Mode.UNTYPED))
    }
}

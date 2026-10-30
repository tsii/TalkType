package de.voicekeyboard.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAgreementTest {

    // (a) 2 gleiche Hypothesen → Präfix freigegeben; 1 Hypothese → nichts

    @Test fun singleHypothesisYieldsNothing() {
        val la = LocalAgreement()
        assertNull(la.accept("So, da bin ich", 16_000))
    }

    @Test fun twoAgreeingHypothesesReleasePrefix() {
        val la = LocalAgreement()
        la.accept("So, da bin ich", 16_000)
        // Trenner nach „ich“ liegt AUSSERHALB des gemeinsamen Präfixes (h2 wächst
        // mit ‚ mal‘) ⇒ committet nur „So, da bin “ (COMMIT_RULE_DECISION T-Fall)
        assertEquals("So, da bin ", la.accept("So, da bin ich mal", 32_000))
    }

    // (b) Wortgrenze: „Das ist der“ vs „Das ist derzw“ → nur „Das ist “ (strenge Lesart)

    @Test fun wordBoundaryDoesNotCommitPartialWord() {
        val la = LocalAgreement()
        la.accept("Das ist der", 16_000)
        // „der“ ist ein unbestätigtes Wort an der Audio-Kante — es bleibt Tail
        assertEquals("Das ist ", la.accept("Das ist derzw", 32_000))
    }

    @Test fun growingWordCutsBackToLastSpace() {
        val la = LocalAgreement()
        la.accept("Das ist derzwo", 16_000) // Vorlauf: Historie füllen
        // gemeinsamer Präfix "Das ist derzw" endet mitten im Wort (beide Hypothesen
        // führen dort mit anderem Buchstaben weiter) → nur bis zum letzten Leerzeichen
        assertEquals("Das ist ", la.accept("Das ist derzwei", 32_000))
    }

    @Test fun contradictoryPrefixYieldsNothing() {
        val la = LocalAgreement()
        la.accept("abc def", 16_000)
        assertNull(la.accept("xyz", 32_000))
    }

    @Test fun sameAudioStateDoesNotCountTwice() {
        val la = LocalAgreement()
        la.accept("So ist", 16_000)
        assertNull(la.accept("So ist", 16_000)) // derselbe Snapshot ist keine 2. Bestätigung
        // „ist“ bleibt Tail (kein belegter Trenner dahinter im gemeinsamen Präfix)
        assertEquals("So ", la.accept("So ist es", 32_000))
    }

    // (a) S4-01-Gegenprobe: zwei identische Mid-Word-Truncationen committen NIE
    //     das angebrochene Wort — „derz“ ist an der Audio-Kante nicht beweisbar.

    @Test fun identicalMidWordTruncationsCommitNoHalfWord() {
        val la = LocalAgreement()
        la.accept("Das ist derz", 16_000)
        val result = la.accept("Das ist derz", 32_000)
        // alte Lesart (endsAtWordBoundary) hätte „Das ist derz“ komplett gefroren
        assertEquals("Das ist ", result)
        // Invariante: committeter Präfix endet immer auf Leerzeichen
        assertTrue(result!!.endsWith(" "))
        assertFalse(result.contains("derz"))
    }

    // (b) „Das ist der “ (mit Leerzeichen): Commit bis einschließlich Leerzeichen

    @Test fun trailingSpaceInsidePrefixIsCommitted() {
        val la = LocalAgreement()
        la.accept("Das ist der ", 16_000)
        // der Trenner NACH „der“ liegt IM gemeinsamen Präfix ⇒ „der“ ist belegt
        assertEquals("Das ist der ", la.accept("Das ist der Zwiebel", 32_000))
    }

    // (c) S4-03-Gegenprobe: Divergenz INNERHALB eines Surrogatpaars

    @Test fun surrogateDivergenceCutsBeforeThePair() {
        val la = LocalAgreement()
        la.accept("Hallo \uD83E\uDD16", 16_000)
        val result = la.accept("Hallo \uD83E\uDD17", 32_000)
        // gemeinsamer Präfix endet auf dem High-Surrogate → Grenze Rücknahme
        // ⇒ kein einsames High-Surrogat, Schnitt am Leerzeichen
        assertEquals("Hallo ", result)
        assertTrue(result!!.endsWith(" "))
        assertFalse(Character.isHighSurrogate(result.last()))
    }

    @Test fun committedPrefixInvarianteAlwaysEndsWithSpaceOrNull() {
        val la = LocalAgreement()
        val texts = listOf(
            "So, da bin", "So, da bin ich", "So, da bin ich mal", "So, da bin ich mal gespannt",
        )
        var audio = 16_000L
        for (t in texts) {
            val r = la.accept(t, audio)
            if (r != null) assertTrue("Präfix muss auf Leerzeichen enden: ‚$r‘", r.endsWith(" "))
            audio += 16_000
        }
    }

    @Test fun resetRequiresTwoAgain() {
        val la = LocalAgreement()
        la.accept("Eins zwei", 16_000)
        la.accept("Eins zwei drei", 32_000)
        la.reset()
        assertNull(la.accept("Eins zwei", 48_000))
        assertEquals("Eins ", la.accept("Eins zwei drei", 64_000))
    }

    @Test fun unicodeSurrogatePairStaysIntact() {
        val la = LocalAgreement()
        la.accept("Schöne \uD83E\uDD16 Tastatur", 16_000)
        // Schnitt am Leerzeichen vor dem wachsenden Wort; \uD83E\uDD16 bleibt intakt
        assertEquals("Schöne \uD83E\uDD16 ", la.accept("Schöne \uD83E\uDD16 Tastaturen", 32_000))
    }
}

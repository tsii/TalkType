package de.voicekeyboard.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitPolicyTest {

    @Test fun singleHypothesisCommitsNothing() {
        val p = CommitPolicy()
        assertEquals(0, p.onHypothesis("Hallo Welt", 16_000, 0, budgetExceeded = false))
        assertEquals(0, p.onHypothesis("Hallo Welt", 32_000, 0, budgetExceeded = false)) // C1: N=3
    }

    @Test fun twoAgreeingHypothesesDoNotCommitAnymore() {
        // E8.2 C1 (N=3): 2 Übereinstimmungen committen NICHTS mehr — der
        // gemessene „2× gleich falsch"-Fall wird getötet.
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false))
        // erst die DRITE Übereinstimmung gibt frei — C2 hält die letzten 2 Wörter
        val c = p.onHypothesis("Hallo Welt ABC", 48_000, 0, budgetExceeded = false)
        assertEquals(6, c) // „Hallo " — Welt/ABC bleiben ungeschützt (C2)
    }

    @Test fun wordBoundaryNoHalfWord() {
        val p = CommitPolicy()
        p.onHypothesis("Das ist der erste", 16_000, 0, budgetExceeded = false)
        p.onHypothesis("Das ist der erste", 32_000, 0, budgetExceeded = false)
        // „derzw“ wird NICHT committet; auch „der“ bleibt Tail (Audio-Kante);
        // C2 hält die letzten 2 Wörter ⇒ committet nur „Das ist „-Bereich bis
        // zur Wortgrenze des freigegebenen Rests
        val c = p.onHypothesis("Das ist der erste", 48_000, 0, budgetExceeded = false)
        assertEquals(8, c) // „Das ist “ — C2 hält „der“, „erste“
    }

    @Test fun committedPrefixNeverShrinksFromPolicy() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)
        val committed = p.onHypothesis("Hallo Welt ABC", 48_000, 0, budgetExceeded = false)
        assertEquals(6, committed) // „Hallo “ — C2 hält Welt/ABC
        // kürzere/widersprüchliche Hypothese darf den Präfix nie schrumpfen lassen
        assertEquals(6, p.onHypothesis("Hallo", 64_000, committed, budgetExceeded = false))
    }

    @Test fun sameAudioStateDoesNotAdvance() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false))
        p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)) // C1: N=3
    }

    @Test fun budgetExceededBlocksNewCommits() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        // Token-Budget verbraucht ⇒ Hypothese evtl. abgeschnitten ⇒ nichts Neues committen
        assertEquals(0, p.onHypothesis("Hallo Welt ABC!", 32_000, 0, budgetExceeded = true))
    }

    @Test fun streamingProgressively() {
        val p = CommitPolicy()
        // E8.2: N=3 Übereinstimmungen + C2 (letzte 2 Wörter halten)
        var c = p.onHypothesis("So, da bin ich mal gespannt", 16_000, 0, budgetExceeded = false)
        assertEquals(0, c) // 1. Hypothese
        c = p.onHypothesis("So, da bin ich mal gespannt", 32_000, c, budgetExceeded = false)
        assertEquals(0, c) // 2. — C1: N=3 noch nicht erreicht
        c = p.onHypothesis("So, da bin ich mal gespannt", 48_000, c, budgetExceeded = false)
        assertEquals(15, c) // 3. — C2 hält „mal“, „gespannt“ ⇒ „So, da bin ich “
        c = p.onHypothesis("So, da bin ich mal gespannt, ob", 64_000, c, budgetExceeded = false)
        assertEquals(15, c) // Agreement zählt neu (divergent) — Hold bleibt 15
        c = p.onHypothesis("So, da bin ich mal gespannt, ob", 80_000, c, budgetExceeded = false)
        assertEquals(15, c) // 2. Übereinstimmung — kein Fortschritt (N=3)
        c = p.onHypothesis("So, da bin ich mal gespannt, ob", 96_000, c, budgetExceeded = false)
        assertEquals(19, c) // 3. — „So, da bin ich mal “ (C2 hält „gespannt,“, „ob“)
    }

    @Test fun committablePrefixAlwaysEndsWithSpace() {
        // Invariante aus COMMIT_RULE_DECISION: jede Commit-Länge > 0 zeigt auf ein
        // Leerzeichen — nie in ein Wort hinein.
        val p = CommitPolicy()
        val hyps = listOf(
            "So, da bin ich" to 16_000L,
            "So, da bin ich mal" to 32_000L,
            "So, da bin ich mal gespannt" to 48_000L,
            "So, da bin ich mal gespannt." to 64_000L,
        )
        var c = 0
        for ((text, audio) in hyps) {
            c = p.onHypothesis(text, audio, c, budgetExceeded = false)
            assertTrue("Länge $c muss 0 oder auf Leerzeichen zeigen", c == 0 || text[c - 1] == ' ')
        }
        // E8.2: Fenster-3-Präfix wächst mit den Hypothesen; C2 hält jeweils die
        // letzten 2 Wörter. Nach [t2,t3,t4] ist der Präfix „So, da bin ich mal“,
        // committet „So, da bin “.
        assertEquals(11, c) // „So, da bin “ (C2 hält „ich“, „mal“)
    }

    @Test fun resetRestartsAgreement() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)
        p.onHypothesis("Hallo Welt ABC", 48_000, 0, budgetExceeded = false)
        p.reset()
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 64_000, 0, budgetExceeded = false)) // C1 neu
    }
}

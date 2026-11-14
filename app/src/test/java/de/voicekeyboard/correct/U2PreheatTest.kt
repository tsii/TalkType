package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T24d (PLAN_V0147 §17.1, Gleis 3 B4): U2 preheat state machine —
 * IDLE/LOADING/READY/UNLOADING; trigger = first text-committing key of the
 * session (NOT Enter, NOT the dictation key — the service feeds those);
 * RAM guard >= 400 MB freeBytes with 3 retries then session give-up; never
 * starts while dictation is non-IDLE or the ASR engine is mid-load; UNLOAD
 * after 60 s idle or on an ineligible field. All seams faked (pure JVM).
 * Counters/codes only (rule 9).
 */
class U2PreheatTest {

    private class Harness(
        freeBytes: Long = 8L * 1024 * 1024 * 1024,
        downloaded: Boolean = true,
        eligible: Boolean = true,
        switchOn: Boolean = true,
        dictationIdle: Boolean = true,
        asrLoading: Boolean = false,
    ) {
        var loadCalls = 0
        var unloadCalls = 0
        var loadFails = false
        val asyncRuns = ArrayList<() -> Unit>()
        val mainPosts = ArrayList<() -> Unit>()
        val idleArms = ArrayList<Pair<Long, () -> Unit>>()
        var idleCancelled = 0

        val preheat = U2Preheat(
            loadAndWarmUp = { loadCalls++; if (loadFails) error("boom") },
            unload = { unloadCalls++ },
            freeBytes = { freeBytes },
            setDownloaded = { downloaded },
            eligibleField = { eligible },
            correctionSwitchOn = { switchOn },
            dictationIdle = { dictationIdle },
            asrLoading = { asrLoading },
            postToMain = { mainPosts.add(it) },
            runAsync = { asyncRuns.add(it) },
            scheduleIdleUnload = { ms, run -> idleArms.add(ms to run) },
            cancelIdleUnload = { idleCancelled++ },
        )

        /** Drives the async worker + main hops synchronously. */
        fun pump() {
            while (asyncRuns.isNotEmpty()) {
                val r = asyncRuns.removeAt(0)
                r()
            }
            while (mainPosts.isNotEmpty()) {
                val r = mainPosts.removeAt(0)
                r()
            }
        }

        fun fireIdle() {
            val (_, run) = idleArms.removeAt(idleArms.size - 1)
            run()
            pump()
        }
    }

    // ------------------------------------------------------------- happy path

    @Test fun `first text key loads and warms up - READY`() {
        val h = Harness()
        assertEquals(U2Preheat.State.IDLE, h.preheat.state)
        h.preheat.onTextKey()
        assertEquals("LOADING after the trigger (scheduled on the worker)", U2Preheat.State.LOADING, h.preheat.state)
        h.pump()
        assertEquals("one load+warm-up on the worker", 1, h.loadCalls)
        assertEquals("READY after the warm-up", U2Preheat.State.READY, h.preheat.state)
        assertTrue(h.preheat.isReady)
        assertTrue("60 s idle unload armed", h.idleArms.any { it.first == 60_000L })
    }

    @Test fun `repeated keys never double-load`() {
        val h = Harness()
        h.preheat.onTextKey()
        h.pump()
        h.preheat.onTextKey()
        h.preheat.onTextKey()
        assertEquals("still one load", 1, h.loadCalls)
        // activity re-arms the idle timer
        assertTrue("idle timer re-armed on activity", h.idleArms.size >= 2)
    }

    // ------------------------------------------------------------- start gates

    @Test fun `download missing or switch off or ineligible - no start`() {
        for (h in listOf(
            Harness(downloaded = false),
            Harness(switchOn = false),
            Harness(eligible = false),
        )) {
            h.preheat.onTextKey()
            h.pump()
            assertEquals("no load without preconditions", 0, h.loadCalls)
            assertEquals(U2Preheat.State.IDLE, h.preheat.state)
        }
    }

    @Test fun `running dictation or mid-load ASR - never starts`() {
        for (h in listOf(
            Harness(dictationIdle = false),
            Harness(asrLoading = true),
        )) {
            h.preheat.onTextKey()
            h.pump()
            assertEquals("blocked while dictation/ASR busy", 0, h.loadCalls)
            assertEquals(U2Preheat.State.IDLE, h.preheat.state)
        }
    }

    // ------------------------------------------------------------- RAM guard

    @Test fun `RAM below 400 MB - retry up to 3 then session give-up`() {
        val h = Harness(freeBytes = 100L * 1024 * 1024)
        h.preheat.onTextKey()
        h.preheat.onTextKey()
        h.preheat.onTextKey()
        assertEquals("three refused attempts, no load", 0, h.loadCalls)
        assertEquals(3, h.preheat.ramRefusals)
        h.preheat.onTextKey()
        assertEquals("give-up: further keys do not even count", 3, h.preheat.ramRefusals)
        assertEquals(U2Preheat.State.IDLE, h.preheat.state)
        // next SESSION resets the budget AND the counter
        h.preheat.onSessionStart()
        h.preheat.onTextKey()
        assertEquals("budget+counter reset per session", 1, h.preheat.ramRefusals)
    }

    // ------------------------------------------------------------- unload paths

    @Test fun `idle timer unloads the engine`() {
        val h = Harness()
        h.preheat.onTextKey()
        h.pump()
        h.fireIdle()
        assertEquals("engine released", 1, h.unloadCalls)
        assertEquals(U2Preheat.State.IDLE, h.preheat.state)
        assertFalse(h.preheat.isReady)
    }

    @Test fun `ineligible field unloads immediately`() {
        val h = Harness()
        h.preheat.onTextKey()
        h.pump()
        h.preheat.onIneligibleField()
        h.pump()
        assertEquals("unload on ineligible field", 1, h.unloadCalls)
        assertEquals(U2Preheat.State.IDLE, h.preheat.state)
    }

    @Test fun `load failure returns to IDLE - retry on the next key`() {
        val h = Harness()
        h.loadFails = true
        h.preheat.onTextKey()
        h.pump()
        assertEquals("failed load back to IDLE", U2Preheat.State.IDLE, h.preheat.state)
        assertEquals("no ready despite key", false, h.preheat.isReady)
        h.loadFails = false
        h.preheat.onTextKey()
        h.pump()
        assertEquals("second attempt succeeds", U2Preheat.State.READY, h.preheat.state)
    }

    @Test fun `shutdown unloads a resident engine`() {
        val h = Harness()
        h.preheat.onTextKey()
        h.pump()
        h.preheat.shutdown()
        h.pump()
        assertEquals(1, h.unloadCalls)
        assertEquals(U2Preheat.State.IDLE, h.preheat.state)
    }
}

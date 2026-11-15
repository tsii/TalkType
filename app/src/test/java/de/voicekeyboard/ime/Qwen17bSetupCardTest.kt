package de.voicekeyboard.ime

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V0.1.53 (PLAN_QWEN17B §B, S4-precursor): the "Qwen3-ASR 1.7B" Setup card —
 * VISIBLE with an honest locked explanation while the set is inert (empty
 * pinned manifest, no sha256, BASE_URL_QWEN_17B empty), and NO buttons at
 * all: no download, no import, no selection. Parakeet stays the standard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Qwen17bSetupCardTest {

    private fun buildActivity(): SetupActivity {
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().totalRamOverride = 16L * 1024 * 1024 * 1024
        controller.setup()
        controller.get().awaitSetupIo()
        return controller.get()
    }

    private fun qwen17bCard(activity: SetupActivity): ViewGroup {
        val root = activity.findViewById<TextView>(de.voicekeyboard.R.id.setup_title).parent as ViewGroup
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ViewGroup && hasTextDeep(c, activity.getString(de.voicekeyboard.R.string.model_qwen17b_title))) {
                return c
            }
        }
        throw AssertionError("1.7B card not found")
    }

    private fun hasTextDeep(v: View, needle: String): Boolean {
        if (v is TextView && v.text.toString().contains(needle)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasTextDeep(v.getChildAt(i), needle)) return true
        return false
    }

    private fun collectButtons(v: View, out: MutableList<Button>) {
        if (v is Button) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectButtons(v.getChildAt(i), out)
    }

    @Test fun `card is visible with the honest locked explanation`() {
        val card = qwen17bCard(buildActivity())
        val texts = buildList { collectTexts(card, this) }.joinToString("\n")
        assertTrue("title", texts.contains("Qwen3-ASR 1.7B"))
        assertTrue("honest state", texts.contains("not available yet"))
        assertTrue("download risk from the plan", texts.contains("4 GB"))
        assertTrue("RAM peak risk from the plan", texts.contains("5.5–6.5 GB"))
        assertTrue("Parakeet stays the standard", texts.contains("Parakeet"))
    }

    @Test fun `locked card has NO download import or select button`() {
        val card = qwen17bCard(buildActivity())
        val buttons = ArrayList<Button>()
        collectButtons(card, buttons)
        assertTrue("no buttons at all while the set is inert", buttons.isEmpty())
        // and the pure policy refuses the set (defense in depth):
        assertFalse(
            "canOfferSet false while inert",
            de.voicekeyboard.model.DownloadPolicy.canOfferSet(
                de.voicekeyboard.model.ModelVariant.QWEN3_ASR_17B,
                totalRamBytes = 16L * 1024 * 1024 * 1024,
                freeBytes = 64L * 1024 * 1024 * 1024,
            ),
        )
    }

    @Test fun `1_7B is never preselected - the active variant pref stays untouched`() {
        val activity = buildActivity()
        val pref = activity
            .getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(de.voicekeyboard.model.ModelVariant.PREFS_KEY, null)
        assertEquals(
            "no select path: the pref resolves to the DEFAULT (A)",
            de.voicekeyboard.model.ModelVariant.DEFAULT,
            de.voicekeyboard.model.ModelVariant.fromId(pref),
        )
    }

    private fun collectTexts(v: View, out: MutableList<String>) {
        if (v is TextView) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }
}

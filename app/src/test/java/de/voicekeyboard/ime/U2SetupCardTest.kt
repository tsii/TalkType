package de.voicekeyboard.ime

import android.widget.Button
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T24b (PLAN_V0147 §17.2, Gleis 3 B1b): the "Text (U2)" Setup card — third
 * model card, visible in RELEASE, honest inert state while BASE_URL_U2 is
 * empty (Qwen VK-2 pattern), licence + size line, follows the CURRENT typing
 * language set, RAM-gate reason. READY/delete coverage follows with the
 * ModelStore multi-segment Teil (flat-name whitelist today).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class U2SetupCardTest {

    private fun buildActivity(ramBytes: Long? = 8L * 1024 * 1024 * 1024): SetupActivity {
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().totalRamOverride = ramBytes
        controller.setup()
        return controller.get()
    }

    private fun u2CardTexts(activity: SetupActivity): List<String> {
        val root = activity.findViewById<TextView>(de.voicekeyboard.R.id.setup_title).parent as ViewGroup
        // scope: the U2 card's own container (find via its title — the rest of
        // the Setup contains "Download" in OTHER sections, e.g. the model start button)
        var card: ViewGroup = root
        outer@ for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ViewGroup && hasTextDeep(c, activity.getString(de.voicekeyboard.R.string.u2_title))) {
                card = c
                break@outer
            }
        }
        val out = ArrayList<String>()
        collect(card, out)
        return out
    }

    private fun hasTextDeep(v: View, needle: String): Boolean {
        if (v is TextView && v.text.toString().contains(needle)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasTextDeep(v.getChildAt(i), needle)) return true
        return false
    }

    private fun collect(v: View, out: MutableList<String>) {
        if (v is TextView) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    @Test fun `card is visible with title size and licence line`() {
        val texts = u2CardTexts(buildActivity()).joinToString("\n")
        assertTrue("card title", texts.contains("Text correction (U2)"))
        assertTrue("size line en/de", texts.contains("179 MB (en) / 249 MB (de)"))
        assertTrue("licence line", texts.contains("MIT / Apache-2.0"))
        assertTrue("opt-in line", texts.contains("only with your consent"))
    }

    @Test fun `download is never offered while BASE_URL_U2 is empty - honest inert state`() {
        assertEquals("test precondition: host not pinned yet", "", de.voicekeyboard.model.ModelManifest.BASE_URL_U2)
        val activity = buildActivity()
        val texts = u2CardTexts(activity).joinToString("\n")
        assertTrue(
            "honest not-available text (Qwen VK-2 pattern)",
            texts.contains(activity.getString(de.voicekeyboard.R.string.model_not_downloaded)),
        )
        assertFalse("no dead download promise on the card", texts.contains("Download"))
    }

    @Test fun `card follows the CURRENT typing language set`() {
        // de-de current -> German set line
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "de-de")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "de-de")
            .commit()
        val deTexts = u2CardTexts(buildActivity()).joinToString("\n")
        assertTrue("de set shown", deTexts.contains("Set: Deutsch"))

        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "en-us")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "en-us")
            .commit()
        val enTexts = u2CardTexts(buildActivity()).joinToString("\n")
        assertTrue("en set shown", enTexts.contains("Set: English"))
    }

    @Test fun `ram gate shows the honest reason on a low-RAM device`() {
        val activity = buildActivity(ramBytes = 4L * 1024 * 1024 * 1024)
        val texts = u2CardTexts(activity).joinToString("\n")
        assertTrue(
            "RAM-gate reason visible (8 GB floor, Qwen class)",
            texts.contains(activity.getString(de.voicekeyboard.R.string.model_ram_locked)),
        )
    }
}

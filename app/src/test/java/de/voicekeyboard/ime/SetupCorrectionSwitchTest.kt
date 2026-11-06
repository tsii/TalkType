package de.voicekeyboard.ime

import android.view.View
import android.view.ViewGroup
import android.widget.Switch
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
 * T23h (PLAN_V0147 §6): setup section "Typing suggestions" — switch A
 * corr_row_enabled (default ON), switch B corr_lang_mode (default current)
 * with the §14.2 discoverability hint + the word-correction availability line
 * (asset discovery, string reused from T23m). Both switches write the SAME
 * keys the view/service already read (live effect via the suppliers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupCorrectionSwitchTest {

    private fun buildActivity(): SetupActivity =
        Robolectric.buildActivity(SetupActivity::class.java).setup().get()

    private fun switches(activity: SetupActivity): List<Switch> {
        val root = activity.findViewById<TextView>(de.voicekeyboard.R.id.setup_title).parent as ViewGroup
        val out = ArrayList<Switch>()
        collect(root, out)
        return out
    }

    private fun collect(v: View, out: MutableList<Switch>) {
        if (v is Switch) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    private fun texts(activity: SetupActivity): List<String> {
        val root = activity.findViewById<TextView>(de.voicekeyboard.R.id.setup_title).parent as ViewGroup
        val out = ArrayList<String>()
        collectTexts(root, out)
        return out
    }

    private fun collectTexts(v: View, out: MutableList<String>) {
        if (v is TextView && v !is Switch) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }

    private fun pref(activity: SetupActivity) =
        activity.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)

    @Test fun `both switches visible - defaults ON and current`() {
        val activity = buildActivity()
        val switches = switches(activity)
        assertEquals("two switches in the section", 2, switches.size)

        val show = switches.first { it.text.contains("Show suggestions while typing") }
        assertTrue("corr_row_enabled default ON", show.isChecked)

        val all = switches.first { it.text.contains("Correct in all active languages") }
        assertFalse("corr_lang_mode default current (unchecked)", all.isChecked)
    }

    @Test fun `clicks persist the keys the service and view already read`() {
        val activity = buildActivity()
        val switches = switches(activity)
        val show = switches.first { it.text.contains("Show suggestions while typing") }
        val all = switches.first { it.text.contains("Correct in all active languages") }

        show.performClick()
        assertEquals(
            "switch A writes corr_row_enabled",
            false,
            pref(activity).getBoolean(VoiceKeyboardService.PREF_CORR_ROW_ENABLED, true),
        )
        show.performClick()
        assertEquals(
            "switch A writes corr_row_enabled",
            true,
            pref(activity).getBoolean(VoiceKeyboardService.PREF_CORR_ROW_ENABLED, true),
        )

        all.performClick()
        assertEquals(
            "switch B checked writes corr_lang_mode=all",
            de.voicekeyboard.correct.LanguageModePolicy.VALUE_ALL,
            pref(activity).getString(VoiceKeyboardService.PREF_CORR_LANG_MODE, null),
        )
        all.performClick()
        assertEquals(
            "switch B unchecked writes corr_lang_mode=current",
            de.voicekeyboard.correct.LanguageModePolicy.VALUE_CURRENT,
            pref(activity).getString(VoiceKeyboardService.PREF_CORR_LANG_MODE, null),
        )
    }

    @Test fun `section shows title hint and availability line`() {
        val activity = buildActivity()
        val texts = texts(activity).joinToString("\n")
        assertTrue("section title", texts.contains("Typing suggestions"))
        assertTrue(
            "discoverability hint (§14.2)",
            texts.contains(activity.getString(de.voicekeyboard.R.string.setup_typing_lang_hint)),
        )
        // availability line from ASSET DISCOVERY — the SAME string resource in
        // BOTH sections (language + typing): the text is reused, not duplicated
        // as a second constant.
        assertTrue(
            "word-correction availability line",
            texts.contains("Word correction available for: Deutsch, English (US)"),
        )
        assertEquals(
            "the availability text appears in both sections — same resource",
            2,
            texts.split("Word correction available for:").size - 1,
        )
    }
}

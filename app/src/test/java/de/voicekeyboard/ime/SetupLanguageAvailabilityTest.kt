package de.voicekeyboard.ime

import android.widget.CheckBox
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
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config

/**
 * T23m (PLAN_V0147 §16): setup language section under the de+en restriction —
 * 13 greyed rows with the 'coming soon' reason, clickable with a hint (no
 * toggle, no pref write), available rows keep toggling, doc lines, cycle
 * filter + current fallback (pure LanguageCycle/Availability assertions of
 * the service reads).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupLanguageAvailabilityTest {

    private fun buildActivity(): SetupActivity =
        Robolectric.buildActivity(SetupActivity::class.java).setup().get()

    private fun languageBoxes(activity: SetupActivity): List<CheckBox> {
        val root = activity.findViewById<TextView>(de.voicekeyboard.R.id.setup_title).parent as ViewGroup
        val boxes = ArrayList<CheckBox>()
        collectCheckBoxes(root, boxes)
        return boxes
    }

    private fun collectCheckBoxes(v: View, out: MutableList<CheckBox>) {
        if (v is CheckBox) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectCheckBoxes(v.getChildAt(i), out)
    }

    private fun sectionTexts(activity: SetupActivity): List<String> {
        val root = activity.findViewById<TextView>(de.voicekeyboard.R.id.setup_title).parent as ViewGroup
        val texts = ArrayList<String>()
        collectTexts(root, texts)
        return texts
    }

    private fun collectTexts(v: View, out: MutableList<String>) {
        if (v is TextView && v !is CheckBox) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }

    // ------------------------------------------------------------- grey rows

    @Test fun `13 of 15 language rows are greyed with the coming soon reason`() {
        val activity = buildActivity()
        val boxes = languageBoxes(activity)
        assertEquals("15 language rows", 15, boxes.size)
        val greyed = boxes.filter { it.alpha < 1f }
        assertEquals("13 greyed rows", 13, greyed.size)
        assertTrue(
            "every greyed row carries the reason",
            greyed.all { it.text.contains(activity.getString(de.voicekeyboard.R.string.setup_lang_coming_soon)) },
        )
        val available = boxes.filter { it.alpha >= 1f }
        assertEquals("2 available rows", 2, available.size)
        assertTrue("available rows carry no reason", available.all { !it.text.contains("coming soon") })
        assertTrue(
            "available rows are the endonym rows",
            available.any { it.text.contains("Deutsch") } && available.any { it.text.contains("English") },
        )
    }

    @Test fun `click on a grey row shows the hint and does NOT toggle or persist`() {
        // saved selection includes an unavailable language (user choice stays stored)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "de-de,fr-fr,en-us")
            .commit()
        val activity = buildActivity() // the section reads the pref at build time
        val grey = languageBoxes(activity).first { it.text.contains("Français") }
        assertTrue("saved state visible: unavailable stays checked", grey.isChecked)

        grey.performClick()
        assertEquals(
            "hint toast instead of a dead click",
            activity.getString(de.voicekeyboard.R.string.setup_lang_coming_soon),
            ShadowToast.getTextOfLatestToast(),
        )
        assertTrue("no toggle on a grey row", grey.isChecked)
        val saved = activity
            .getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, null)
        assertEquals("fr-fr stays stored (user choice, out of the cycle only)", "de-de,fr-fr,en-us", saved)
    }

    @Test fun `available rows keep toggling and persisting`() {
        val activity = buildActivity()
        val german = languageBoxes(activity).first { it.text.contains("Deutsch") }
        assertTrue("default active: de-de checked", german.isChecked)
        german.performClick()
        assertFalse("toggling an available row works", german.isChecked)
        val saved = activity
            .getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, null)
        assertEquals("en-us stays (never empty)", "en-us", saved)
    }

    // ------------------------------------------------------ section doc lines

    @Test fun `section shows the availability doc lines`() {
        val activity = buildActivity()
        val texts = sectionTexts(activity).joinToString("\n")
        assertTrue(
            "only-available doc line",
            texts.contains(activity.getString(de.voicekeyboard.R.string.setup_lang_only_available)),
        )
        // (4): the word-correction line derives from ASSET DISCOVERY
        // (assets/wordlists/*.bin), not the constant — endonyms rendered.
        assertTrue(
            "word correction line from asset discovery",
            texts.contains("Word correction available for: Deutsch, English (US)"),
        )
    }

    // ----------------------------------------------- cycle filter + fallback

    @Test fun `globe cycle and current language see only available languages`() {
        // service reads: LanguageAvailability.filterActive(parseActive(pref))
        val saved = LanguageCycle.parseActive("de-de,fr-fr,en-us")
        val filtered = de.voicekeyboard.keyboard.LanguageAvailability.filterActive(saved)
        assertEquals(listOf("de-de", "en-us"), filtered)
        // cycle: next of de-de over the FILTERED actives
        assertEquals("en-us", LanguageCycle.nextActiveLanguage(filtered, "de-de"))
        assertEquals("de-de", LanguageCycle.nextActiveLanguage(filtered, "en-us"))
        // current_language=fr-fr falls back to the first available (§16)
        assertEquals("de-de", LanguageCycle.initialLanguage(filtered, "fr-fr"))
        // only unavailable actives -> fallback order, cycle works on it
        val onlyFr = de.voicekeyboard.keyboard.LanguageAvailability.filterActive(listOf("fr-fr"))
        assertEquals(listOf("de-de", "en-us"), onlyFr)
        assertEquals("de-de", de.voicekeyboard.keyboard.LanguageAvailability.firstAvailable(onlyFr))
    }
}

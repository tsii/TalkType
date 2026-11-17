package de.voicekeyboard.ime

import android.widget.Button
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import de.voicekeyboard.R
import de.voicekeyboard.model.ModelFile
import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelVariant
import de.voicekeyboard.model.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * T24b (PLAN_V0147 §17.2, Gleis 3 B1b): the "Text (U2)" Setup card — third
 * model card, visible in RELEASE, honest inert state while BASE_URL_U2_EN/_DE are
 * empty (Qwen VK-2 pattern), licence + size line, follows the CURRENT typing
 * language set, RAM-gate reason. READY/delete coverage follows with the
 * ModelStore multi-segment Teil (flat-name whitelist today).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class U2SetupCardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fixtureA = byteArrayOf(1, 2, 3)
    private val fixtureB = byteArrayOf(9, 8)

    /** Flat mini manifest (fixture) — activation of a READY U2 set for the UI. */
    private fun miniManifest(): List<ModelFile> = listOf(
        ModelFile("model.bin", fixtureA.size.toLong(), Sha256.ofBytes(fixtureA)),
        ModelFile("vocab.txt", fixtureB.size.toLong(), Sha256.ofBytes(fixtureB)),
    )

    private fun storeWithReadyU2(): ModelStore {
        val store = ModelStore(tmp.newFolder(), miniManifest())
        val staging = store.newStagingDir()
        File(staging, "model.bin").writeBytes(fixtureA)
        File(staging, "vocab.txt").writeBytes(fixtureB)
        assertTrue("fixture activation expected", store.activate(staging, ModelVariant.BART_SPELLING_EN))
        return store
    }

    private fun buildActivity(
        ramBytes: Long? = 8L * 1024 * 1024 * 1024,
        freeBytes: Long? = null,
        store: ModelStore? = null,
    ): SetupActivity {
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().totalRamOverride = ramBytes
        controller.get().freeSpaceOverride = freeBytes
        controller.get().modelStoreOverride = store
        controller.setup()
        controller.get().awaitSetupIo()
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

    /** Button inside the U2 card by its exact text (null if absent). */
    private fun u2Button(activity: SetupActivity, text: String): Button? {
        val root = activity.findViewById<TextView>(R.id.setup_title).parent as ViewGroup
        var card: ViewGroup? = null
        outer@ for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ViewGroup && hasTextDeep(c, activity.getString(de.voicekeyboard.R.string.u2_title))) {
                card = c
                break@outer
            }
        }
        var found: Button? = null
        fun scan(v: View) {
            if (v is Button && v.text.toString() == text) found = v
            if (v is ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))
        }
        card?.let { scan(it) }
        return found
    }

    @Test fun `card is visible with title size and licence line`() {
        val texts = u2CardTexts(buildActivity()).joinToString("\n")
        assertTrue("card title", texts.contains("Text correction (U2)"))
        assertTrue("size line en/de", texts.contains("179 MB (en) / 249 MB (de)"))
        assertTrue("licence line", texts.contains("MIT / Apache-2.0"))
        assertTrue("opt-in line", texts.contains("only with your consent"))
    }

    @Test fun `hosts pinned - card offers download AND import with an honest size hint`() {
        // V0.1.54b: BASE_URL_U2_EN/_DE are pinned (catbox) — the card offers
        // the DOWNLOAD next to the parallel import and names the size honestly
        // instead of the outdated "no download host" text.
        assertEquals("https://files.catbox.moe/", de.voicekeyboard.model.ModelManifest.BASE_URL_U2_EN)
        assertEquals("https://files.catbox.moe/", de.voicekeyboard.model.ModelManifest.BASE_URL_U2_DE)
        val activity = buildActivity()
        val download = u2Button(activity, activity.getString(R.string.model_download))
        assertNotNull("download button exists on the card", download)
        assertEquals(
            "offered at 8 GB RAM with free space",
            View.VISIBLE,
            download?.visibility,
        )
        assertEquals(
            "import stays the parallel offline path",
            View.VISIBLE,
            u2Button(activity, activity.getString(R.string.setup_import_folder))?.visibility,
        )
        val texts = u2CardTexts(activity).joinToString("\n")
        assertTrue(
            "honest size hint instead of the dead 'no host' text",
            texts.contains("Download (~"),
        )
        assertFalse(
            "outdated 'no download host' text is gone from the card",
            texts.contains(activity.getString(de.voicekeyboard.R.string.setup_import_only)),
        )
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
        assertEquals(
            "no download button below the 8 GB RAM gate",
            View.GONE,
            u2Button(activity, activity.getString(R.string.model_download))?.visibility,
        )
    }

    @Test fun `space floor fails - honest space text, no download button`() {
        val activity = buildActivity(freeBytes = 1L * 1024 * 1024 * 1024) // < 2.5 GB floor
        val texts = u2CardTexts(activity).joinToString("\n")
        assertTrue(
            "space reason visible (existing space text)",
            texts.contains(activity.getString(de.voicekeyboard.R.string.setup_error_space)),
        )
        assertEquals(
            "no download button without the free-space floor",
            View.GONE,
            u2Button(activity, activity.getString(R.string.model_download))?.visibility,
        )
    }

    @Test fun `READY U2 set shows delete only - no download, no import`() {
        // current typing language en-us -> the READY fixture set is the EN one
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "en-us")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "en-us")
            .commit()
        val activity = buildActivity(store = storeWithReadyU2())
        assertEquals(
            "no download on a READY set",
            View.GONE,
            u2Button(activity, activity.getString(R.string.model_download))?.visibility,
        )
        assertEquals(
            "no import on a READY set",
            View.GONE,
            u2Button(activity, activity.getString(R.string.setup_import_folder))?.visibility,
        )
        assertEquals(
            "delete frees the READY set",
            View.VISIBLE,
            u2Button(activity, activity.getString(R.string.model_delete))?.visibility,
        )
        val texts = u2CardTexts(activity).joinToString("\n")
        assertTrue("ready text", texts.contains(activity.getString(de.voicekeyboard.R.string.u2_ready)))
    }

    @Test fun `clicking the download button starts the service with the U2 EXTRA_VARIANT`() {
        // current typing language en-us -> the EN set downloads
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "en-us")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "en-us")
            .commit()
        val activity = buildActivity()
        u2Button(activity, activity.getString(R.string.model_download))!!.performClick()
        val started = Shadows.shadowOf(app).nextStartedService
        assertEquals(
            de.voicekeyboard.model.ModelDownloadService.ACTION_START,
            started.action,
        )
        assertEquals(
            "EXTRA_VARIANT = the card's U2 set id (NOT the dictation fallback A)",
            de.voicekeyboard.model.ModelVariant.BART_SPELLING_EN.id,
            started.getStringExtra(de.voicekeyboard.model.ModelDownloadService.EXTRA_VARIANT),
        )
        assertEquals(de.voicekeyboard.model.ModelDownloadService::class.java.name, started.component?.className)
    }
}

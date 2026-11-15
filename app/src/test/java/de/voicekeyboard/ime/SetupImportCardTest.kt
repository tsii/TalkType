package de.voicekeyboard.ime

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import de.voicekeyboard.R
import de.voicekeyboard.model.ImportResult
import de.voicekeyboard.model.ModelFile
import de.voicekeyboard.model.ModelStatus
import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelVariant
import de.voicekeyboard.model.Sha256
import java.io.File

/**
 * V0.1.51 (PLAN_V0151 §D, P3): "Import folder…" — one button per set without
 * a download host (Qwen, U2-en, U2-de), click starts the SYSTEM folder picker
 * (ACTION_OPEN_DOCUMENT_TREE), the button disappears once the set is READY,
 * and every [ImportResult] maps to a visible message (pure [importMessage]).
 * The import itself is pinned in ModelStoreImportTest (store level, fake
 * ImportSource) — here only the UI wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupImportCardTest {

    private lateinit var storeRoot: File

    /** Store with a MINI manifest (small real hashes) — READY is cheap to build. */
    private fun miniStore(): ModelStore {
        val bytes = ByteArray(256) { (it % 251).toByte() }
        val manifest = listOf(
            ModelFile("encoder.int8.onnx", 256L, Sha256.ofBytes(bytes)),
            ModelFile("tokenizer.json", 64L, Sha256.ofBytes(bytes.copyOf(64))),
        )
        val store = ModelStore(storeRoot, manifest)
        return store
    }

    private fun storeWithReadySet(variant: ModelVariant): ModelStore {
        val store = miniStore()
        val staging = store.newStagingDir()
        val bytes = ByteArray(256) { (it % 251).toByte() }
        for (entry in listOf(
            ModelFile("encoder.int8.onnx", 256L, Sha256.ofBytes(bytes)),
            ModelFile("tokenizer.json", 64L, Sha256.ofBytes(bytes.copyOf(64))),
        )) {
            File(staging, entry.storeName).writeBytes(
                if (entry.storeName == "tokenizer.json") bytes.copyOf(64) else bytes,
            )
        }
        assertTrue(store.activate(staging, variant))
        assertEquals(ModelStatus.READY, store.statusQuick(variant))
        return store
    }

    private fun buildActivity(store: ModelStore? = null): SetupActivity {
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().totalRamOverride = 8L * 1024 * 1024 * 1024
        if (store != null) controller.get().modelStoreOverride = store
        controller.setup()
        return controller.get()
    }

    private fun u2Card(activity: SetupActivity): ViewGroup {
        val root = activity.findViewById<TextView>(R.id.setup_title).parent as ViewGroup
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ViewGroup && hasTextDeep(c, activity.getString(R.string.u2_title))) return c
        }
        throw AssertionError("U2 card not found")
    }

    private fun hasTextDeep(v: View, needle: String): Boolean {
        if (v is TextView && v.text.toString().contains(needle)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasTextDeep(v.getChildAt(i), needle)) return true
        return false
    }

    private fun importButtonIn(card: ViewGroup): Button {
        for (i in 0 until card.childCount) {
            val c = card.getChildAt(i)
            if (c is Button && c.text.toString() == card.context.getString(R.string.setup_import_folder)) return c
        }
        throw AssertionError("no import button on the card")
    }

    @Before fun setUpStoreRoot() {
        storeRoot = createTempDir("vk-import-card")
    }

    // ------------------------------------------------ button present + picker

    @Test fun `qwen card offers Import folder while the set is not READY`() {
        val activity = buildActivity()
        val button = activity.findViewById<Button>(R.id.setup_qwen_import)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(activity.getString(R.string.setup_import_folder), button.text.toString())
    }

    @Test fun `clicking the qwen import button starts the system folder picker`() {
        val activity = buildActivity()
        activity.findViewById<Button>(R.id.setup_qwen_import).performClick()
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.action)
    }

    @Test fun `u2 card offers Import folder for the current typing language set`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "en-us")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "en-us")
            .commit()
        val activity = buildActivity()
        val button = importButtonIn(u2Card(activity))
        assertEquals(View.VISIBLE, button.visibility)
        button.performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, Shadows.shadowOf(activity).nextStartedActivity.action)
    }

    @Test fun `u2 import stays visible on a low-RAM device - the gate only locks downloads`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "en-us")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "en-us")
            .commit()
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().totalRamOverride = 4L * 1024 * 1024 * 1024
        controller.setup()
        assertEquals(
            "local import is no download — the RAM gate does not apply",
            View.VISIBLE, importButtonIn(u2Card(controller.get())).visibility,
        )
    }

    // ------------------------------------------------------- READY hides import

    @Test fun `a READY qwen set hides the import button like after a download`() {
        val activity = buildActivity(store = storeWithReadySet(ModelVariant.QWEN3_ASR))
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.setup_qwen_import).visibility)
    }

    @Test fun `a READY u2 set hides the import button and shows Delete instead`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "en-us")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "en-us")
            .commit()
        val activity = buildActivity(store = storeWithReadySet(ModelVariant.BART_SPELLING_EN))
        val card = u2Card(activity)
        assertEquals(View.GONE, importButtonIn(card).visibility)
        assertTrue(
            "delete visible on READY (existing logic untouched)",
            (0 until card.childCount).any {
                val c = card.getChildAt(it)
                c is Button && c.text.toString() == activity.getString(R.string.model_delete) &&
                    c.visibility == View.VISIBLE
            },
        )
    }

    // ------------------------------------------------------------ picker cancel

    @Test fun `closing the folder picker without a choice starts no import and shows no error`() {
        val activity = buildActivity()
        activity.onImportFolderPicked(null) // user pressed back in the picker
        assertNull("a cancel is not a failure — no toast", ShadowToast.getTextOfLatestToast())
    }

    // -------------------------------------------------- error text mapping (pure)

    @Test fun `importMessage maps every result to a string with the offending file`() {
        val ready = importMessage(ImportResult.Ready)
        assertEquals(R.string.setup_import_ok, ready.first)
        assertNull(ready.second)

        val missing = importMessage(ImportResult.Missing("tokenizer/tokenizer.json"))
        assertEquals(R.string.setup_import_missing, missing.first)
        assertEquals("the file name reaches the message", "tokenizer/tokenizer.json", missing.second)

        val hash = importMessage(ImportResult.HashMismatch("encoder.int8.onnx"))
        assertEquals(R.string.setup_import_hash, hash.first)
        assertEquals("encoder.int8.onnx", hash.second)

        val size = importMessage(ImportResult.SizeMismatch("mel_filters.npy"))
        assertEquals(R.string.setup_import_size, size.first)
        assertEquals("mel_filters.npy", size.second)

        val io = importMessage(ImportResult.IoError("source went away"))
        assertEquals(R.string.setup_import_io, io.first)
        assertNull("technical detail never reaches the UI", io.second)
    }
}

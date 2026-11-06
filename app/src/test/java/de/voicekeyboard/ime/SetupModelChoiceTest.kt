package de.voicekeyboard.ime

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import de.voicekeyboard.R
import de.voicekeyboard.model.ModelDownloadService
import de.voicekeyboard.model.ModelFile
import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelVariant
import de.voicekeyboard.model.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
 * T22h (PLAN_V0146 §3): model selection in the Setup — one card per set
 * (Parakeet recommended / Qwen3-ASR experimental; LITERT stays debug-only
 * and hidden). Pins:
 *  (a) section + both cards with the PINNED Opus texts (sizes included);
 *  (b) the Qwen card is DISABLED with an explanation when the RAM gate
 *      fails (4 GiB fixture) and when the manifest list is empty
 *      (fixture injection — production pins 6 files);
 *  (c) Select writes vk_layout/model_variant ONLY on a READY card;
 *  (d) Delete is visible only for NON-ACTIVE READY sets, frees them via
 *      deleteSet and the card falls back to the download state;
 *  (e) the Download button starts ModelDownloadService with EXTRA_VARIANT;
 *  (f) the network invite persists the set id of the ACTIVE variant —
 *      A users keep their already-persisted id (default-identical).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class SetupModelChoiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ramPlenty = 12L shl 30
    private val ram4GiB = 4L shl 30

    private val fixtureA = byteArrayOf(1, 2, 3)
    private val fixtureB = byteArrayOf(9, 8)

    private fun miniManifest(): List<ModelFile> = listOf(
        ModelFile("model.bin", fixtureA.size.toLong(), Sha256.ofBytes(fixtureA)),
        ModelFile("vocab.txt", fixtureB.size.toLong(), Sha256.ofBytes(fixtureB)),
    )

    /** Store with READY versions of [variants] (T22c staging pattern). */
    private fun storeWith(vararg variants: ModelVariant): ModelStore {
        val store = ModelStore(tmp.newFolder(), miniManifest())
        for (v in variants) {
            val staging = store.newStagingDir()
            File(staging, "model.bin").writeBytes(fixtureA)
            File(staging, "vocab.txt").writeBytes(fixtureB)
            assertTrue("fixture activation of ${v.id} expected", store.activate(staging, v))
        }
        return store
    }

    private fun writePref(id: String?) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        if (id == null) {
            ctx.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit()
        } else {
            ctx.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, Context.MODE_PRIVATE)
                .edit().putString(ModelVariant.PREFS_KEY, id).commit()
        }
    }

    private fun prefValue(): String? =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, Context.MODE_PRIVATE)
            .getString(ModelVariant.PREFS_KEY, null)

    private fun buildActivity(
        store: ModelStore? = null,
        ram: Long? = ramPlenty,
        manifestOverride: List<ModelFile>? = null,
        prefId: String? = null,
    ): SetupActivity {
        writePref(prefId)
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().modelStoreOverride = store
        controller.get().totalRamOverride = ram
        controller.get().manifestOverride = manifestOverride
        controller.setup()
        return controller.get()
    }

    // ---- (a) section + pinned texts ----

    @Test
    fun `(a) section shows both cards with the pinned titles and sizes`() {
        val a = buildActivity(store = storeWith(ModelVariant.ONNX_CPU))
        assertEquals(
            "Dictation model",
            a.findViewById<TextView>(R.id.setup_model_section_title).text.toString(),
        )
        val parakeet = a.findViewById<View>(R.id.setup_card_parakeet)
        val qwen = a.findViewById<View>(R.id.setup_card_qwen)
        assertEquals(View.VISIBLE, parakeet.visibility)
        assertEquals(View.VISIBLE, qwen.visibility)
        assertEquals(
            "Parakeet TDT 0.6B (recommended)",
            a.findViewById<TextView>(R.id.setup_parakeet_title).text.toString(),
        )
        assertEquals(
            "Qwen3-ASR 0.6B (experimental)",
            a.findViewById<TextView>(R.id.setup_qwen_title).text.toString(),
        )
        assertTrue(
            "parakeet body carries the 0.67 GB size",
            a.findViewById<TextView>(R.id.setup_parakeet_body).text.toString().contains("0.67 GB"),
        )
        val qwenBody = a.findViewById<TextView>(R.id.setup_qwen_body).text.toString()
        assertTrue("qwen body carries the 1.7 GB size", qwenBody.contains("1.7 GB"))
        // C1 (Oracle §20b): the precise RTF end text replaces the vague
        // "Slower than Parakeet" — honest about the missing phone measurement.
        assertTrue(
            "qwen body carries the measured RTF warning",
            qwenBody.contains("RTF 1.2 measured"),
        )
        assertTrue(
            "qwen body warns the phone is NOT yet measured",
            qwenBody.contains("NOT yet measured on your phone"),
        )
        assertTrue("qwen body is marked experimental", qwenBody.contains("Experimental"))
    }

    // ---- (b) qwen disabled: RAM gate + empty manifest fixture ----

    @Test
    fun `(b) qwen card disabled with ram explanation at 4 GiB - parakeet stays usable`() {
        val a = buildActivity(store = storeWith(), ram = ram4GiB)
        assertEquals(
            "RAM explanation pinned",
            a.getString(R.string.model_ram_locked),
            a.findViewById<TextView>(R.id.setup_qwen_status).text.toString(),
        )
        assertEquals(
            "qwen download hidden behind the RAM gate",
            View.GONE,
            a.findViewById<Button>(R.id.setup_qwen_download).visibility,
        )
        // A has NO RAM gate (plan §2: RAM gate Qwen only): still downloadable
        assertEquals(
            "parakeet download unaffected by the RAM gate",
            View.VISIBLE,
            a.findViewById<Button>(R.id.setup_parakeet_download).visibility,
        )
    }

    @Test
    fun `(b) qwen card disabled when the manifest list is empty (fixture injection)`() {
        val a = buildActivity(store = storeWith(), manifestOverride = emptyList())
        assertEquals(
            "not-unlocked explanation",
            a.getString(R.string.model_not_downloaded),
            a.findViewById<TextView>(R.id.setup_qwen_status).text.toString(),
        )
        assertEquals(
            View.GONE,
            a.findViewById<Button>(R.id.setup_qwen_download).visibility,
        )
        assertEquals(
            "parakeet card also offer-checked (fixture applies to every set)",
            View.GONE,
            a.findViewById<Button>(R.id.setup_parakeet_download).visibility,
        )
    }

    // ---- (c) Select writes the pref ONLY on READY ----

    @Test
    fun `(c) select on a READY card writes the target id into the pref`() {
        val a = buildActivity(store = storeWith(ModelVariant.ONNX_CPU))
        assertNull("no pref before the click", prefValue())
        a.findViewById<Button>(R.id.setup_parakeet_select).performClick()
        assertEquals("onnx-cpu", prefValue())
    }

    @Test
    fun `(c) select on a NOT-READY card never writes the pref`() {
        val a = buildActivity(store = storeWith(ModelVariant.ONNX_CPU)) // qwen NOT installed
        a.findViewById<Button>(R.id.setup_qwen_select).performClick()
        assertNull("pref unchanged on a not-ready card", prefValue())
    }

    // ---- (d) Delete: only non-active READY, frees the set ----

    @Test
    fun `(d) delete hidden on the active set - visible and working on the other READY set`() {
        // VK-2: the delete -> download transition is pinned on the PARAKEET
        // card (A is always offerable); the qwen card's download is hidden
        // while its base URL is empty (not-enabled path).
        val store = storeWith(ModelVariant.ONNX_CPU, ModelVariant.QWEN3_ASR)
        val a = buildActivity(store = store, prefId = ModelVariant.QWEN3_ASR.id)

        // active card: NEVER a delete
        assertEquals(
            "no delete on the active set",
            View.GONE,
            a.findViewById<Button>(R.id.setup_qwen_delete).visibility,
        )
        // non-active READY card: delete visible
        val parakeetDelete = a.findViewById<Button>(R.id.setup_parakeet_delete)
        assertEquals(View.VISIBLE, parakeetDelete.visibility)

        parakeetDelete.performClick()

        assertNull("set freed via deleteSet", store.activeVersion(ModelVariant.ONNX_CPU))
        assertEquals(
            "card back to the download state",
            View.VISIBLE,
            a.findViewById<Button>(R.id.setup_parakeet_download).visibility,
        )
        // the active set is untouched
        assertEquals(ModelVariant.QWEN3_ASR.id, prefValue())
        assertTrue(store.statusQuick(ModelVariant.QWEN3_ASR) == de.voicekeyboard.model.ModelStatus.READY)
        assertFalse(
            "delete never appears on the ACTIVE card after the refresh",
            a.findViewById<Button>(R.id.setup_qwen_delete).visibility == View.VISIBLE,
        )
    }

    // ---- (d2) C2 (Oracle §20b): locked + READY keeps the delete button ----

    @Test
    fun `(d2) qwen ready but locked (BASE_URL_QWEN empty) keeps select and delete - no orphaned 1,7 GB`() {
        // production state today: BASE_URL_QWEN = "" (VK-2, no server pinned)
        // while the qwen files sit READY on disk — the card must stay operable.
        assertEquals("", de.voicekeyboard.model.ModelManifest.BASE_URL_QWEN)
        val store = storeWith(ModelVariant.QWEN3_ASR)
        val a = buildActivity(store = store) // active defaults to Parakeet
        assertEquals(
            "READY card shows ready",
            a.getString(R.string.model_ready),
            a.findViewById<TextView>(R.id.setup_qwen_status).text.toString(),
        )
        assertEquals(
            "C2: delete stays visible on the locked READY card",
            View.VISIBLE,
            a.findViewById<Button>(R.id.setup_qwen_delete).visibility,
        )
        assertEquals(
            "select stays visible on the locked READY card",
            View.VISIBLE,
            a.findViewById<Button>(R.id.setup_qwen_select).visibility,
        )
        assertEquals(
            "download stays hidden while the base URL is empty (VK-2)",
            View.GONE,
            a.findViewById<Button>(R.id.setup_qwen_download).visibility,
        )
        // and the delete REALLY frees the set (deleteSet works on locked sets)
        a.findViewById<Button>(R.id.setup_qwen_delete).performClick()
        assertNull("set freed despite the lock", store.activeVersion(ModelVariant.QWEN3_ASR))
        assertEquals(
            "card falls back to the locked download-hidden state",
            View.GONE,
            a.findViewById<Button>(R.id.setup_qwen_delete).visibility,
        )
    }

    // ---- (e) Download button starts the service with EXTRA_VARIANT ----

    @Test
    fun `(e) qwen download starts the service with the qwen EXTRA_VARIANT`() {
        val a = buildActivity(store = storeWith())
        a.findViewById<Button>(R.id.setup_qwen_download).performClick()
        val app = ApplicationProvider.getApplicationContext<Context>() as Application
        val started = Shadows.shadowOf(app).nextStartedService
        assertEquals(
            de.voicekeyboard.model.ModelDownloadService.ACTION_START,
            started.action,
        )
        assertEquals(
            "EXTRA_VARIANT = the card's set id",
            ModelVariant.QWEN3_ASR.id,
            started.getStringExtra(de.voicekeyboard.model.ModelDownloadService.EXTRA_VARIANT),
        )
        assertEquals(ModelDownloadService::class.java.name, started.component?.className)
    }

    @Test
    fun `(e) parakeet download carries the onnx-cpu EXTRA_VARIANT`() {
        val a = buildActivity(store = storeWith())
        a.findViewById<Button>(R.id.setup_parakeet_download).performClick()
        val app = ApplicationProvider.getApplicationContext<Context>() as Application
        val started = Shadows.shadowOf(app).nextStartedService
        assertEquals(
            ModelVariant.ONNX_CPU.id,
            started.getStringExtra(de.voicekeyboard.model.ModelDownloadService.EXTRA_VARIANT),
        )
    }

    // ---- (f) invite: set id of the ACTIVE variant; A ids stay stable ----

    @Test
    fun `(f) invite persists the set id of the ACTIVE variant (qwen)`() {
        val store = storeWith(ModelVariant.ONNX_CPU, ModelVariant.QWEN3_ASR)
        val a = buildActivity(store = store, prefId = ModelVariant.QWEN3_ASR.id)
        a.inviteStateOverride = de.voicekeyboard.model.DownloadController.State.Ready

        a.findViewById<Button>(R.id.setup_net_invite_later).performClick()

        val shownFor = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("vk_permissions", Context.MODE_PRIVATE)
            .getString("net_invite_shown_for", null)
        assertEquals(
            "invite key = qwen set id",
            store.manifestSetId(ModelVariant.QWEN3_ASR),
            shownFor,
        )
    }

    @Test
    fun `(f) A users keep their persisted invite id - default is identical`() {
        // default-manifest store (pure hash, no files needed): the set ids of
        // A and Qwen differ — and the DEFAULT call equals the ONNX_CPU call.
        val store = ModelStore(tmp.newFolder())
        assertEquals(store.manifestSetId(), store.manifestSetId(ModelVariant.ONNX_CPU))
        assertNotEquals(
            "different sets => different invite ids (invite shows again after Qwen)",
            store.manifestSetId(ModelVariant.ONNX_CPU),
            store.manifestSetId(ModelVariant.QWEN3_ASR),
        )
    }
}

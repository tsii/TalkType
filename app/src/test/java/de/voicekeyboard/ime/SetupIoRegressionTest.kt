package de.voicekeyboard.ime

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SetupIoRegressionTest {
    @Test fun `picker result received before storage discovery is retained`() {
        val pending = mutableListOf<Runnable>()
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().ioExecutor = Executor { pending.add(it) }
        val saved = android.os.Bundle().apply { putString("pending_import_variant", "qwen3-asr") }
        // Use the actual variant id, independently of its serialization spelling.
        saved.putString("pending_import_variant", de.voicekeyboard.model.ModelVariant.QWEN3_ASR.id)
        controller.create(saved).start().resume()
        controller.get().onImportFolderPicked(android.net.Uri.parse("content://test/tree/root"))
        val out = android.os.Bundle()
        controller.saveInstanceState(out)
        assertEquals("picker target must survive until storage is ready",
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR.id, out.getString("pending_import_variant"))
        assertEquals("content://test/tree/root", out.getString("pending_import_uri"))
        controller.pause().stop().destroy()
    }

    @Test fun `queued delete prevents selecting its target`() {
        val bytes = byteArrayOf(1)
        val store = de.voicekeyboard.model.ModelStore(java.nio.file.Files.createTempDirectory("delete-race").toFile(),
            listOf(de.voicekeyboard.model.ModelFile("model.bin", 1,
                de.voicekeyboard.model.Sha256.ofBytes(bytes))))
        val staging = store.newStagingDir()
        java.io.File(staging, "model.bin").writeBytes(bytes)
        assertTrue(store.activate(staging, de.voicekeyboard.model.ModelVariant.QWEN3_ASR))
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().modelStoreOverride = store
        controller.setup()
        controller.get().awaitSetupIo()
        val pending = mutableListOf<Runnable>()
        controller.get().ioExecutor = Executor { pending.add(it) }
        controller.get().findViewById<android.widget.Button>(de.voicekeyboard.R.id.setup_qwen_delete).performClick()
        controller.get().findViewById<android.widget.Button>(de.voicekeyboard.R.id.setup_qwen_select).performClick()
        val prefs = controller.get().getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, 0)
        assertNotEquals("deleting set cannot become active",
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR.id,
            prefs.getString(de.voicekeyboard.model.ModelVariant.PREFS_KEY, null))
        // An external preference change must also be revalidated by the worker.
        prefs.edit().putString(de.voicekeyboard.model.ModelVariant.PREFS_KEY,
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR.id).commit()
        val worker = Thread({ pending.removeAt(0).run() }, "test-delete")
        worker.start(); worker.join()
        assertEquals(de.voicekeyboard.model.ModelStatus.READY,
            store.statusQuick(de.voicekeyboard.model.ModelVariant.QWEN3_ASR))
        controller.pause().stop().destroy()
    }

    @Test fun `late worker result does not populate a destroyed activity`() {
        val pending = mutableListOf<Runnable>()
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().ioExecutor = Executor { pending.add(it) }
        controller.setup().pause().stop().destroy()
        val worker = Thread({ pending.removeAt(0).run() }, "test-setup-io")
        worker.start(); worker.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().storageLoaded)
    }

    @Test fun `storage executor cannot accidentally run discovery on main`() {
        val pending = mutableListOf<Runnable>()
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().ioExecutor = Executor { pending.add(it) }
        controller.setup()
        pending.removeAt(0).run()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("main-thread guard rejects IO", controller.get().storageLoaded)
        controller.destroy()
    }

    @Test fun `creation queues storage discovery instead of doing it inline`() {
        val pending = mutableListOf<Runnable>()
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().ioExecutor = Executor { pending.add(it) }
        controller.setup()
        assertTrue("storage discovery must be queued", pending.isNotEmpty())
        assertFalse(controller.get().storageLoaded)
        val worker = Thread({ pending.removeAt(0).run() }, "test-setup-io")
        worker.start(); worker.join()
        assertFalse("worker cannot render views directly", controller.get().storageLoaded)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().storageLoaded)
        controller.destroy()
    }
}

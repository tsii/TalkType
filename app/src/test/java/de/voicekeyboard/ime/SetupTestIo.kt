package de.voicekeyboard.ime

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Wait for the real setup worker, then explicitly deliver its main-loop result. */
internal fun SetupActivity.awaitSetupIo(): SetupActivity {
    repeat(3) {
        val done = CountDownLatch(1)
        ioExecutor.execute { done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "Setup worker did not finish" }
        shadowOf(Looper.getMainLooper()).idle()
    }
    return this
}

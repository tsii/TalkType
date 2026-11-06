package de.voicekeyboard.keyboard

import android.app.Application
import android.view.MotionEvent
import android.view.View
import de.voicekeyboard.layouts.LayoutRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * VK-9 (EMU_DUTY_V0147_ADDENDUM, planner dossier §5): a re-measure between
 * DOWN and UP of the SAME tap must not drop the character. rebuildModel used
 * to rotate the model instance on EVERY onMeasure (e.g. the suggestion-row
 * publish triggers a relayout every ~300 ms); the UP gate hung on OBJECT
 * IDENTITY (`key === pressedKey`) and silently discarded the tap.
 *
 * Here: TWO DOWN/UP pairs on the 'f' key centre (delta < LONG_PRESS_MS);
 * between DOWN and UP of the SECOND pair a full re-measure is forced — the
 * character must still commit (2x onTextInput("f")).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardTapIdentityTest {

    private val app: Application get() = RuntimeEnvironment.getApplication() as Application

    @Test
    fun `re-measure between DOWN and UP does not drop the tap`() {
        val texts = ArrayList<String>()
        val view = KeyboardView(app)
        view.listener = object : KeyboardView.Listener {
            override fun onTextInput(text: String) {
                texts.add(text)
            }

            override fun onAction(action: String) = Unit
        }
        view.setLayout(LayoutRepository(app).loadOrFallback("de-de"), 1080)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val f = view.keyOf("f")!!
        val cx = f.centerX
        val cy = f.centerY

        // pair 1: plain tap
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx, cy, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 100, MotionEvent.ACTION_UP, cx, cy, 0))
        assertEquals(listOf("f"), texts)

        // pair 2: FULL re-measure (rebuildModel!) between DOWN and UP —
        // the identity rotation used to drop this tap.
        view.dispatchTouchEvent(MotionEvent.obtain(0, 200, MotionEvent.ACTION_DOWN, cx, cy, 0))
        view.requestLayout()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.dispatchTouchEvent(MotionEvent.obtain(0, 300, MotionEvent.ACTION_UP, cx, cy, 0))

        assertEquals("re-measure must not drop the tap", listOf("f", "f"), texts)
    }
}

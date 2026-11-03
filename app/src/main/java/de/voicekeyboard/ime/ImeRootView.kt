package de.voicekeyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.voicekeyboard.R
import de.voicekeyboard.keyboard.EmojiPanelView
import de.voicekeyboard.keyboard.KeyboardPalette
import de.voicekeyboard.keyboard.KeyboardTheme
import de.voicekeyboard.keyboard.KeyboardView

/**
 * Container der IME (V2: Moduswechsel statt Overlay, EMOJI_PANEL_DESIGN_V2 §2).
 *
 * Genau zwei Modi, gegenseitig ausschließende Stack-Kinder:
 *  - Tastatur: statusRow VISIBLE, keyboardView VISIBLE, Emoji-Block GONE.
 *  - Emoji:    statusRow VISIBLE, keyboardView GONE (vollständig, auch unterste
 *              Reihe — keine unsichtbaren Keyboard-Trefferflächen), Emoji-Block
 *              VISIBLE mit nativem Footer-Button „Zur Tastatur“.
 *
 * Keine Overlay-Rechnung: kein onLayout-Höhenhandling, keine Margen/Gravity-Anker
 * für das Panel (die drei Overlay-Varianten sind gerätewiderlegt, M8b-3b/c/d).
 * Der Größenvertrag ist ein Designmaß: Emoji-Block 240 dp Gesamthöhe inkl.
 * Footer; EmojiPanel height=0 + Gewicht 1 — der Layout-Manager verteilt.
 * System-Navigationsinsets sind EINMAL als unteres Root-Padding gesetzt.
 */
class ImeRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val statusText: TextView
    val micButton: Button
    /** P0 (2026-11-01): 🐞 als ERSTES View-Kind der statusRow (48 dp, rechts) —
     *  vorher dynamisches addView im Service (brüchig, ungetestet, verschwand
     *  geräteseitig). Immer sichtbar, Service hängt nur den Listener an. */
    val debugButton: Button
    /** T9 (v0.1.38): die Statuszeile als Ganzes — NUR Debug sichtbar (s. init);
     *  die Sichtbarkeit ist Tests zugänglich, ohne über micButton.parent zu gehen. */
    val statusRow: LinearLayout
    val keyboardView: KeyboardView
    val emojiPanel: EmojiPanelView

    /** Nativer Rückweg-Button im Emoji-Block (V2 §3): voll, breit, ≥48dp, eigener Hit-Test. */
    val backToKeyboardButton: Button

    /** Emoji-Inhaltsblock: alternatives Stack-Kind (kein Overlay). */
    private val emojiBlock: LinearLayout

    init {
        val density = resources.displayMetrics.density

        // S4 — Stock-Tokens (DESIGN_STOCK_LOOK §1): ALLE Farben der Leiste, des
        // Roots und der nativen Buttons kommen aus KeyboardTheme (hell/dunkel nach
        // System-Nachtmodus) — kein hartkodierter Farbwert in dieser Datei.
        val palette: KeyboardPalette = KeyboardTheme.palette(
            isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
        )
        // Dark muss auch am Root greifen (Sol §1) — deckende Fläche für den ganzen Stack.
        setBackgroundColor(palette.background)

        statusText = TextView(context).apply {
            // P0 (2026-11-01, Nutzer-Entscheidung): Grundzustand NIE leer —
            // vorher (S4) bewusst "", weil der Service den Text setzt; der
            // Service-Weg (updateStatus) bleibt, "Bereit" ist nur der
            // Konstruktions-Default, falls er (noch) nicht lief.
            text = "Bereit"
            // Sol §1: Status 12 sp, sekundäre Textfarbe (Texte links).
            textSize = KeyboardTheme.SP_SECONDARY_SP
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        micButton = Button(context).apply {
            text = "🎤"
            isEnabled = true // Push-to-Talk: Touch-Down startet, Touch-Up stoppt (Service verdrahtet)
            // S4: nativer Button ohne fremdes Relief-Thema — Farben aus den Tokens.
            setTextColor(palette.textFunctional)
            setBackgroundColor(palette.background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }

        debugButton = Button(context).apply {
            // P0: 48-dp-Trefferfläche, RECHTS (letztes Kind der statusRow),
            // Tokens wie micButton (kein fremdes Relief-Thema).
            text = "🐞"
            contentDescription = "Diagnose kopieren/teilen"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setTextColor(palette.textFunctional)
            setBackgroundColor(palette.background)
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt(),
            )
        }

        // T9 (v0.1.38): Statuszeile NUR DEBUG — im Release (FLAG_DEBUGGABLE fehlt)
        // bleibt die statusRow GONE: Statustext + 🎤 + 🐞 belegen KEINEN Layout-
        // platz, die Tastatur erhält das volle Höhenbudget. Injizierbare Naht:
        // context.applicationInfo.flags, EINMAL gelesen (Robolectric-Tests steuern
        // das Bit). Service-Updates (statusText.text, micButton.isEnabled/
        // isActivated) sind reine Property-Sets auf weiterhin angehängten Views —
        // bei GONE gefahrlos, kein Guard nötig (geprüft: VoiceKeyboardService
        // :1537/1550/1552 und syncMicUi :921). T5-Band-Overlay: ohne sichtbare
        // statusRow liegt die KeyboardView an der Stack-Oberkante (top = 0) ⇒
        // statusBandPx = 0 ⇒ menuTopForStatusBand fällt auf die T1-Zweizonenregel
        // zurück (Bestands-Fallback).
        val debugStatusRow =
            (context.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

        statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // T21/S2-Höhenhaushalt (DESIGN_STOCK_LOOK §3): DIESER Zeilenblock ist die
            // EINE Stelle, an der die Statuszeile in den Höhenhaushalt eingeht —
            // explizite 48-dp-Höhe (nicht „versehentlich“ über die Debug-Taste
            // gezogen). Der vertikale Stack unten misst die statusRow zuerst und
            // zieht ihre Höhe von der Child-MeasureSpec der KeyboardView ab
            // (LinearLayout.onMeasure → measureChildBeforeLayout); KeyboardView
            // rechnet deshalb mit statusLinePx = 0 — GENAU EINMAL, kein Doppelabzug.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt(),
            )
            // S4: 1-dp-Trennlinie UNTER der Leiste (Sol §1 „Trennlinien: 1 dp unter
            // der Diagnoseleiste — einzige feste Linie, auch in Emoji-Ansicht
            // konsistent“). Als Hintergrund-Layer INNERHALB der 48 dp, damit sich
            // die Abzugskette (Keys-Budget) nicht erneut ändert — kein zusätzliches
            // Stack-Kind, keine Messänderung.
            val fill = GradientDrawable().apply { setColor(palette.background) }
            val line = GradientDrawable().apply { setColor(palette.separator) }
            background = LayerDrawable(arrayOf(fill, line)).apply {
                setLayerInset(1, 0, (48 * density).toInt() - (1 * density).toInt().coerceAtLeast(1), 0, 0)
            }
            addView(statusText)
            addView(micButton)
            addView(debugButton) // P0: rechts, fest in der Zeile (kein dynamisches addView)
            // T9 (vc39): Release ⇒ GONE (kein Layout-Platz); Debug ⇒ Bestand (VISIBLE).
            // T9b-Härtung (vc39-Beweislauf, Review-Nachlese): die Modi-Wechsler
            // showEmojiPanel()/hideEmojiPanel() fassen NUR keyboardView/emojiBlock an —
            // nie statusRow; der Service setzt überhaupt keine visibility. Damit kein
            // künftiger Wechsler die Release-Zeile zurückholt, bleibt die Setzung hier
            // die einzige Stelle und ist per Test erzwungen (ImeRootViewLayoutTest:
            // Release-Test prüft GONE in Tastatur- UND Emoji-Modus inkl. Roundtrip).
            visibility = if (debugStatusRow) VISIBLE else GONE
        }

        keyboardView = KeyboardView(context)
        keyboardView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        // Emoji-Block (Designmaß 240dp gesamt inkl. Footer): Panel gewichtet (height=0,
        // weight=1), Footer-Button darunter, außerhalb des Panel-Canvas/Hit-Tests.
        emojiPanel = EmojiPanelView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, // Gewicht verteilt — keine berechnete Höhe
                1f,
            )
        }
        backToKeyboardButton = Button(context).apply {
            text = context.getString(R.string.emoji_back_to_keyboard)
            // S4: nativer Footer-Button in den Tokens (kein Activity-Theme-Relief).
            setTextColor(palette.textFunctional)
            setBackgroundColor(palette.background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxOf((48 * density).toInt(), minHeight),
            )
        }
        emojiBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * density).toInt(), // Designmaß inkl. Footer (V2 §2)
            )
            addView(emojiPanel)
            addView(backToKeyboardButton)
            visibility = GONE // Start: Tastaturmodus
        }

        // Der Stack ist das einzige inhaltliche Kind der Root.
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // F28a: das Langdruckmenü der obersten Tastenreihe wird UEBER die
            // Statusleiste gezeichnet (negatives top im KeyboardView-Koordinatensystem).
            // Der Stack darf seine Kinder daher NICHT auf die Child-/eigene Grenze
            // klemmen, sonst wäre der über die View-Oberkante hinausreichende Teil
            // der Palette unsichtbar. NUR diese zwei Properties — kein Layout-Umbau.
            clipChildren = false
            clipToPadding = false
            // F34/P2-2 (REVIEW_F29_F33): die statusRow liegt in der Z-Ordnung ÜBER
            // der KeyboardView — das r0-Popup (negatives top) ist nur Canvas-Zeichnung
            // im KeyboardView-Child und würde die 🐞-Taste zwar touchbar, aber
            // visuell verdecken. Elevation = Z-Anhebung OHNE Layout-/Geometrie-
            // Änderung (OutlineProvider.NONE: keine Schattenkante).
            statusRow.elevation = 1f
            // Kein Umriss = keine Schattenkante; die NONE-Konstante ist im
            // Offline-SDK-Stub nicht auflösbar (F34-Workaround 2026-11-01).
            statusRow.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {}
            }
            addView(statusRow)
            addView(keyboardView)
            addView(emojiBlock)
        }
        addView(stack)

        // System-Navigationsinsets als normaler unterer Innenabstand, einmal für
        // den gesamten Stack (V2 §2) — kein Panelpositionierungs-Hack.
        //
        // T21/S2-Höhenhaushalt (DESIGN_STOCK_LOOK §3): DIESE Zeile (setPadding mit
        // `nav`) ist die EINE Stelle, an der der Navigationsinset abgezogen wird:
        // FrameLayout reduziert die MeasureSpec des Stacks um das Root-Padding,
        // KeyboardView selbst rechnet mit navInsetPx = 0 — GENAU EINMAL.
        // Daraus folgt der Integrationsvertrag: Root-Angebot H → Keyboard-Child
        // erhält H − nav − 48 dp und skaliert ALLE Reihen in genau dieses Budget.
        setOnApplyWindowInsetsListener { v, insets ->
            val nav = insets.systemGestureInsets.bottom
                .coerceAtLeast(insets.systemWindowInsetBottom)
            // P0-0.1.20: INSET-ANOMALIE-Deckel — ein aehnlich großes bottom-Systeminset
            // (neueer OS-Stand, IME im eigenen Prozess) duerfe nie den ganzen Stack
            // einquetschen: das untere Root-Padding ist der EINZIGE Mechanismus, der
            // die Statuszeile (48 dp) von oben herdruecken/komprimieren kann. Der Deckel
            // gilt NUR fuer das Padding (Hoehe-Abzug bleibt genau einmal), nicht fuer
            // die Insets-Auslieferung.
            val navCapped = nav.coerceAtMost((120 * v.resources.displayMetrics.density).toInt())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navCapped)
            insets
        }
    }

    /** Emoji-Modus (idempotent): Tastatur GONE, Emoji-Block VISIBLE. */
    fun showEmojiPanel() {
        if (isEmojiPanelVisible) return
        keyboardView.visibility = GONE
        emojiBlock.visibility = VISIBLE
    }

    /** Tastaturmodus (idempotent): Emoji-Block GONE, Tastatur VISIBLE; keine MotionEvent-Weitergabe. */
    fun hideEmojiPanel() {
        if (!isEmojiPanelVisible) return
        emojiBlock.visibility = GONE
        keyboardView.visibility = VISIBLE
    }

    val isEmojiPanelVisible: Boolean
        get() = emojiBlock.visibility == VISIBLE
}

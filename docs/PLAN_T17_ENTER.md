# PLAN T17 — Enter in multiline fields must newline, not close keyboard
Symptom (user): single-line (browser URL): Enter = action, keyboard closes, page
loads -> CORRECT, keep. Multiline (e-mail body): Enter closes the keyboard; user
cannot continue on line 2 -> WRONG. Expected: keyboard stays open, text wraps.
Rule (planner-approved; AOSP semantics confirmed by review VK-R1 — UNSPECIFIED is
the default action and must NOT newline):
  action = imeOptions AND IME_MASK_ACTION
  treatAsNewline = info == null
                   || action == NONE
                   || (inputType AND TYPE_TEXT_FLAG_MULTI_LINE) != 0
                   || (imeOptions AND IME_FLAG_NO_ENTER_ACTION) != 0
  treatAsNewline -> commitText("\n",1)   (keyboard stays open)
  else           -> performEditorAction(action)
Rationale: many apps mark the body field IME_ACTION_DONE; stock keyboards treat
Done in a multiline field as a line break. Our old code sent the action blindly
(and the KEYCODE_ENTER fallback could also dismiss the window).
Tests: EditorActionEnterTest matrix — singleLine+GO -> action; singleLine+DONE ->
action; multiline+DONE -> newline; multiline+NONE -> newline; NO_ENTER_ACTION+GO
-> newline; info=null -> newline.
Verification (orchestrator, emu): Chrome URL field -> loads + closes (must stay);
DebugInputActivity multiline field -> text contains newline AND mInputShown=true.
Status: implemented by coder T17+T18-A (this commit wave), pending verification.

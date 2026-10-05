**Install this one.** It fixes the thing that actually mattered: the words never arrived in the text box.

## The text never appeared — fixed

This was silent. The pill said something reassuring, the transcript was saved to history, and your
text box was unchanged. Three real causes, all fixed:

**The field was looked up too late.** Dictation takes seconds. By the time the transcript was ready
you had often moved the caret, or the keyboard had swapped its window — and the field Typorb found
afterwards was regularly the wrong one, or nothing at all. Typorb now remembers the field **the moment
you tap the orb**, which is the last instant the answer is certainly right, and types into that one.

**A successful-looking write was trusted.** `ACTION_SET_TEXT` returning `true` only means the action
was performed. Several apps — WhatsApp's message box among them — perform it and drop the text, and
Typorb used to report that as complete success. Every route is now **read back and checked** before
it is called a success, and a route that changed nothing falls through to the next one.

**Dictating deleted what you had already typed.** `ACTION_SET_TEXT` replaces a field's entire
contents, so a dictation into a half-written message wiped that message. Typorb now reads the field
first and adds to it, on a new line.

There is also a new fallback: **`ACTION_FOCUS` then set-text again**, for fields that refuse a bare
set-text until they hold focus themselves. And if everything is refused, the words are still left on
your clipboard with the pill saying so — the text is never silently dropped.

## The orb looked broken on tap — fixed

The pill was being stretched to fill its window, so during every state change the square orb was
squashed into a capsule shape and back, and the outgoing pill was clipped to the incoming one's box.
It read as a broken layout rather than a transition. Each pill is now sized by its own content, and
the crossfade no longer clips.

Taps also had **no visual feedback at all** — the window is the pill's rectangle, so the only thing
you could see was the morph starting a beat later. The orb now scales under your finger immediately.

## Switching engines actually switches

The offline engine captured the GPU-acceleration setting **once**, when it was first built — which is
the very first dictation, long before you ever open Settings. Changing it in the dashboard did
nothing until the app was killed. It is now read fresh every time a model is loaded. Which model runs
was already re-checked per dictation; that is unchanged.

## If text still does not appear

Settings → Debug console → **Injection** now names the exact route that won (`ACTION_SET_TEXT`,
`CLIPBOARD_PASTE`, `FOCUS_THEN_SET_TEXT`, `COPIED_TO_CLIPBOARD`) and, when something was refused, which
steps were tried and why. **Copy report** sends the whole thing.

## Install

Uninstall the previous Typorb first: every build so far carries a different signing key, so Android
refuses to upgrade in place. Afterwards re-enable the accessibility service.

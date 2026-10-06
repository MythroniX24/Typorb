**This one is honest about the last one.** v1.3.8 claimed the words would arrive, and it made the orb
worse at the same time. Both are real bugs with real causes, found by reading how the apps that get this
right actually do it.

## Why the text still did not appear

**The read-back was comparing against a snapshot.** `AccessibilityNodeInfo` is a snapshot: its `.text` is
the field's content from the moment the node was fetched, and `performAction` does not update it. So
every check of "did the text land?" re-read the text from *before* the write, concluded the field had not
changed, and moved on to the next route — writing the same sentence a second time and finally reporting
a failure for a dictation that had already arrived. A node is only brought up to date by `refresh()`,
which the verification never called. It does now, on every attempt.

**The field was rebuilt around text nobody had read.** `ACTION_SET_TEXT` replaces a field's entire
contents, and the text Typorb put back came from the node — which for a hint, a placeholder or a password
is not your content at all. A rebuild now only happens for a field whose contents were actually read;
otherwise the route used is paste, which inserts at the field's own caret and is structurally incapable
of inventing or deleting text.

**The field was chosen before the dictation and never re-checked.** A node picked up seconds ago can be
stale by the time the words are ready: a recycled editor still answers questions and silently discards
writes. The field is now resolved when the words are ready, from the system's own input focus, with the
tap-time capture kept as the fallback for when the keyboard has closed since.

**And there is a new route above all of them:** on Android 13+ Typorb now types through the platform's
own input connection — the same `commitText` a keyboard uses — which needs no node, no clipboard, and
cannot land in the wrong place.

**If a field still refuses the words, long-press the orb.** The last transcript is kept, and a long press
types it again into whatever field is focused now. You should not have to say a sentence twice because a
text box said no.

The Debug console's `Injection` row now names the exact route *and the field it went into*, e.g.
`inserted via CLIPBOARD_PASTE · EditText in com.whatsapp · live focus (system findFocus)` — so "it went
somewhere" and "it went nowhere" can be told apart from a screenshot.

## Why the orb looked worse

The animation was being run **twice, on two different clocks**. The window rectangle was animated in the
overlay while Compose animated the pill inside it — so for two hundred milliseconds the recording capsule
was already 160dp wide inside a window that was still the idle square. A window surface is clipped to its
own bounds: the pill really was being cut in half on every state change. That is the "it looks broken
when I tap" you reported.

Now Compose animates once and the window follows it frame by frame, so the rectangle is never smaller
than what is inside it. The box is the *larger* of the pill's current and target size, so the pill on its
way out is never cut off either, and every pill is drawn into the top-right corner the window is anchored
to, so a capsule grows leftwards from the edge you aimed at.

## The orb can finally be dragged

This was never implemented. It is now:

* **Drag it anywhere** — it follows your finger exactly, lifts and grows slightly while it moves, and
  stops at the screen edges and at the keyboard's top edge (the one place it could be moved to and never
  dragged back from).
* The position is **remembered**, across the keyboard opening and closing, the window being recreated,
  and a reboot. Until you move it, it stays where it always was: 16dp above the keyboard, pinned right.
* A tap is still a tap: the gesture is only a drag past the platform's own touch slop, so starting a
  dictation cannot nudge the orb.

## Install

Uninstall the previous Typorb first: every build so far carries a different signing key, so Android
refuses to upgrade in place. Afterwards re-enable the accessibility service.

If text still does not land, send the **Injection** row from Settings → Debug console — with the new
wording it says which field the words went into, which is the one fact that decides what to fix next.

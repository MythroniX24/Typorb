**The orb is the icon now, a fast drag no longer stutters, and the text has one more real reason for not
arriving — that one is fixed too.**

## The orb: an icon tile, with a panel that slides out of it

The orb is your icon in a rounded tile, and that tile is the orb. It never changes size, never changes
shape, and never moves while a state changes around it.

* **Tap it** — a glass panel slides out to the **left** of the tile, and the waveform lives in there. The
  bars grow **up and down** from a line through the middle of the panel: as you speak they move, and when
  you stop they collapse back onto that line, so a pause is one straight line rather than a row of stubs
  shaking at the noise floor. (When a level counts as silence is decided by a rule with tests, not by ear:
  the room's own floor sits under it and speech sits well over it.)
* **Tap it again** — the panel closes back into the tile and the tile stays exactly where it was. While the
  words are being written, the tile itself wears a slow cobalt ring, because the thing you asked to stop
  should look like your orb again the moment you stop it.
* **A failure** slides the panel back out, red-tinted and wide enough to read the reason on one line.

Both icons came from the files you dropped in: the pack's launcher icons are the app icon (adaptive, every
density, plus the round and themed slots), and the artwork you supplied is the orb — cropped to the
drawing and zoomed so it fills the tile instead of floating in the middle of a big empty plate. Your icon
is also the brand mark on the first screen, so the thing you meet in the intro is the thing that shows up
over your keyboard.

## The fast drag

You said it: it moves, but when you drag fast it lags a bit and **sticks in between**. Both causes were
real.

**Every move event wrote the window.** A fast finger produces more move events than there are frames, and
the platform delivers them in bursts; each one ended in its own `updateViewLayout` — a binder round trip
through the window manager plus the relayout behind it. Once that costs more than the finger takes to
move, the backlog feeds itself: stale events pile up, every one is a transaction the orb does not need, and
the orb stalls and then jumps. A move is not a write now, it is a **destination**: everything that arrives
inside one frame collapses to the last position, one frame writes the window once, and what gets written is
always the newest position you asked for — never a replay of somewhere your finger has already left. And
nothing is delayed by it: a window position only reaches the screen at a frame boundary anyway.

**The accessibility watchdog was ticking through the gesture.** Every 600 ms Typorb re-checks the screen to
decide whether it should still be there, and each check is a pair of accessibility queries — the focused
field, the window list — on the same main thread that has to hand the drag its next position. That is a
dropped frame arriving punctually, every 600 ms. It now skips its tick while a finger is on the orb and
evaluates on the next one instead. A keyboard does not appear or disappear mid-drag; nothing is lost.

## Why the text still was not arriving

One genuinely broken link in the chain, and it was the worst possible one.

**`ACTION_SET_TEXT` — the strongest route — was being switched off exactly where it works best.** A field
that reports no text is *empty*: `getText()` returns `null` for a box you have not typed in yet, which is
the fresh chat box, the empty search field, the note with no first word — the fields this app is opened on.
Typorb was reading that `null` as "this field will not say what it holds" and skipping the rebuild route
entirely, falling back to a clipboard paste instead. The two readings are opposites, not synonyms: joining
an *empty* field with a transcript gives the bare transcript, so a proven-empty field is the safest
possible place to write. A hint published as the field's own text (some apps do that) was also disabling
the route; both now count as empty. Password fields are the one real exception and keep the insertive
routes only.

Also: a screen that cannot be read at all is now named separately in the debug console (`field content
unreadable` vs `password field, rebuild skipped`), so the next report says which of the two happened.

## Install

The APK is signed with this build's key. If Android refuses to upgrade over an older Typorb, uninstall that
one first — earlier builds were signed with a per-run CI key.

If the words still do not appear after this, open **Settings → Debug console** and read the `Injection`
row. It names the route that won and the field it went into, for example
`inserted via ACTION_SET_TEXT · EditText in com.whatsapp · live focus (system findFocus)`. `Copy report`
copies the whole console, and one line of it decides what gets fixed next.

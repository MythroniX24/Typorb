# Typorb v1.3.11 — the drag, the tap, and the words

Three things were reported from the phone, and this build answers all three.

## 1. The drag works in every direction now

The orb was walled off from the keyboard: it could not be dragged below a line 16dp above the IME.
Its **default position is exactly that line**, so a downward drag moved it 14dp and stopped. On a
phone that is not felt as a boundary — it is felt as a drag that does not work, which is what the
report said.

- The keyboard still decides where the orb *starts*. It no longer decides where it can go.
- A keyboard cannot hide the orb anyway: this window is an accessibility overlay, which is layered
  **above** the IME, so an orb parked over the keyboard is still on top and still touchable.
- The only rule left is that the pill stays fully on screen, so it can never be left half off an edge.

Two other things were making the drag unreliable and rough:

- **A long press was stealing it.** The long press resolved at 420ms — *under* the platform's own
  500ms timeout — and once it had fired, movement was ignored for the rest of the gesture. Resting a
  finger on the orb for two fifths of a second and then moving is exactly what a person does when
  they are about to move something, so the orb "just would not drag". It is 700ms now, and a fired
  long press can no longer stand in a drag's way.
- **The screen bounds were re-queried on every move.** That query is a call into the system, and a
  drag asks up to 120 times a second on the thread that also has to hand the new position back. It is
  cached now, and a window write the system refuses is rolled back instead of being remembered as
  done — which used to make the *next* drag start from a position the orb was not at.

Also: the orb no longer grows 6% while you carry it. An orb that inflates under the finger reads as
lag, not as feedback.

## 2. The tap animation was deleted, and rebuilt

You asked for the old animation to go first, and it did — all of it. What replaced it is built on one
rule: **nothing animates unless something is happening.**

- **The window is no longer resized during an animation.** The old build reported the pill's box on
  every animation frame and re-positioned the window on each report. On a cheap phone that is a
  stream of system calls at the frame rate, and the one frame the window fell behind was the pill
  being cut off by its own window — the tear on every tap. Now the window is written **twice** per
  state change: once to the box that fits both the pill leaving and the pill arriving, once more when
  the morph is over. No frame can outrun it.
- **Nothing runs while the orb is at rest.** The idle orb used to run two infinite animations — a
  pulsing glow and a geometric "breath" — which recomposed it every frame of every drag. It is
  completely still now.
- **The tap answer**, in order: the surface morphs to its new size on one easing curve, the border
  colour moves with it, the contents crossfade (the old one leaves in 80ms, the new one arrives after
  a 70ms beat so it is never squeezed inside a capsule that is still opening), and a soft indigo
  **sheen sweeps across the glass once** — the acknowledgement that the tap landed.
- The orb tightens to 94% while your finger is on it, and the waveform bars now rise as a wave
  instead of appearing all at once.

## 3. Text that does not land

The injection ladder was writing the same sentence twice in one specific case, and could give up on a
write that had actually worked — both because the *only* thing it ever asked "did that land?" was the
node it had written to, which can go stale between the write and the read-back.

- The read-back now asks **two** questions: what that node says, and what the field the *system* is
  pointing at right now says. A stale node used to report a successful write as a failure.
- The ladder is retried **once** (after 260ms) — but **only when no route reported a write at all**. A
  field that was not ready yet is worth another pass; a field that took the text and hid it is not,
  because writing again on top of a write the field will not confirm is how one dictation arrives
  twice. When that happens the text goes to the clipboard intact and the pill says so.

## If the words still do not appear

Open **Settings → Debug console** and read the `Injection` row. It names the route that won and the
field it went into, for example:

```
inserted via ACTION_SET_TEXT · EditText in com.whatsapp · live focus (system findFocus)
```

If it says `COPIED_TO_CLIPBOARD`, the text is on your clipboard — long-press the field and paste, or
**long-press the orb** to type the same words again. Either line tells us exactly where it stopped,
and that is the one thing a build cannot tell us without your phone.

## Install

The APK is signed with this build's key. If you are upgrading from an earlier build and Android
refuses the install, uninstall Typorb first — previous builds were signed with a per-run CI key.

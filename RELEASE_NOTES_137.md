**Install this one — it supersedes v1.3.6**, which had a small bug where a second failure message could be drawn clipped inside the previous one's capsule width.

## What's fixed

**Tapping the orb now animates instead of breaking the layout.** The overlay window *is* the pill's rectangle, so tapping it is also a resize — it now morphs between states (~190 ms) instead of snapping a moment later, the pill no longer re-fades on every waveform frame, and a failure capsule widens to fit its message so it can actually be read.

**A dictation can no longer vanish.** If no editable field can be reached, or a field refuses both set-text and paste, the text is left on the clipboard and the pill says `Copied — long-press to paste.` The focus lookup now searches every window, refuses nodes inside the keyboard's own windows (typing into an IME search box looks exactly like "my text never appeared"), and retries for ~0.7 s before giving up.

**Both engines, and switching between them.** Spoken language is now a setting (Control → *Spoken language*): **Auto** lets Whisper detect the language per recording, which is what handles Hinglish, with English/Hindi available when detection guesses wrong. The offline engine re-checks which model files to load on every take, so a freshly downloaded int8 model is used immediately instead of the full-precision weights bundled in the APK staying resident — and Control now warns when Offline is selected without a downloaded model.

## If text still does not appear

Settings → Debug console has a second half: **Last dictation**, **Audio captured**, **Transcript size** and **Injection**. The stage names exactly how far the take got — still recording, engine failure (with the verbatim error), or injection — and **Copy report** puts all of it on the clipboard.

## Install

Uninstall the previous Typorb first: every build so far carries a different signing key, so Android refuses to upgrade in place. Afterwards re-enable the accessibility service.

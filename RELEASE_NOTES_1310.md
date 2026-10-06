**Hotfix.** v1.3.9 crashed on launch of the overlay itself — this release fixes exactly that.

## The crash

```
java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from com.typorb.overlay.d
```

The orb window never finished attaching, so on 1.3.9 the pill could not appear at all: the crash lands
on the main thread the moment the window is added, before drag, animation, or dictation gets a chance
to run.

## Why it happened

v1.3.9 introduced the drag host — a wrapper view that now sits between the window and the pill so the
drag can be tracked in raw screen coordinates. Compose does not read its lifecycle from the view it
draws into; it reads it from the view it considers the *window's content*. It finds that by walking up
looking for the `android.R.id.content` frame — **which an overlay window does not have** — so it lands
on the drag host and resolves `ViewTreeLifecycleOwner` from *that* view.

The three view-tree owners were being set on the ComposeView only. Before the drag host existed the
ComposeView *was* the root, so the lookup happened to find them; with the wrapper in between, the lookup
returned `null` and threw. The owners are now set on the window root as well, so no lookup anywhere in
the chain can miss.

## Install

Uninstall the previous Typorb first: every build so far carries a different signing key, so Android
refuses to upgrade in place. Afterwards re-enable the accessibility service.

If the words still do not land in a text box, send the **Injection** row from Settings → Debug console —
it names the route used and the field it went into.

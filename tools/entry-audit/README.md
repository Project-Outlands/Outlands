# entry-audit

Which vanilla methods does the pack call that still reach real OpenGL?

The shim patches vanilla's own bodies, so a mod that calls `GlStateManager.enableBlend` or `TextureManager.bindTexture`
goes through the patches. A mod that calls something we did **not** patch — or the lower-level helper underneath one —
reaches `liblwjgl_opengl`, and in a Vulkan window there is no context there. The result is a SIGSEGV, not an exception,
so the log never says which call it was. Three of these have happened already:

| call | who | outcome |
|---|---|---|
| `glUseProgramObjectARB` | Immersive Intelligence | SIGSEGV, fixed by a hook |
| `OpenGlHelper.setActiveTexture` | TurretMod (SanLib) | SIGSEGV, fixed by a hook |
| `glActiveTexture` after a closed backend | any | fixed by the CLOSED state |

So the question "how many more are there?" is asked by measurement, not by reading.

## What it does

1. Parses every class of every mod jar for its constant-pool method references into `net/minecraft/client/renderer/**`
   and `net/minecraft/client/shader/**` — the classes that mediate GL.
2. Resolves each srg name to its MCP name with `srg-lookup` (the same mapping source a crash report would use).
3. Reads the patched body out of `projects/mcpbase/patched` and classifies it:
   - **hooked** — the body mentions a backend (`FrameStates`, `TextureBackends`, `DisplayLists`, …), so the mod's call is
     recorded and no GL is issued;
   - **delegates** — no backend of its own and no GL either: it calls other vanilla methods, and those are hooked;
   - **hole** — no backend and a direct `GL11.`/`ARB*.`/`EXT*.` call: **a mod calling this segfaults**.

## Usage

```sh
python3 tools/entry-audit/audit.py <pack>/mods            # reads every jar, a few minutes
python3 tools/entry-audit/audit.py <pack>/mods --reuse    # classify the cached references again
```

Exit status is 1 when there is at least one hole, so it can gate a release check.

## Self-test

Point it at the **unpatched** tree and it has to report the holes the patches close:

```sh
python3 tools/entry-audit/audit.py <pack>/mods --patched projects/mcpbase/minecraft --reuse
# covered: 403    unguarded real-GL entry points: 111
#     1080  ...GlStateManager  popMatrix  ()V
#     1078  ...GlStateManager  pushMatrix ()V
```

Without that, "0 holes" would also be what a broken classifier prints.

## What it cannot see

- **Inherited methods.** A call to `GuiScreen.drawTexturedModalRect` resolves to `Gui`, not `GuiScreen`, so it lands in
  the unresolved list. The tool reports how many those are instead of pretending they are covered.
- **Instance fields into inner classes.** `GlStateManager.enableBlend` reads as "delegates" because it sets
  `blendState.blend`, and the hook is in `BooleanState.setState` in another class. Static reading cannot follow that;
  the entries were checked by hand.
- **Whether a hook is *correct*.** This finds calls that reach real GL, nothing more. A hooked method that records the
  wrong value is a different bug, and the frame dump is what finds those.

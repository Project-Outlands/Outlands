# tools

## `jfr-alloc.py`

Sums a JFR recording's allocation samples into three tables: what was allocated, where, and — for one class — who
asked for it. The numbers are sampled and weighted by JFR, so they compare runs of the same settings; they are not a
heap measurement.

```bash
# the three tables
tools/jfr-alloc.py /tmp/outlands.jfr

# one class, broken down by its callers (the shape used to find EnderIO and IC2 in ItemStack allocations)
tools/jfr-alloc.py /tmp/outlands.jfr --class net.minecraft.item.ItemStack

# GC instead: pause percentiles and heap configuration
tools/jfr-alloc.py /tmp/outlands.jfr --gc
```

Make the recording with:

```
-XX:StartFlightRecording=filename=/tmp/outlands.jfr,settings=profile,dumponexit=true
```

`profile` is what samples allocations; `dumponexit=true` is what makes the file appear when the game is closed
normally. The `jfr` tool from the same JDK must be on `PATH` or under `JAVA_HOME/bin`.

The class and method names come out as the runtime spells them: `func_110993_a` rather than `uploadTextureImageSubImpl`
unless the recording was made in a development run. `tools/mc-srg-lookup` (the skill of the same name) or the
`srg2mcp.tsrg` under `projects/outlands/build/mappings/` translate them.

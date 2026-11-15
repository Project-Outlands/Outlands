#!/usr/bin/env python3
"""Find the vanilla methods a modpack calls that still reach real OpenGL.

The shim works by patching vanilla's own bodies: `GlStateManager.enableBlend` records the state instead of calling
`GL11.glEnable`, `TextureManager.bindTexture` forwards to the backend, and so on. A mod that calls those same methods
goes through the patches and is fine. A mod that calls a method we did **not** patch - or the lower-level helper
underneath one - reaches `liblwjgl_opengl`, and in a Vulkan window there is no context there. That is a SIGSEGV, not
an exception, so nothing in the log says which call it was.

This tool answers "which of those are left?" by reading the pack, not by guessing:

  1. every class in every mod jar is parsed for its constant-pool method references into `net/minecraft/**`;
  2. each srg name is resolved to its MCP name (`srg-lookup`);
  3. the patched vanilla body is read from `projects/mcpbase/patched` and classified:
       - hooked   - the body mentions a backend (`FrameStates`, `TextureBackends`, `DisplayLists`, ...),
       - delegates- no backend and no GL of its own: it calls other vanilla methods, which are hooked (checked by hand
                    for the ones that matter, since a static reading cannot follow instance fields into inner classes),
       - hole     - no backend and a direct `GL11.`/`ARB*.`/`EXT*.` call: **a mod calling this segfaults**.

Usage:
    python3 tools/entry-audit/audit.py <mods-dir> [--patched <dir>]

The first run over a large pack takes a few minutes: it reads every class of every jar. Results are cached next to the
script (`refs.json`) so the classification can be re-run alone with `--reuse`.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import zipfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "refs.json")

# Every class that mediates GL for a mod. These are the entry points the shim patches; anything a mod calls on them is
# expected to be hooked.
ENTRY_CLASSES = (
    "net/minecraft/client/renderer/",
    "net/minecraft/client/shader/",
)

# What "hooked" looks like in a patched body. Kept as bare class names: a patch refers to them fully qualified, and a
# false positive here would be a method that mentions a backend without using it, which does not happen in practice.
BACKEND = (
    "FrameStates", "FrameState", "ClientArrays", "TextureBackends", "TextureBackend", "ShimGl",
    "RenderBackendSelection", "FramebufferBackend", "FramebufferBackends", "GlShaders", "DisplayLists",
    "DisplayListBackend", "VertexBufferBackends", "VertexBufferBackend", "VertexStreamBackends",
    "VertexStreamBackend", "ChunkMeshCapture", "GlAnswers", "Screenshots", "SpirvResources",
)

GL = re.compile(
    r"\b(GL11|GL13|GL14|GL15|GL20|GL30|GL31|GL32|GL33|GL40|GL41|GL42|GL43|GL44|GL45|GL46"
    r"|ARB[A-Za-z]+|EXT[A-Za-z]+|NV[A-Za-z]+|KHR[A-Za-z]+|AMD[A-Za-z]+)\.\w+\("
)


def parse_refs(data):
    """Method references out of one class file: (owner, name, descriptor)."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return []
    pos = 8
    count = int.from_bytes(data[pos:pos + 2], "big")
    pos += 2
    utf8, classes, nat, refs = {}, {}, {}, []
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = int.from_bytes(data[pos:pos + 2], "big")
            utf8[i] = data[pos + 2:pos + 2 + length].decode("utf-8", "replace")
            pos += 2 + length
        elif tag == 7:
            classes[i] = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
        elif tag == 12:
            nat[i] = (int.from_bytes(data[pos:pos + 2], "big"), int.from_bytes(data[pos + 2:pos + 4], "big"))
            pos += 4
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 17, 18):
            if tag in (9, 10, 11):
                refs.append((int.from_bytes(data[pos:pos + 2], "big"),
                             int.from_bytes(data[pos + 2:pos + 4], "big")))
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        else:
            raise ValueError(f"constant pool tag {tag}")
        i += 1
    out = []
    for class_index, nat_index in refs:
        owner = utf8.get(classes.get(class_index, 0), "")
        name_i, desc_i = nat.get(nat_index, (0, 0))
        name, desc = utf8.get(name_i, ""), utf8.get(desc_i, "")
        if owner and name:
            out.append((owner, name, desc))
    return out


def methods_of(path):
    """name -> [body] for one patched source file. Crude brace matching: enough for 1.12.2 vanilla's style."""
    lines = open(path, encoding="utf-8").read().split("\n")
    out = defaultdict(list)
    for i, ln in enumerate(lines):
        m = re.match(r"\s*(?:public|private|protected)?\s*(?:static\s+)?(?:final\s+)?[\w\[\]<>., ?]+\s+(\w+)\s*\(", ln)
        if not m or m.group(1) in ("if", "for", "while", "switch", "catch", "return", "new"):
            continue
        depth, body, j = 0, [], i
        while j < len(lines):
            body.append(lines[j])
            depth += lines[j].count("{") - lines[j].count("}")
            if depth == 0 and "{" in "\n".join(body) and len(body) > 1:
                break
            j += 1
        out[m.group(1)].append("\n".join(body))
    return out


def collect(mods_dir):
    hits = defaultdict(lambda: defaultdict(int))
    jars = sorted(glob.glob(os.path.join(mods_dir, "*.jar")))
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if not name.endswith(".class"):
                        continue
                    for owner, method, desc in parse_refs(z.read(name)):
                        if owner.startswith(ENTRY_CLASSES) and method.startswith("func_"):
                            hits[owner][(method, desc)] += 1
        except zipfile.BadZipFile:
            continue
    out = {o: [{"name": n, "desc": d, "count": c} for (n, d), c in sorted(rows.items(), key=lambda kv: -kv[1])]
           for o, rows in hits.items()}
    json.dump(out, open(CACHE, "w"))
    return out


def resolve(names):
    open("/tmp/entry-audit-names.txt", "w").write("\n".join(names) + "\n")
    proc = subprocess.run(["srg-lookup", "-R", "/tmp/entry-audit-names.txt"], capture_output=True, text=True)
    out = {}
    for ln in proc.stdout.split("\n"):
        m = re.match(r"^(\w+) \((func_\w+)\)$", ln.strip())
        if m:
            out[m.group(2)] = m.group(1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mods", help="the pack's mods directory")
    ap.add_argument("--patched", default=os.path.join(HERE, "..", "..", "projects", "mcpbase", "patched"))
    ap.add_argument("--reuse", action="store_true", help="classify the cached references instead of re-reading the jars")
    args = ap.parse_args()

    refs = json.load(open(CACHE)) if args.reuse and os.path.exists(CACHE) else collect(args.mods)
    srg = sorted({r["name"] for rows in refs.values() for r in rows})
    name_map = resolve(srg)
    print(f"{len(refs)} classes, {sum(len(v) for v in refs.values())} method references, "
          f"{len(name_map)}/{len(srg)} names resolved", file=sys.stderr)

    cache, holes, covered, unresolved = {}, [], 0, []
    for owner, rows in refs.items():
        path = os.path.join(args.patched, owner + ".java")
        if not os.path.exists(path):
            unresolved.append((owner, len(rows)))
            continue
        if owner not in cache:
            cache[owner] = methods_of(path)
        for r in rows:
            mcp = name_map.get(r["name"], r["name"])
            bodies = cache[owner].get(mcp)
            if not bodies:
                unresolved.append((f"{owner}.{mcp}", r["count"]))
                continue
            if any(b in body for b in BACKEND for body in bodies) or not any(GL.search(b) for b in bodies):
                covered += 1
            else:
                holes.append((owner, mcp, r["desc"], r["count"]))

    holes.sort(key=lambda h: -h[3])
    print(f"\ncovered: {covered}    unguarded real-GL entry points: {len(holes)}")
    for owner, mcp, desc, count in holes:
        print(f"  {count:5d}  {owner:56s} {mcp:24s} {desc}")
    if unresolved:
        print(f"\nunresolved (getters, constructors, inherited methods - not GL entry points): "
              f"{len(unresolved)} entries, {sum(c for _, c in unresolved)} references")
    return 1 if holes else 0


if __name__ == "__main__":
    sys.exit(main())

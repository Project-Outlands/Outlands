#!/usr/bin/env python3
"""Find the raw GL calls a modpack makes that the shim's ruleset does not route.

`audit.py` asks the same question about vanilla's entry points - the methods a mod calls on `GlStateManager` or
`TextureManager`. This one asks it about `org.lwjgl.opengl.*` itself: a mod that calls `GL11.glGenTextures` or
`GL20.glCreateProgram` is not going through vanilla at all, and if the ruleset has no entry for it the call reaches
`liblwjgl_opengl`. In a Vulkan window there is no context there, so it is a SIGSEGV with no Java frame pointing at the
shim - which is exactly how TurretMod's turret camera died inside `GL11C.nglGenTextures`.

The ruleset is read from `FrameShimRules.java` rather than from the running game, because the question is what the
shipped rules cover, not what one session happened to load.

Usage:
    python3 tools/entry-audit/gl-surface.py <mods-dir> [--rules <FrameShimRules.java>]

Exit status is 1 when a real GL call is uncovered. Window and context management (`Display`, `Drawable`,
`GLContext`, `ContextCapabilities`) is reported separately: those are the LWJGL 2 compatibility layer's own API, not
GL calls, and the shim is not the thing that answers them.
"""

import argparse
import glob
import os
import re
import sys
import zipfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

# Not GL calls: window and context management, answered by the LWJGL 2 compatibility layer.
NOT_GL = ("Display", "Drawable", "SharedDrawable", "GLContext", "ContextCapabilities", "PixelFormat", "Pbuffer",
          "GL", "GLCapabilities")

# Classes that *implement* an OpenGL backend rather than consume one. They are supposed to call real GL: that is what
# they are for, and under the Vulkan backend they are simply not the code path in use. Listed by package prefix so the
# report says which jar was skipped and why, instead of hiding it.
GL_IMPLEMENTERS = (
    "com/dhj/actinium/",
    "com/gtnewhorizons/angelica/",
    "net/coderbot/iris/debug/",
    # GTNHLib's VAO/VBO/shader wrappers are used by the GL backends above and by nothing else in the pack - measured by
    # listing every class that references them, which finds only Actinium and Angelica.
    "com/gtnewhorizon/gtnhlib/client/opengl/",
    "com/gtnewhorizon/gtnhlib/client/renderer/vao/",
    "com/gtnewhorizon/gtnhlib/client/renderer/vbo/",
    "com/gtnewhorizon/gtnhlib/client/renderer/shader/",
    "com/gtnewhorizon/gtnhlib/client/renderer/vertex/",
    "com/gtnewhorizon/gtnhlib/client/renderer/textures/",
    "com/gtnewhorizon/gtnhlib/client/renderer/",
    # The GL state manager Angelica runs on, and the LWJGL 3 shim itself: both are the OpenGL path by definition.
    "com/mitchej123/glsm/",
    "com/mitchej123/lwjgl/",
)

# The ruleset's per-owner helpers, and the constant each one retargets away from.
KIND_OWNER = {"entry": "GL11", "arb": "ARB_SHADER_OBJECTS", "arbBuffer": "ARB_BUFFER_OBJECT",
              "gl13": "GL13", "gl14": "GL14", "gl15": "GL15", "gl20": "GL20", "gl30": "GL30"}


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


def rules(rules_path):
    src = open(rules_path, encoding="utf-8").read()
    owners = dict(re.findall(r'public static final String (\w+) = "(org/lwjgl/opengl/[\w]+)";', src))
    covered = set()
    for m in re.finditer(r"(?:^|\s)(" + "|".join(KIND_OWNER) + r')\(\s*"(\w+)"\s*,\s*"([^"]+)"\)', src):
        covered.add((owners[KIND_OWNER[m.group(1)]], m.group(2), m.group(3)))
    for m in re.finditer(r'new RetargetEntry\((\w+),\s*"(\w+)",\s*"([^"]+)",\s*FACADE\)', src):
        covered.add((owners.get(m.group(1), m.group(1)), m.group(2), m.group(3)))
    return covered


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mods")
    ap.add_argument("--rules", default=os.path.join(HERE, "..", "..", "src", "main", "java",
                                                    "net", "minecraftforge", "lwjgl", "shim", "FrameShimRules.java"))
    args = ap.parse_args()

    covered = rules(args.rules)
    print(f"the ruleset covers {len(covered)} (owner, name, descriptor) triples", file=sys.stderr)

    hits = defaultdict(lambda: defaultdict(set))
    for jar in sorted(glob.glob(os.path.join(args.mods, "*.jar"))):
        try:
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if not name.endswith(".class"):
                        continue
                    for owner, method, desc in parse_refs(z.read(name)):
                        if owner.startswith("org/lwjgl/opengl/") and not method.startswith("n"):
                            hits[(owner, method, desc)][os.path.basename(jar)].add(name[:-6])
        except zipfile.BadZipFile:
            continue

    missing, not_gl, implementers = [], [], []
    for key, jars in hits.items():
        if key in covered:
            continue
        count = sum(len(v) for v in jars.values())
        sites = sorted({c for v in jars.values() for c in v})
        if key[0].split("/")[-1] in NOT_GL:
            not_gl.append((count, key, sites))
        elif any(site.startswith(GL_IMPLEMENTERS) for site in sites):
            implementers.append((count, key, sites))
        else:
            missing.append((count, key, sites))

    missing.sort(key=lambda x: -x[0])
    print(f"\ndistinct raw GL calls in the pack: {len(hits)}    uncovered: {len(missing)}")
    for count, (owner, name, desc), sites in missing:
        print(f"  {count:5d}  {owner.split('/')[-1]:20s} {name:28s} {desc[:40]:40s} {sites[0] if sites else ''}")
    print(f"\nOpenGL backends implemented by mods (supposed to call real GL): {len(implementers)}")
    for count, (owner, name, desc), sites in sorted(implementers, key=lambda x: -x[0])[:5]:
        print(f"  {count:5d}  {owner.split('/')[-1]:20s} {name:28s} {sites[0] if sites else ''}")
    print(f"\nnot GL calls, answered by the LWJGL 2 compatibility layer: {len(not_gl)}")
    for count, (owner, name, desc), sites in sorted(not_gl, key=lambda x: -x[0])[:5]:
        print(f"  {count:5d}  {owner.split('/')[-1]:20s} {name}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())

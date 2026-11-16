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


CAPABILITY = re.compile(r"ContextCapabilities\.(\w+)")


def declared(rules_path):
    """The capability names the shim contract declares on, read from GlAnswers.declareShimContract()."""
    answers = os.path.join(os.path.dirname(os.path.abspath(rules_path)), "..", "..", "client", "render", "GlAnswers.java")
    answers = os.path.normpath(answers)
    if not os.path.exists(answers):
        return None
    src = open(answers, encoding="utf-8").read()
    body = src.split("declareShimContract()", 1)[-1]
    return set(re.findall(r"caps\.(\w+)\s*=\s*true", body))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mods")
    ap.add_argument("--rules", default=os.path.join(HERE, "..", "..", "src", "main", "java",
                                                    "net", "minecraftforge", "lwjgl", "shim", "FrameShimRules.java"))
    args = ap.parse_args()

    covered = rules(args.rules)
    print(f"the ruleset covers {len(covered)} (owner, name, descriptor) triples", file=sys.stderr)

    hits = defaultdict(lambda: defaultdict(set))
    # Per jar: which capabilities each class asks about, and which classes it references. A call behind a capability
    # the contract declares off never executes, and reporting it as a gap would be crying wolf - measured: every one
    # of ThaumcraftFix's nine vertex-array calls sits behind GLContext.getCapabilities().OpenGL33, and the shim
    # declares 2.1.
    caps_of = defaultdict(lambda: defaultdict(set))
    refs_of = defaultdict(lambda: defaultdict(set))
    for jar in sorted(glob.glob(os.path.join(args.mods, "*.jar"))):
        short = os.path.basename(jar)
        try:
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if not name.endswith(".class"):
                        continue
                    cls = name[:-6]
                    for owner, method, desc in parse_refs(z.read(name)):
                        refs_of[short][cls].add(owner)
                        if owner.endswith("ContextCapabilities"):
                            caps_of[short][cls].add(method)
                        if owner.startswith("org/lwjgl/opengl/") and not method.startswith("n"):
                            hits[(owner, method, desc)][short].add(cls)
        except zipfile.BadZipFile:
            continue

    on = declared(args.rules)
    gated = {}
    for short, by_class in caps_of.items():
        # A class that asks about a capability the contract declares off takes its fallback path. Two hops *out* from
        # those classes - to the classes they reference - covers the helper they would have called, which is where the
        # calls usually are: ThaumcraftFix's mixin asks about OpenGL33 and its calls are in RiftData.
        gated_caps = {cls: {n for n in names if on is None or n not in on} for cls, names in by_class.items()}
        gated_caps = {cls: caps for cls, caps in gated_caps.items() if caps}
        for _ in range(2):
            for cls, caps in list(gated_caps.items()):
                for ref in refs_of[short].get(cls, ()):
                    if ref in refs_of[short] and ref not in gated_caps:
                        gated_caps[ref] = caps
        for cls, caps in gated_caps.items():
            gated[(short, cls)] = sorted(caps)

    missing, not_gl, implementers, behind = [], [], [], []
    for key, jars in hits.items():
        if key in covered:
            continue
        count = sum(len(v) for v in jars.values())
        sites = sorted({c for v in jars.values() for c in v})
        if key[0].split("/")[-1] in NOT_GL:
            not_gl.append((count, key, sites))
        elif any(site.startswith(GL_IMPLEMENTERS) for site in sites):
            implementers.append((count, key, sites))
        elif all((short, site) in gated for short, v in jars.items() for site in v):
            caps = sorted({c for short, v in jars.items() for site in v for c in gated[(short, site)]})
            behind.append((count, key, sites, caps))
        else:
            missing.append((count, key, sites))

    missing.sort(key=lambda x: -x[0])
    print(f"\ndistinct raw GL calls in the pack: {len(hits)}    uncovered: {len(missing)}")
    for count, (owner, name, desc), sites in missing:
        print(f"  {count:5d}  {owner.split('/')[-1]:20s} {name:28s} {desc[:40]:40s} {sites[0] if sites else ''}")
    if behind:
        print(f"\nbehind a capability the contract declares off (never executes): {len(behind)}")
        for count, (owner, name, desc), sites, caps in sorted(behind, key=lambda x: -x[0])[:8]:
            print(f"  {count:5d}  {owner.split('/')[-1]:20s} {name:28s} {sites[0] if sites else '':44s} {','.join(caps)}")
    print(f"\nOpenGL backends implemented by mods (supposed to call real GL): {len(implementers)}")
    for count, (owner, name, desc), sites in sorted(implementers, key=lambda x: -x[0])[:5]:
        print(f"  {count:5d}  {owner.split('/')[-1]:20s} {name:28s} {sites[0] if sites else ''}")
    print(f"\nnot GL calls, answered by the LWJGL 2 compatibility layer: {len(not_gl)}")
    for count, (owner, name, desc), sites in sorted(not_gl, key=lambda x: -x[0])[:5]:
        print(f"  {count:5d}  {owner.split('/')[-1]:20s} {name}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Derives the LWJGL2 -> LWJGL3 rewrite table from class files (pure stdlib, no ASM).

usage: derive.py --l2 <lwjgl.jar> <lwjgl_util.jar> --l3 <lwjgl3 jars...>
                  --seed seed.tsv --out rewrite-table.tsv [--emit-java <src/main/java>]

table columns: kind, owner, name, desc, targetOwner, targetName, targetDesc, recipe
recipe ops (index = target parameter position):
  insConst:<i>:<owner>/<FIELD>      push a constant, shifting the source parameters right
  typeFromUnsigned:<i>:<owner>/<FIELD>  boolean unsigned -> FIELD + unsigned
  widen:<i>                        int -> long
  addr:<i>                         Buffer -> long via MemoryUtil.memAddress
  drop:<i>                         drop source parameter i
"""
import argparse
import hashlib
import os
import struct
import sys
import zipfile
from collections import defaultdict

CONST_TAGS = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
              12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


class ClassFile:
    __slots__ = ('name', 'super_name', 'interfaces', 'methods', 'fields', 'refs')

    def __init__(self):
        self.name = None
        self.super_name = None
        self.interfaces = []
        self.methods = set()
        self.fields = set()
        self.refs = set()


def _modified_utf8(b):
    # class file names are ASCII in practice
    try:
        return b.decode('utf-8')
    except UnicodeDecodeError:
        return b.decode('latin-1')


def parse_class(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    pos = 8
    cp_count = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    utf8 = {}
    class_at = {}
    nat = {}
    refs = []
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            ln = struct.unpack_from('>H', data, pos)[0]
            pos += 2
            utf8[i] = _modified_utf8(data[pos:pos + ln])
            pos += ln
        elif tag == 7:
            class_at[i] = struct.unpack_from('>H', data, pos)[0]
            pos += 2
        elif tag in (9, 10, 11):
            ci, nti = struct.unpack_from('>HH', data, pos)
            pos += 4
            refs.append((i, tag, ci, nti))
        elif tag == 12:
            nat[i] = struct.unpack_from('>HH', data, pos)
            pos += 4
        elif tag == 8:
            pos += 2
        elif tag in (5, 6):
            pos += 8
            i += 1
        else:
            pos += CONST_TAGS[tag]
        i += 1

    cf = ClassFile()
    cf.refs = {(utf8[class_at[ci]], utf8[nat[nti][0]], utf8[nat[nti][1]])
               for (_i, _tag, ci, nti) in refs if ci in class_at and nti in nat}

    def u2(p):
        return struct.unpack_from('>H', data, p)[0]

    access = u2(pos); pos += 2
    cf.name = utf8[class_at[u2(pos)]]; pos += 2
    sup = u2(pos); pos += 2
    cf.super_name = utf8[class_at[sup]] if sup else None
    n_if = u2(pos); pos += 2
    for _ in range(n_if):
        cf.interfaces.append(utf8[class_at[u2(pos)]]); pos += 2
    for _ in range(2):  # fields, then methods
        n = u2(pos); pos += 2
        target = cf.fields if _ == 0 else cf.methods
        for _m in range(n):
            pos += 2  # access
            nm = utf8[u2(pos)]; pos += 2
            ds = utf8[u2(pos)]; pos += 2
            target.add((nm, ds))
            n_attr = u2(pos); pos += 2
            for _a in range(n_attr):
                pos += 2  # name index
                ln = struct.unpack_from('>I', data, pos)[0]; pos += 4 + ln
    return cf


def load_jars(paths):
    out = {}
    for p in paths:
        with zipfile.ZipFile(p) as zf:
            for n in zf.namelist():
                if not n.endswith('.class') or n.startswith('META-INF/'):
                    continue
                try:
                    cf = parse_class(zf.read(n))
                except Exception:
                    continue
                if cf.name:
                    out[cf.name] = cf
    return out


def ancestors(idx, owner):
    seen, stack, out = set(), [owner], []
    while stack:
        c = stack.pop()
        if c in seen:
            continue
        seen.add(c)
        out.append(c)
        cf = idx.get(c)
        if cf:
            if cf.super_name:
                stack.append(cf.super_name)
            stack.extend(cf.interfaces)
    return out


def resolve(idx, owner, name, desc):
    for c in ancestors(idx, owner):
        cf = idx.get(c)
        if cf and (name, desc) in cf.methods:
            return c
    return None


def parse_desc(desc):
    i, ps = 1, []
    while desc[i] != ')':
        j = i
        while desc[j] == '[':
            j += 1
        if desc[j] == 'L':
            j = desc.index(';', j)
        ps.append(desc[i:j + 1])
        i = j + 1
    return ps, desc[i + 1:]


FAMILY = set('fiudvsxbn')


def family_targets(idx, owner, name, desc):
    """L3 members that split glX into glXv/glXf/glXiv... with an identical descriptor."""
    hits = []
    for c in ancestors(idx, owner):
        cf = idx.get(c)
        if not cf:
            continue
        for (nm, ds) in cf.methods:
            if ds != desc or nm == name:
                continue
            if nm.startswith(name):
                suf = nm[len(name):]
                if 0 < len(suf) <= 4 and all(ch in FAMILY for ch in suf):
                    hits.append(nm)
    return sorted(set(hits))


def read_tsv(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding='utf-8') as f:
        for ln, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            p = line.split('\t')
            if len(p) != 8:
                sys.exit(f'{path}:{ln}: expected 8 columns, got {len(p)}')
            rows.append(p)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--l2', nargs='+', required=True)
    ap.add_argument('--l3', nargs='+', required=True)
    ap.add_argument('--seed', default=os.path.join(os.path.dirname(__file__), 'seed.tsv'))
    ap.add_argument('--out', default=os.path.join(os.path.dirname(__file__), 'rewrite-table.tsv'))
    ap.add_argument('--emit-java', metavar='DIR')
    ap.add_argument('--report-unhandled', action='store_true')
    args = ap.parse_args()

    l2 = load_jars(args.l2)
    l3 = load_jars(args.l3)
    print(f'L2 classes={len(l2)}  L3 classes={len(l3)}', file=sys.stderr)

    seed = read_tsv(args.seed)
    rows, seen, unresolved = [], set(), []

    for r in seed:
        rows.append(r)
        seen.add((r[1], r[2], r[3]))

    both = sorted(c for c in l2 if c in l3)
    for cls in both:
        if not cls.startswith('org/lwjgl/'):
            continue
        for (name, desc) in sorted(l2[cls].methods):
            if name.startswith('ngl') or name in ('<init>', '<clinit>'):
                continue
            if (cls, name, desc) in seen:
                continue
            if resolve(l3, cls, name, desc):
                continue  # resolves directly or via inheritance
            fams = family_targets(l3, cls, name, desc)
            if len(fams) == 1:
                rows.append(['RENAME', cls, name, desc, cls, fams[0], desc, '-'])
                seen.add((cls, name, desc))
            else:
                unresolved.append((cls, name, desc, fams))

    # every target must exist in L3
    bad = []
    for r in rows:
        kind, owner, name, desc, towner, tname, tdesc, _recipe = r
        if kind == 'CONVERT':
            if not resolve(l3, towner, tname, tdesc):
                bad.append(r)
        elif kind == 'CTOR':
            if not resolve(l3, towner, tname, tdesc):
                bad.append(r)
        elif kind in ('RENAME', 'ALIAS', 'ADAPTER'):
            if not resolve(l3, towner, tname, tdesc) and not tdesc.startswith('(') is False:
                bad.append(r)
    if bad:
        print('table entries whose target is missing in L3:', file=sys.stderr)
        for r in bad:
            print('  ' + '\t'.join(r), file=sys.stderr)
        sys.exit(1)

    rows.sort(key=lambda r: (r[0], r[1], r[2], r[3]))
    body = ''.join('\t'.join(r) + '\n' for r in rows)
    table_sha = hashlib.sha256(body.encode('utf-8')).hexdigest()
    l3_shas = []
    for p in args.l3:
        with open(p, 'rb') as fh:
            l3_shas.append(f'{os.path.basename(p)} {hashlib.sha256(fh.read()).hexdigest()}')
    with open(args.out, 'w', encoding='utf-8') as f:
        f.write('# kind\towner\tname\tdesc\ttargetOwner\ttargetName\ttargetDesc\trecipe\n')
        f.write('# generated by tools/lwjgl2diff/derive.py, manual entries live in seed.tsv\n')
        f.write(f'# table-sha256: {table_sha}\n')
        for line in l3_shas:
            f.write(f'# l3-sha256: {line}\n')
        f.write(body)

    cnt = defaultdict(int)
    for r in rows:
        cnt[r[0]] += 1
    print(f'rows: {dict(cnt)} -> {args.out}', file=sys.stderr)

    if unresolved:
        print(f'not covered by the table (needs seed or in-place shim): {len(unresolved)}', file=sys.stderr)
        if args.report_unhandled:
            for cls, name, desc, fams in unresolved:
                print(f'  UNHANDLED {cls}.{name}{desc}  family={fams}', file=sys.stderr)

    if args.emit_java:
        emit_java(args.emit_java, rows, l2, table_sha)


def _jstr(s):
    out = []
    for ch in s:
        if ch == '\0':
            out.append('\\0')
        elif ch == '\\':
            out.append('\\\\')
        elif ch == '"':
            out.append('\\"')
        else:
            out.append(ch)
    return '"' + ''.join(out) + '"'


def emit_java(outdir, rows, l2, table_sha):
    legacy = os.path.join(outdir, 'com', 'cleanroommc', 'lwjgl', 'legacy')
    os.makedirs(legacy, exist_ok=True)

    renames = [r for r in rows if r[0] == 'RENAME']
    aliases = [r for r in rows if r[0] == 'ALIAS']
    converts = [r for r in rows if r[0] == 'CONVERT']
    ctors = [r for r in rows if r[0] == 'CTOR']
    adapters = [r for r in rows if r[0] == 'ADAPTER']

    def key(o, n, d):
        return f'key({_jstr(o)}, {_jstr(n)}, {_jstr(d)})'

    with open(os.path.join(legacy, 'RewriteTable.java'), 'w', encoding='utf-8') as f:
        f.write(('''package com.cleanroommc.lwjgl.legacy;

import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL2 -> LWJGL3 call-site rewrite table. Generated by tools/lwjgl2diff/derive.py, do not edit.
 * Verified by :cleanroom:checkLwjglCompatTable.
 */
public final class RewriteTable {

    private RewriteTable() {}

    /** SHA-256 of the table body, must match rewrite-table.tsv. */
    public static final String TABLE_SHA256 = \"%s\";

    /** owner\\0name\\0desc */
    public static String key(String owner, String name, String desc) {
        return owner + '\\0' + name + '\\0' + desc;
    }

    /** Same owner and descriptor, different method name. Value = new name. */
    public static final Map<String, String> RENAME = new HashMap<>(%d * 2);
    /** Same descriptor, different owner and name. Value = "targetOwner\\0targetName". */
    public static final Map<String, String> ALIAS = new HashMap<>(%d * 2);
    /** Descriptor changes. Value = "targetOwner\\0targetName\\0targetDesc\\0recipe". */
    public static final Map<String, String> CONVERT = new HashMap<>(%d * 2);
    /** Constructor call -> static factory. Value = "factoryOwner\\0factoryName\\0factoryDesc". */
    public static final Map<String, String> CTOR = new HashMap<>(%d * 2);
    /** Redirect to an adapter method. Value = "adapterOwner\\0adapterName\\0adapterDesc". */
    public static final Map<String, String> ADAPTER = new HashMap<>(%d * 2);

    static {
''').replace('\\"', '"') % (table_sha, len(renames), len(aliases), len(converts), len(ctors), len(adapters)))
        for kind, target_map in (('RENAME', renames), ('ALIAS', aliases),
                                 ('CONVERT', converts), ('CTOR', ctors), ('ADAPTER', adapters)):
            if not target_map:
                continue
            for (k, owner, name, desc, towner, tname, tdesc, recipe) in target_map:
                if kind == 'RENAME':
                    value = _jstr(tname)
                elif kind == 'ALIAS':
                    value = _jstr(towner + '\0' + tname)
                elif kind == 'CONVERT':
                    value = _jstr(towner + '\0' + tname + '\0' + tdesc + '\0' + recipe)
                else:
                    value = _jstr(towner + '\0' + tname + '\0' + tdesc)
                f.write(f'        {kind}.put({key(owner, name, desc)}, {value});\n')
            f.write('\n')
        f.write('    }\n}\n')

    l2names = sorted(c for c in l2 if c.startswith('org/lwjgl/'))
    with open(os.path.join(legacy, 'Lwjgl2ClassSet.java'), 'w', encoding='utf-8') as f:
        f.write('''package com.cleanroommc.lwjgl.legacy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * All LWJGL2 (lwjgl + lwjgl_util) internal class names. Generated by tools/lwjgl2diff/derive.py, do not edit.
 */
public final class Lwjgl2ClassSet {

    private Lwjgl2ClassSet() {}

    public static final Set<String> NAMES = new HashSet<>(Arrays.asList(
''')
        for i in range(0, len(l2names), 4):
            chunk = ', '.join(_jstr(n) for n in l2names[i:i + 4])
            tail = ',' if i + 4 < len(l2names) else ''
            f.write(f'            {chunk}{tail}\n')
        f.write('    ));\n}\n')

    print(f'emitted RewriteTable.java ({len(rows)} rows) + Lwjgl2ClassSet.java ({len(l2names)} classes)'
          f' -> {legacy}', file=sys.stderr)


if __name__ == '__main__':
    main()

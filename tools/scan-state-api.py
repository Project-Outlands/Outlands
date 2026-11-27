#!/usr/bin/env python3
"""Scan mod jars for references to the block-state / meta / id APIs.

Usage: tools/scan-state-api.py [instances-root]

Reports, per reference kind, how many call sites and how many mods use it. This is how we size the
blast radius of replacing the (block id << 4 | meta) state id: meta collapsing for blocks with more
than sixteen states is already the status quo, so what matters is which mods *persist* an id or
convert states to meta across blocks they do not own.

Reads the constant pool of every class in every jar and counts Fieldref/Methodref entries whose
member name is one of the targets. Member names are unique enough (SRG names are assigned per
member) that the owner does not have to be resolved, which also catches calls on Block subclasses
and super.getMetaFromState().
"""
import collections
import glob
import hashlib
import os
import struct
import sys
import zipfile

TARGETS = {
    # state -> meta and back: what a mod must not be able to do once meta stops being faithful
    'func_176201_c': 'meta_out',
    'getMetaFromState': 'meta_out',
    'func_176203_a': 'meta_in',
    'getStateFromMeta': 'meta_in',
    # 32 bit state ids: an id change is silent corruption if a mod persisted one
    'func_176210_f': 'state_id',
    'getStateId': 'state_id',
    'func_176220_d': 'state_id_by',
    'getStateById': 'state_id_by',
    'field_176229_d': 'state_id_map',
    'BLOCK_STATE_IDS': 'state_id_map',
    # block registry ids: widened by REID, also used as keys in mod data
    'func_149682_b': 'block_id',
    'getIdFromBlock': 'block_id',
    'func_149729_e': 'block_by_id',
    'getBlockById': 'block_by_id',
    # item damage carrying a block state (the classic "ItemBlock meta" pattern)
    'func_77960_j': 'item_meta',
    'getMetadata': 'item_meta',
    'func_77964_b': 'item_meta_set',
    'setItemDamage': 'item_meta_set',
}

CP_TAGS_WITH_TWO_SLOTS = (5, 6)


def parse_refs(data):
    """Yield member names referenced by this class file (from Fieldref/Methodref/InterfaceMethodref)."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        return
    pos = 8
    count = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    utf8 = {}
    refs = []
    index = 1

    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, pos)[0]
            pos += 2
            utf8[index] = data[pos:pos + length].decode('utf-8', 'replace')
            pos += length
        elif tag in (3, 4):
            pos += 4
        elif tag in CP_TAGS_WITH_TWO_SLOTS:
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            if tag in (9, 10, 11):
                refs.append(struct.unpack_from('>HH', data, pos))
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            return
        index += 1

    # second pass over the recorded (class_idx, nat_idx) pairs needs the cp table again
    index = 1
    pos = 8 + 2
    nat = {}
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, pos)[0]
            pos += 2 + length
        elif tag in (3, 4):
            pos += 4
        elif tag in CP_TAGS_WITH_TWO_SLOTS:
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag == 12:
            nat[index] = struct.unpack_from('>H', data, pos)[0]
            pos += 4
        elif tag in (9, 10, 11, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            return
        index += 1

    for _, nat_idx in refs:
        name_idx = nat.get(nat_idx)
        if name_idx is not None and name_idx in utf8:
            yield utf8[name_idx]


VERSION_CACHE = {}
INSTANCES_112 = {'SoS-TD', 'Magic-Sink', 'Magic Sink+', 'outlands-2026.10.25', '1.12.2',
                 'Cleanroom-MMC-instance-0.2.4-alpha', 'IC2C', 'etf', 'Project Red Test'}
INSTANCE_OF = {}


def version_is_112(label):
    version = VERSION_CACHE.get(label)
    if version in ('1.12.2', '1.12'):
        return True
    if version in ('1.7.10', '1.8.9'):
        return False
    return INSTANCE_OF.get(label) in INSTANCES_112


def mc_version(jar, archive):
    """mcmod.info's mcversion, or None for libraries that do not ship one."""
    if jar in VERSION_CACHE:
        return VERSION_CACHE[jar]
    version = None
    try:
        text = archive.read('mcmod.info').decode('utf-8', 'replace')
        import json
        data = json.loads(text[text.index('['):]) if '[' in text else json.loads(text)
        if isinstance(data, dict):
            data = [data]
        if data and isinstance(data[0], dict):
            version = data[0].get('mcversion')
            if isinstance(version, list):
                version = version[0] if version else None
    except Exception:
        version = None
    VERSION_CACHE[jar] = version
    return version


KINDS = (
    ('item', ('ItemBlock', 'ItemMulti', 'MetaItem')),
    ('te', ('TileEntity', 'Tile')),
    ('world', ('WorldGen', 'Structure', 'Chunk', 'Decoration', 'Feature')),
    ('client', ('Client', 'Render', 'HUD', 'Model', 'Gui')),
    ('integration', ('Waila', 'Hwyla', 'Probe', 'JEI', 'Plugin', 'Top', 'Integration')),
    ('block', ('Block',)),
)


def kind_of(name):
    for kind, needles in KINDS:
        if any(needle in name for needle in needles):
            return kind
    return 'other'


def jars():
    """Returns (instance, path) for every enabled jar, deduplicated across instances."""
    roots = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1
                               else '~/.local/share/PrismLauncher/instances')
    seen = {}
    for path in sorted(glob.glob(roots + '/*/minecraft/mods/**/*.jar', recursive=True) +
                       glob.glob(roots + '/*/.minecraft/mods/**/*.jar', recursive=True)):
        if path.endswith('.disabled') or '/.disabled/' in path:
            continue
        try:
            digest = hashlib.sha1(open(path, 'rb').read()).hexdigest()
        except OSError:
            continue
        instance = path[len(roots) + 1:].split('/')[0]
        seen.setdefault(digest, (instance, path))
    return sorted(seen.values())


def main():
    per_jar = collections.defaultdict(lambda: collections.Counter())
    instance_of = {}
    classes = collections.defaultdict(lambda: collections.defaultdict(list))
    scanned = 0
    broken = 0
    kinds = collections.defaultdict(collections.Counter)
    versions = collections.Counter()
    version_of = {}
    kinds_112 = collections.defaultdict(collections.Counter)

    for instance, jar in jars():
        try:
            archive = zipfile.ZipFile(jar)
        except Exception:
            broken += 1
            continue
        scanned += 1
        label = os.path.basename(jar)
        instance_of[label] = instance
        version = mc_version(jar, archive) or 'unknown'
        VERSION_CACHE[label] = version
        INSTANCE_OF[label] = instance
        versions[version] += 1
        version_of[label] = version
        for entry in archive.namelist():
            if not entry.endswith('.class'):
                continue
            try:
                data = archive.read(entry)
            except Exception:
                continue
            for name in parse_refs(data):
                bucket = TARGETS.get(name)
                if bucket:
                    per_jar[label][bucket] += 1
                    kinds[bucket][kind_of(entry)] += 1
                    if version_is_112(label):
                        kinds_112[bucket][kind_of(entry)] += 1
                    if len(classes[bucket][label]) < 200:
                        classes[bucket][label].append(entry[:-6].replace('/', '.'))

    print(f"扫描 {scanned} 个 jar（跨实例去重），{broken} 个打不开\n")
    totals = collections.Counter()
    mods = collections.Counter()
    for jar, counter in per_jar.items():
        totals.update(counter)
        for bucket in counter:
            mods[bucket] += 1
    print("引用点 / 涉及模组数:")
    for bucket, count in totals.most_common():
        print(f"  {bucket:14} {count:5d} 处引用, {mods[bucket]:4d} 个模组")
    print("\n1.12.2 子集（mcmod.info 声明 1.12.x）:")
    subset = collections.Counter()
    subset_mods = collections.Counter()
    for jar, counter in per_jar.items():
        if not version_is_112(jar):
            continue
        for bucket in ('meta_out', 'meta_in', 'state_id', 'state_id_by', 'state_id_map', 'block_id', 'block_by_id', 'item_meta', 'item_meta_set'):
            if counter[bucket]:
                subset[bucket] += counter[bucket]
                subset_mods[bucket] += 1
    for bucket in ('meta_out', 'meta_in', 'item_meta', 'item_meta_set', 'state_id', 'state_id_by', 'state_id_map', 'block_id', 'block_by_id'):
        print(f"  {bucket:14} {subset[bucket]:5d} 处, {subset_mods[bucket]:4d} 个模组")
    print("\n引用类的种类分布（1.12.2 子集）:")
    for bucket in ('meta_out', 'meta_in', 'state_id'):
        print(f"  {bucket:10} " + ' '.join(f"{k}={v}" for k, v in kinds_112[bucket].most_common()))
    print("\n版本分布:", dict(versions.most_common(6)))
    print("\n按实例（有引用的模组数）:")
    per_instance = collections.defaultdict(collections.Counter)
    for jar, counter in per_jar.items():
        for bucket in counter:
            per_instance[instance_of[jar]][bucket] += 1
    for instance in sorted(per_instance):
        print(f"  {instance:32} " + ' '.join(f"{k}={v}" for k, v in per_instance[instance].most_common()))
    print("\nmeta_out 最多的模组（前 20）:")
    for jar, counter in sorted(per_jar.items(), key=lambda kv: -kv[1]['meta_out'])[:20]:
        if counter['meta_out']:
            print(f"  {counter['meta_out']:4d}  {jar[:56]:56} [{instance_of[jar]}]")
    print("\n1.12.2 里 meta_out 最多的模组（前 15）:")
    for jar, counter in sorted(per_jar.items(), key=lambda kv: -kv[1]['meta_out'])[:15]:
        if counter['meta_out'] and version_is_112(jar):
            print(f"  {counter['meta_out']:4d}  {jar[:56]:56} meta_in={counter['meta_in']} state_id={counter['state_id']}")
    print("\n1.12.2 里直接读 BLOCK_STATE_IDS 的模组（全部）:")
    for jar, counter in sorted(per_jar.items(), key=lambda kv: -kv[1]['state_id_map']):
        if counter['state_id_map'] and version_is_112(jar):
            print(f"  {counter['state_id_map']:4d}  {jar[:56]:56} {classes['state_id_map'][jar][:3]}")
    print("\n1.12.2 里 getStateId 的引用类（前 12 个模组，各 2 个类）:")
    shown = 0
    for jar, counter in sorted(per_jar.items(), key=lambda kv: -kv[1]['state_id']):
        if not counter['state_id'] or not version_is_112(jar):
            continue
        print(f"  {counter['state_id']:3d}  {jar[:44]:44} {classes['state_id'][jar][:2]}")
        shown += 1
        if shown >= 12:
            break
    print("\n使用 state id / 状态 id map 的模组（这些会受 id 变化影响）:")
    for jar, counter in sorted(per_jar.items(), key=lambda kv: -(kv[1]['state_id'] + kv[1]['state_id_by'] + kv[1]['state_id_map'])):
        n = counter['state_id'] + counter['state_id_by'] + counter['state_id_map']
        if n:
            print(f"  {n:4d}  {jar[:56]:56} id={counter['state_id']} by={counter['state_id_by']} map={counter['state_id_map']}")


if __name__ == '__main__':
    main()

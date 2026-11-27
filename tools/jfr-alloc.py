#!/usr/bin/env python3
"""Sums a JFR recording's allocation samples into three tables: by allocated class, by allocating site, and, for one
class, by the code that asked for it.

    tools/jfr-alloc.py recording.jfr
    tools/jfr-alloc.py recording.jfr --class net.minecraft.item.ItemStack
    tools/jfr-alloc.py recording.jfr --gc

The numbers are sampled: JFR records one sample per so many bytes allocated (the 'profile' settings throttle it), and
the weight of a sample is the estimate of what it stands for. They compare runs of the same recording settings, they are
not a heap measurement.

Requires the 'jfr' tool from the JDK that made the recording (JDK 25 here), which is usually next to 'java'.
"""
import argparse
import collections
import os
import shutil
import subprocess
import sys
import tempfile

WEIGHT_UNITS = {'bytes': 1.0, 'kB': 1e3, 'MB': 1e6, 'GB': 1e9}


def jfr_binary():
    found = shutil.which('jfr')
    if found:
        return found
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidate = os.path.join(java_home, 'bin', 'jfr')
        if os.path.exists(candidate):
            return candidate
    sys.exit('jfr not found on PATH or in JAVA_HOME/bin; point JAVA_HOME at the JDK that made the recording')


def human(value):
    for unit, scale in (('GB', 1e9), ('MB', 1e6), ('kB', 1e3)):
        if value >= scale:
            return '%.1f %s' % (value / scale, unit)
    return '%.0f B' % value


def parse_frames(block):
    """Frames of one event, the allocating method first, as 'Class.method'."""
    frames = []
    inside = False
    for line in block:
        stripped = line.strip()
        if stripped.startswith('stackTrace = ['):
            inside = True
            continue
        if inside:
            if stripped.startswith(']'):
                break
            if '(' in stripped:
                frames.append(stripped[:stripped.index('(')])
    return frames


def collect(path, depth):
    samples = []
    tool = jfr_binary()
    with tempfile.TemporaryFile('w+', encoding='utf-8', errors='replace') as out:
        subprocess.run([tool, 'print', '--events', 'jdk.ObjectAllocationSample',
                        '--stack-depth', str(depth), path],
                       stdout=out, stderr=subprocess.DEVNULL, check=True)
        out.seek(0)
        block = []
        for line in out:
            if line.startswith('jdk.ObjectAllocationSample {'):
                if block:
                    samples.append(parse_block(block))
                block = [line]
            elif block:
                block.append(line)
        if block:
            samples.append(parse_block(block))
    return [s for s in samples if s is not None]


def parse_block(block):
    object_class = weight = None
    for line in block:
        stripped = line.strip()
        if stripped.startswith('objectClass = '):
            # 'int[] (classLoader = bootstrap)' - the loader is not part of the name
            name = stripped[len('objectClass = '):].strip()
            cut = name.find(' (classLoader = ')
            object_class = name if cut < 0 else name[:cut]
        elif stripped.startswith('weight = '):
            parts = stripped[len('weight = '):].split()
            if len(parts) == 2 and parts[1] in WEIGHT_UNITS:
                weight = float(parts[0]) * WEIGHT_UNITS[parts[1]]
    if object_class is None or weight is None:
        return None
    return object_class, weight, parse_frames(block)


def table(title, counter, total, top):
    print()
    print(title)
    print('-' * len(title))
    shown = 0
    for name, value in counter.most_common(top):
        print('  %10s %6s  %s' % (human(value), '%.1f%%' % (100 * value / total), name))
        shown += 1
    rest = sum(v for _, v in counter.most_common()[top:])
    if rest > 0:
        print('  %10s %6s  (%d more)' % (human(rest), '%.1f%%' % (100 * rest / total), len(counter) - top))


def main():
    parser = argparse.ArgumentParser(description='Allocation tables out of a JFR recording.')
    parser.add_argument('recording', help='the .jfr file')
    parser.add_argument('--depth', type=int, default=10, help='stack depth to read (default 10)')
    parser.add_argument('--top', type=int, default=15, help='rows per table (default 15)')
    parser.add_argument('--class', dest='klass',
                        help='also break this allocated class down by the code that asked for it')
    parser.add_argument('--site', dest='site',
                        help='also break one allocating site down by its callers')
    parser.add_argument('--frame', dest='frame',
                        help='also break down who calls this method anywhere in the stack')
    parser.add_argument('--gc', action='store_true', help='print the GC tables instead')
    args = parser.parse_args()

    if args.gc:
        tool = jfr_binary()
        for view in ('gc-pauses', 'heap-configuration'):
            subprocess.run([tool, 'view', view, args.recording], check=False)
            print()
        return

    samples = collect(args.recording, args.depth)
    if not samples:
        sys.exit('no allocation samples in %s (was the recording made with settings that sample allocations?)'
                 % args.recording)

    total = sum(weight for _, weight, _ in samples)
    by_class = collections.Counter()
    by_site = collections.Counter()
    for object_class, weight, frames in samples:
        by_class[object_class] += weight
        by_site[frames[0] if frames else '(no stack)'] += weight

    print('%s: %d samples, %s sampled (weights are estimates, not a heap measurement)'
          % (args.recording, len(samples), human(total)))
    table('Allocation by class', by_class, total, args.top)
    table('Allocation by site', by_site, total, args.top)

    if args.klass:
        wanted = args.klass
        callers = collections.Counter()
        own = 0.0
        for object_class, weight, frames in samples:
            if object_class != wanted:
                continue
            own += weight
            callers[frames[1] if len(frames) > 1 else '(direct)'] += weight
        if own == 0:
            sys.exit('\n%s was not allocated in this recording' % wanted)
        print()
        print('%s: %s of %s (%.1f%%)' % (wanted, human(own), human(total), 100 * own / total))
        table('%s -- who allocates it' % wanted, callers, own, args.top)

    if args.frame:
        wanted = args.frame
        callers = collections.Counter()
        own = 0.0
        for _, weight, frames in samples:
            for i, frame in enumerate(frames[1:], 1):
                if frame == wanted:
                    own += weight
                    callers[frames[i + 1] if i + 1 < len(frames) else '(top)'] += weight
                    break
        if own == 0:
            sys.exit('\n%s does not appear in any stack' % wanted)
        print()
        print('%s is called by, %s of %s (%.1f%%)' % (wanted, human(own), human(total),
                                                       100 * own / total))
        table('callers of %s' % wanted, callers, own, args.top)

    if args.site:
        wanted = args.site
        callers = collections.Counter()
        own = 0.0
        for _, weight, frames in samples:
            if not frames or frames[0] != wanted:
                continue
            own += weight
            callers[frames[1] if len(frames) > 1 else '(direct)'] += weight
        if own == 0:
            sys.exit('\n%s did not allocate anything in this recording' % wanted)
        print()
        print('%s: %s of %s (%.1f%%)' % (wanted, human(own), human(total), 100 * own / total))
        table('%s -- who calls it' % wanted, callers, own, args.top)


if __name__ == '__main__':
    main()

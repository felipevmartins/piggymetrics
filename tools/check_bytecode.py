#!/usr/bin/env python3
"""Gate de bytecode: confere o major das classes compiladas de cada modulo.

Uso: tools/check_bytecode.py MAJOR [--root DIR]
MAJOR: 52=Java 8, 55=11, 61=17, 65=21, 69=25. Sai com 1 se alguma classe tiver outro major
ou se nenhuma classe for encontrada.
"""
import argparse
import collections
import pathlib
import struct
import sys


def major_of(path):
    with open(path, 'rb') as handle:
        header = handle.read(8)
    if len(header) < 8 or header[:4] != b'\xca\xfe\xba\xbe':
        return None
    return struct.unpack('>H', header[6:8])[0]


def scan(root):
    result = collections.defaultdict(collections.Counter)
    for kind in ('classes', 'test-classes'):
        for path in sorted(pathlib.Path(root).glob('*/target/%s/**/*.class' % kind)):
            module = path.relative_to(root).parts[0]
            result[(module, kind)][major_of(path)] += 1
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('major', type=int)
    parser.add_argument('--root', default='.')
    args = parser.parse_args(argv)
    result = scan(args.root)
    if not result:
        print('nenhuma classe encontrada: compile antes')
        return 1
    failed = False
    for (module, kind), counts in sorted(result.items()):
        wrong = {m: n for m, n in counts.items() if m != args.major}
        failed = failed or bool(wrong)
        print('%s %-12s %s %s' % ('FAIL' if wrong else 'ok  ', module + '/' + kind,
                                  dict(counts), 'esperado %d' % args.major))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

"""Resolve append-style merge conflicts by keeping both sides.

Usage: python union_merge.py <file> [mode]
  mode "both"   (default): ours + theirs
  mode "close:<text>": ours + <text> + theirs, for hunks where both sides end inside a block whose closing line
                       follows the conflict (e.g. close:"    }" or close:"    });").
  mode "theirs" / "ours": keep one side.
Per-hunk modes: pass several modes separated by "|"; the last one repeats.
JSON mixin configs: use mode "mixins" to union the "mixins"/"client" arrays.
"""
import json
import re
import sys

PAT = re.compile(r'<<<<<<< [^\n]*\n(.*?)=======\n(.*?)>>>>>>> [^\n]*\n', re.S)


def resolve(text, modes):
    i = [0]

    def rep(m):
        mode = modes[min(i[0], len(modes) - 1)]
        i[0] += 1
        ours, theirs = m.group(1), m.group(2)
        if mode == 'ours':
            return ours
        if mode == 'theirs':
            return theirs
        if mode.startswith('close:'):
            return ours + mode[6:].replace('\\n', '\n') + '\n\n' + theirs
        sep = '' if ours.endswith('\n\n') or not ours else ''
        return ours + sep + theirs

    return PAT.sub(rep, text)


def mixins(text):
    def side(n):
        return PAT.sub(lambda m: m.group(n), text)
    a, b = json.loads(side(1)), json.loads(side(2))
    for k in ('mixins', 'client', 'server'):
        if k in a or k in b:
            a[k] = sorted(set(a.get(k, [])) | set(b.get(k, [])))
    return json.dumps(a, indent=2) + '\n'


if __name__ == '__main__':
    path = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else 'both'
    with open(path, encoding='utf-8') as f:
        s = f.read()
    s = mixins(s) if mode == 'mixins' else resolve(s, mode.split('|'))
    with open(path, 'w', encoding='utf-8') as f:
        f.write(s)
    print(path, 'resolved;', 'conflicts left' if '<<<<<<<' in s else 'clean')

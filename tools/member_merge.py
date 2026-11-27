"""Merge a Java class that several branches append members to (core/Hooks.java, core/event/FeatherEvents.java).

Usage: python member_merge.py <repo-relative path> <other-branch>
Takes the HEAD (ours) version, splits both versions' outer-class body into top-level members (each with its leading
javadoc/annotations/comments), and appends the other branch's members whose declaration key is not in ours, just
before the "// --- helpers" section if present (else before the class's closing brace). Imports are unioned.
Writes the result over the working-tree file (resolving its conflicts).
"""
import re
import subprocess
import sys

DECL = re.compile(r'^\s*(?:public|protected|private|static|final|abstract|default|sealed|non-sealed|\s)*'
                  r'(?:@interface|interface|class|record|enum)\s+(\w+)'
                  r'|^\s*(?:public|protected|private|static|final|\s)*[\w<>\[\], ?.]+\s+(\w+)\s*(?:=|\(|;)')


def show(rev, path):
    return subprocess.run(['git', 'show', f'{rev}:{path}'], capture_output=True, text=True, encoding='utf-8', check=True).stdout


def split(src):
    """(header incl. class line, [member texts], footer) for the first top-level class."""
    lines = src.splitlines(keepends=True)
    depth = 0
    start = None
    for i, l in enumerate(lines):
        if start is None and re.match(r'^(public\s+)?(final\s+)?(abstract\s+)?(class|interface)\s', l):
            start = i
            break
    # find the line where the class body opens
    i = start
    while '{' not in lines[i]:
        i += 1
    header = ''.join(lines[:i + 1])
    body = lines[i + 1:]
    # last closing brace of the class
    end = max(k for k, l in enumerate(body) if l.strip() == '}')
    footer = ''.join(body[end:])
    body = body[:end]
    members, cur, depth, pending = [], [], 0, False
    for l in body:
        cur.append(l)
        code = re.sub(r'"(\\.|[^"\\])*"', '""', l)
        code = re.sub(r"'(\\.|[^'\\])'", "''", code)
        code = code.split('//')[0] if not code.strip().startswith('*') else ''
        opens, closes = code.count('{'), code.count('}')
        depth += opens - closes
        stripped = l.strip()
        if depth == 0 and (stripped.endswith('}') or stripped.endswith('});') or stripped.endswith(';')) \
                and not stripped.startswith('*') and not stripped.startswith('/') and not stripped.startswith('@'):
            members.append(''.join(cur))
            cur = []
    tail = ''.join(cur)
    return header, members, tail + footer


def key(member):
    for l in member.splitlines():
        s = l.strip()
        if not s or s.startswith(('/', '*', '@')):
            continue
        m = DECL.match(l)
        if m:
            name = m.group(1) or m.group(2)
            kind = 'type' if m.group(1) else 'field'
            if kind == 'field' and '(' in s.split('=')[0]:
                # method: include parameter types for overloads
                sig = re.sub(r'\s+', ' ', s.split('{')[0])
                return 'method:' + sig
            return f'{kind}:{name}'
        return 'raw:' + s
    return 'blank:' + member.strip()[:80]


def imports(src):
    return [l for l in src.splitlines() if l.startswith('import ')]


def main():
    path, other = sys.argv[1], sys.argv[2]
    ours, theirs = show('HEAD', path), show(other, path)
    h1, m1, f1 = split(ours)
    _, m2, _ = split(theirs)
    have = {key(m) for m in m1}
    add = [m for m in m2 if key(m) not in have and m.strip()]
    idx = next((i for i, m in enumerate(m1) if '---- helpers' in m or '-- helpers' in m), len(m1))
    merged = m1[:idx] + add + m1[idx:]
    imp1 = imports(ours)
    extra = [i for i in imports(theirs) if i not in imp1]
    if extra:
        last = h1.rfind(imp1[-1]) + len(imp1[-1]) if imp1 else h1.find('\n') + 1
        h1 = h1[:last] + ''.join('\n' + i for i in extra) + h1[last:]
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(h1 + ''.join(merged) + f1)
    print(f'{path}: kept {len(m1)} members, added {len(add)} from {other}: ' + ', '.join(key(m).split(':', 1)[1][:40] for m in add))


if __name__ == '__main__':
    main()

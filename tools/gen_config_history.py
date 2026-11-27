"""Writes src/main/resources/feather/history.json: for every bundled config file, the SHA-256 of every earlier version
of it found in git history (Paper-era Feather resources included), line endings normalized.

Feather.saveDefault uses it on startup: a config file on disk that matches an earlier bundled version was never edited,
so it is replaced by the new bundled one (new creatures, harvests, guide pages...). Edited files are left alone.
Run before a release (release.py does): python tools/gen_config_history.py
"""
import hashlib
import json
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)
REPO = os.path.dirname(MOD)
BUNDLED = os.path.join(MOD, 'src', 'main', 'resources', 'feather')
OUT = os.path.join(BUNDLED, 'history.json')
# only Feather's own resource folders, so an unrelated file with the same name can never count as a past version
PATH = re.compile(r'^(res/|mod/src/main/resources/feather/)(?P<name>[\w-]+\.yml)$')


def norm(data: bytes) -> bytes:
    return data.replace(b'\r\n', b'\n').rstrip()


def sha(data: bytes) -> str:
    return hashlib.sha256(norm(data)).hexdigest()


def main():
    names = sorted(f for f in os.listdir(BUNDLED) if f.endswith('.yml') and f != 'plugin.yml')
    objs = subprocess.run(['git', '-C', REPO, 'rev-list', '--all', '--objects'], capture_output=True, check=True)
    blobs = {}
    for line in objs.stdout.decode('utf-8', 'replace').splitlines():
        h, _, path = line.partition(' ')
        m = PATH.search(path)
        if m and m.group('name') in names:
            blobs.setdefault(m.group('name'), set()).add(h)
    out = {}
    for name in names:
        current = sha(open(os.path.join(BUNDLED, name), 'rb').read())
        hashes = set()
        for h in blobs.get(name, ()):
            data = subprocess.run(['git', '-C', REPO, 'cat-file', '-p', h], capture_output=True, check=True).stdout
            hashes.add(sha(data))
        hashes.discard(current)
        out[name] = sorted(hashes)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write('\n')
    print(f'history.json: {sum(len(v) for v in out.values())} earlier versions of {len(out)} files')


if __name__ == '__main__':
    main()

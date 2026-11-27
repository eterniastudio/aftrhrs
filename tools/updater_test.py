"""Local test rig for the auto-updater (no GitHub): a signed mirror, fake jars and a production server driver.

    python tools/updater_test.py fakejar <in.jar> <out.jar> <version>       # same jar, fabric.mod.json version changed
    python tools/updater_test.py dummymod <out.jar> <mod id> <version>      # an empty Fabric mod ("unrelated" mod)
    python tools/updater_test.py mirror --out DIR --base http://127.0.0.1:8765/ --jar build/libs/aftrhrs-1.0.1.jar \
        [--dep fake-geckolib.jar ...] [--tamper-jar] [--tamper-manifest]
    python -m http.server 8765 --bind 127.0.0.1 --directory DIR            # serve it
    python tools/updater_test.py server --dir SERVER --java JAVA [--prop aftrhrs.update.releases=...] \
        --step "send:feather update stage" --step "expect:is downloaded and checked:180" ...

The mirror has GitHub's layout under --base: latest/download/manifest.json(.sig), download/v<version>/<jar>, and
deps/<jar> for every --dep jar (dependencies without a --dep come from Modrinth as in a real release). It is signed with
the real release key (release.py's KEY), so the mod's signature check runs unchanged; point the mod at it with
-Daftrhrs.update.releases=<base>. --tamper-jar serves a jar that differs from the signed hash; --tamper-manifest
changes the manifest after signing.

`server` starts a production Fabric server (java -jar fabric-server-launch.jar nogui) in --dir, waits for "Done (",
runs the steps (send:<console command>, expect:<regex>[:<seconds>], pause:<seconds>), then sends `stop` and waits for
the JVM (and the update helper, if one started) to exit. Every server line is echoed to stdout.
"""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import release  # noqa: E402


def rewrite_jar(src, dst, edit):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst + '.tmp', 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == 'fabric.mod.json':
                meta = json.loads(data.decode('utf-8'))
                edit(meta)
                data = json.dumps(meta, indent=2).encode('utf-8')
            zout.writestr(item, data)
    os.replace(dst + '.tmp', dst)


def fakejar(a):
    rewrite_jar(a.src, a.dst, lambda m: m.__setitem__('version', a.version))
    print(f'{a.dst}: {release.mod_json(a.dst)["id"]} {a.version}')


def dummymod(a):
    meta = {'schemaVersion': 1, 'id': a.id, 'version': a.version, 'name': 'Unrelated test mod', 'environment': '*'}
    with zipfile.ZipFile(a.dst, 'w') as z:
        z.writestr('fabric.mod.json', json.dumps(meta))
    print(a.dst)


def mirror(a):
    key = release.load_key()
    p = release.props()
    jar = os.path.abspath(a.jar)
    version = release.mod_json(jar)['version']
    base = a.base if a.base.endswith('/') else a.base + '/'
    if os.path.isdir(a.out):
        shutil.rmtree(a.out)
    # Dependencies: --dep jars from the mirror, the rest as a real release lists them (Modrinth).
    manifest = release.build_manifest(p, version, 'local test', jar, base, os.path.join(release.HERE, 'release', 'cache'))
    for dep in a.dep or []:
        meta = release.mod_json(dep)
        data = open(dep, 'rb').read()
        name = os.path.basename(dep)
        entry = {'id': meta['id'], 'version': meta['version'], 'min': '', 'name': name, 'url': f'{base}deps/{name}',
                 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data), 'source': 'test'}
        old = next((d for d in manifest['dependencies'] if d['id'] == meta['id']), None)
        if old:
            entry['min'] = old['min']
            manifest['dependencies'][manifest['dependencies'].index(old)] = entry
        else:
            manifest['dependencies'].append(entry)
        os.makedirs(os.path.join(a.out, 'deps'), exist_ok=True)
        shutil.copy2(dep, os.path.join(a.out, 'deps', name))
    mp, sp = release.sign(key, manifest, os.path.join(a.out, 'latest', 'download'))
    os.makedirs(os.path.join(a.out, 'download', f'v{version}'), exist_ok=True)
    served = os.path.join(a.out, 'download', f'v{version}', os.path.basename(jar))
    if a.tamper_jar:
        # Same size, one byte different inside: only the hash can tell.
        data = bytearray(open(jar, 'rb').read())
        data[len(data) // 2] ^= 0x5A
        open(served, 'wb').write(data)
    else:
        shutil.copy2(jar, served)
    if a.tamper_manifest:
        raw = open(mp, 'rb').read().replace(b'"local test"', b'"local tesT"')
        open(mp, 'wb').write(raw)
    print(json.dumps({'version': version, 'mod': manifest['mod']['url'],
                      'dependencies': {d['id']: d['version'] for d in manifest['dependencies']},
                      'tamper_jar': a.tamper_jar, 'tamper_manifest': a.tamper_manifest}))


def server(a):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    cmd = [a.java, '-Xmx2G'] + [f'-D{p}' for p in a.prop or []] + ['-jar', 'fabric-server-launch.jar', 'nogui']
    proc = subprocess.Popen(cmd, cwd=a.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding='utf-8', errors='replace', bufsize=1)
    lines, cond = [], threading.Condition()

    def pump():
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            with cond:
                lines.append(line)
                cond.notify_all()
        with cond:
            cond.notify_all()

    threading.Thread(target=pump, daemon=True).start()

    def expect(pattern, timeout, start=0):
        rx = re.compile(pattern)
        end = time.time() + timeout
        with cond:
            i = start
            while True:
                while i < len(lines):
                    if rx.search(lines[i]):
                        return i + 1
                    i += 1
                left = end - time.time()
                if left <= 0 or proc.poll() is not None and i >= len(lines):
                    return -1
                cond.wait(min(left, 1))

    mark = expect(r'Done \(', 300)
    if mark < 0:
        print('!! server did not start', flush=True)
        proc.kill()
        sys.exit(1)
    ok = True
    for step in a.step or []:
        kind, _, rest = step.partition(':')
        if kind == 'send':
            with cond:
                mark = len(lines)
            proc.stdin.write(rest + '\n')
            proc.stdin.flush()
        elif kind == 'expect':
            pat, _, secs = rest.rpartition(':') if re.search(r':\d+$', rest) else (rest, '', '60')
            found = expect(pat, int(secs), mark)
            print(f'>> expect {pat!r}: {"ok" if found > 0 else "NOT FOUND"}', flush=True)
            ok &= found > 0
            if found > 0:
                mark = found
        elif kind == 'pause':
            time.sleep(float(rest))
    proc.stdin.write('stop\n')
    proc.stdin.flush()
    try:
        proc.wait(timeout=120)
    except subprocess.TimeoutExpired:
        proc.kill()
    print(f'>> server exited ({proc.returncode})', flush=True)
    # An update helper waits for this JVM, then swaps the jars: give it a moment.
    log = os.path.join(a.dir, 'config', 'aftrhrs', 'updates', 'update.log')
    time.sleep(a.settle)
    if os.path.exists(log):
        print('>> update.log:\n' + open(log, encoding='utf-8').read(), flush=True)
    print('>> mods: ' + ', '.join(sorted(os.listdir(os.path.join(a.dir, 'mods')))), flush=True)
    sys.exit(0 if ok else 2)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('fakejar')
    s.add_argument('src'), s.add_argument('dst'), s.add_argument('version')
    s = sub.add_parser('dummymod')
    s.add_argument('dst'), s.add_argument('id'), s.add_argument('version')
    s = sub.add_parser('mirror')
    s.add_argument('--out', required=True), s.add_argument('--base', required=True), s.add_argument('--jar', required=True)
    s.add_argument('--dep', action='append'), s.add_argument('--tamper-jar', action='store_true')
    s.add_argument('--tamper-manifest', action='store_true')
    s = sub.add_parser('server')
    s.add_argument('--dir', required=True), s.add_argument('--java', default='java'), s.add_argument('--prop', action='append')
    s.add_argument('--step', action='append'), s.add_argument('--settle', type=float, default=8)
    a = ap.parse_args()
    {'fakejar': fakejar, 'dummymod': dummymod, 'mirror': mirror, 'server': server}[a.cmd](a)


if __name__ == '__main__':
    main()

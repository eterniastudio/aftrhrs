"""Publishes an Aftr Hrs mod release that installed copies (clients and opted-in servers) update to by themselves.

    python release.py --notes "What changed"          # build, sign, publish on GitHub
    python release.py --notes "..." --no-build         # use build/libs/aftrhrs-<version>.jar as it is
    python release.py --notes "..." --dry-run          # build and sign into release/v<version>/, publish nothing

Bump `version` in gradle.properties first: installs only ever move to a higher version, and this script refuses a
version that isn't newer than the latest release. The release lands on the public releases-only repo REPO with three
assets: the mod jar, manifest.json and manifest.json.sig.

manifest.json lists the mod jar and the dependency jars it needs (GeckoLib, Fabric API and Stellarity, at the versions
in gradle.properties), each with its fabric.mod.json id and version, file name, download URL (the mod: this release;
dependencies: Modrinth's CDN), SHA-256 and size, plus the Minecraft / Fabric Loader versions the jar needs (from its
fabric.mod.json). manifest.json.sig is an Ed25519 signature over its exact bytes, made with KEY (or
$AFTRHRS_RELEASE_KEY). The public half is built into the mod (update/Updater.java PUBLIC_KEY); without the private key no
update can be published that existing installs accept, so keep a backup of it somewhere safe.

Needs: Python's `cryptography`, network access to api.modrinth.com, and (to publish) the `gh` CLI logged in with
write access to REPO. JAVA_HOME should point at JDK 25 (default: ~/.jdks/temurin-25.0.3 when it exists).
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request
import zipfile

REPO = 'eterniastudio/aftrhrs'
HERE = os.path.dirname(os.path.abspath(__file__))
KEY = os.environ.get('AFTRHRS_RELEASE_KEY', os.path.expanduser('~/.config/aftrhrs/aftrhrs-release-ed25519.pem'))
UPDATER = os.path.join(HERE, 'src/main/java/net/axther/aftrhrs/update/Updater.java')
MODRINTH = 'https://api.modrinth.com/v2'
UA = 'aftrhrs-release-script (github.com/eterniastudio/aftrhrs)'
# Dependency jars every install needs: (fabric mod id, Modrinth project, how gradle.properties names the version).
DEPENDENCIES = [
    ('geckolib', 'geckolib', lambda p: ('version_id', p['geckolib_modrinth_id'])),
    ('fabric-api', 'fabric-api', lambda p: ('version_number', p['fabric_version'])),
    # Stellarity's textures, sounds and names for the server's Stellarity data pack (its license forbids bundling it).
    ('stellarity', 'stellarity', lambda p: ('version_number', p['stellarity_version'])),
]


def props():
    out = {}
    for line in open(os.path.join(HERE, 'gradle.properties'), encoding='utf-8'):
        if '=' in line and not line.lstrip().startswith('#'):
            k, v = line.split('=', 1)
            out[k.strip()] = v.strip()
    return out


def vkey(v):
    base, _, suffix = v.split('+')[0].partition('-')
    nums = [int(x) if x.isdigit() else -1 for x in base.split('.')]
    return nums + [0] * (6 - len(nums)), (suffix == '', suffix)


def fetch_json(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def download(url, path, sha512=None):
    if not (os.path.exists(path) and (sha512 is None or hashlib.sha512(open(path, 'rb').read()).hexdigest() == sha512)):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + '.part', 'wb') as f:
            shutil.copyfileobj(r, f)
        os.replace(path + '.part', path)
    data = open(path, 'rb').read()
    if sha512 and hashlib.sha512(data).hexdigest() != sha512:
        sys.exit(f'{url}: SHA-512 does not match what Modrinth lists')
    return data


def mod_json(jar_path):
    with zipfile.ZipFile(jar_path) as z:
        return json.loads(z.read('fabric.mod.json').decode('utf-8'))


def minimum(predicate):
    """'>=5.5.7' -> '5.5.7'; '*' or anything else -> '' (no minimum)."""
    m = re.fullmatch(r'\s*>=\s*([0-9][0-9A-Za-z.+-]*)\s*', predicate or '')
    return m.group(1) if m else ''


def modrinth_file(project, how, value, mc):
    if how == 'version_id':
        v = fetch_json(f'{MODRINTH}/version/{value}')
    else:
        q = urllib.parse.urlencode({'game_versions': json.dumps([mc]), 'loaders': json.dumps(['fabric'])})
        v = next((x for x in fetch_json(f'{MODRINTH}/project/{project}/version?{q}') if x['version_number'] == value), None)
        if v is None:
            sys.exit(f'Modrinth has no {project} {value} for Minecraft {mc}')
    if mc not in v['game_versions'] or 'fabric' not in v['loaders']:
        sys.exit(f'Modrinth {project} {v["version_number"]} is not a Fabric build for Minecraft {mc}')
    f = next((f for f in v['files'] if f['primary']), v['files'][0])
    return f['url'], f['filename'], f['hashes']['sha512']


def dependencies(p, depends, cache):
    out = []
    for mod_id, project, ref in DEPENDENCIES:
        how, value = ref(p)
        url, name, sha512 = modrinth_file(project, how, value, p['minecraft_version'])
        if not url.startswith('https://cdn.modrinth.com/data/'):
            sys.exit(f'unexpected download host for {project}: {url}')
        path = os.path.join(cache, name)
        data = download(url, path, sha512)
        meta = mod_json(path)
        if meta.get('id') != mod_id:
            sys.exit(f'{name} declares mod id {meta.get("id")!r}, expected {mod_id!r}')
        out.append({'id': mod_id, 'version': meta['version'], 'min': minimum(depends.get(mod_id, '')), 'name': name,
                    'url': url, 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data), 'source': 'modrinth'})
        print(f'  {mod_id} {meta["version"]}: {url}')
    return out


def load_key():
    from cryptography.hazmat.primitives import serialization
    if not os.path.exists(KEY):
        sys.exit(f'no release key at {KEY} (set AFTRHRS_RELEASE_KEY to its path)')
    key = serialization.load_pem_private_key(open(KEY, 'rb').read(), None)
    pub = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)).decode()
    embedded = re.search(r'PUBLIC_KEY = "([^"]+)"', open(UPDATER, encoding='utf-8').read()).group(1)
    if pub != embedded:
        sys.exit(f'{KEY} does not match the public key built into the mod ({embedded}); installs would reject this release')
    return key


def build_manifest(p, version, notes, jar_path, base, cache):
    """The manifest dict for the jar at jar_path; the mod's URL is base + download/v<version>/<name>."""
    meta = mod_json(jar_path)
    if meta.get('id') != 'aftrhrs' or meta.get('version') != version:
        sys.exit(f'{jar_path} is aftrhrs {meta.get("version")}, not {version} (stale build? rerun without --no-build)')
    depends = meta.get('depends', {})
    data = open(jar_path, 'rb').read()
    name = f'aftrhrs-{version}.jar'
    return {
        'format': 2,
        'repo': REPO,
        'version': version,
        'published': datetime.date.today().isoformat(),
        'notes': notes,
        'minecraft': depends.get('minecraft', ''),
        'min_minecraft': p['minecraft_version'],
        'min_loader': minimum(depends.get('fabricloader', '')) or p['fabric_loader_version'],
        'mod': {'id': 'aftrhrs', 'version': version, 'name': name, 'url': f'{base}download/v{version}/{name}',
                'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)},
        'dependencies': dependencies(p, depends, cache),
    }


def sign(key, manifest, out):
    """Writes manifest.json + manifest.json.sig into out; returns their paths."""
    raw = json.dumps(manifest, indent=1, sort_keys=True).encode()
    sig = base64.b64encode(key.sign(raw)).decode() + '\n'
    key.public_key().verify(base64.b64decode(sig), raw)
    os.makedirs(out, exist_ok=True)
    mp, sp = os.path.join(out, 'manifest.json'), os.path.join(out, 'manifest.json.sig')
    open(mp, 'wb').write(raw)
    open(sp, 'w', newline='\n').write(sig)
    return mp, sp


def gh(*args, check=True, capture=True):
    return subprocess.run(['gh', *args], check=check, text=True, capture_output=capture)


def latest_release():
    try:
        r = gh('release', 'view', '--repo', REPO, '--json', 'tagName', '--jq', '.tagName', check=False)
    except FileNotFoundError:
        return None, 'gh not found'
    if r.returncode != 0:
        return None, (r.stderr or '').strip()
    return r.stdout.strip().lstrip('v') or None, ''


def build():
    env = dict(os.environ)
    jdk = os.path.expanduser('~/.jdks/temurin-25.0.3')
    if 'JAVA_HOME' not in env and os.path.isdir(jdk):
        env['JAVA_HOME'] = jdk
    # earlier bundled config versions, so unedited config files on servers/worlds update to this release's ones
    subprocess.run([sys.executable, os.path.join(HERE, 'tools', 'gen_config_history.py')], check=True)
    gradlew = os.path.join(HERE, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
    subprocess.run([gradlew, 'build', '-q'], cwd=HERE, check=True, env=env)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--notes', required=True, help='what changed (release page and manifest)')
    ap.add_argument('--no-build', action='store_true', help='use the jar already in build/libs')
    ap.add_argument('--dry-run', action='store_true', help='build and sign into release/v<version>/, publish nothing')
    a = ap.parse_args()

    key = load_key()
    p = props()
    version = p['version']
    tag = f'v{version}'
    if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]{0,40}', version):
        sys.exit(f'bad version {version!r}')

    latest, why = latest_release()
    if latest and vkey(version) <= vkey(latest):
        sys.exit(f'{version} is not newer than the latest release {latest}; bump version in gradle.properties')
    print(f'latest release on {REPO}: {latest or "none"}' + (f' ({why})' if why and not latest else ''))

    if not a.no_build:
        build()
    jar = os.path.join(HERE, 'build', 'libs', f'aftrhrs-{version}.jar')
    if not os.path.exists(jar):
        sys.exit(f'missing {jar} (build first)')

    out = os.path.join(HERE, 'release', tag)
    print(f'manifest for Aftr Hrs {version} (Minecraft {p["minecraft_version"]}):')
    manifest = build_manifest(p, version, a.notes, jar, f'https://github.com/{REPO}/releases/', os.path.join(HERE, 'release', 'cache'))
    mp, sp = sign(key, manifest, out)
    shutil.copy2(jar, os.path.join(out, os.path.basename(jar)))
    print(f'signed {mp}')
    if a.dry_run:
        print('dry run: nothing published')
        return

    deps = manifest['dependencies']
    body = (f'{a.notes}\n\n'
            f'**Minecraft {p["minecraft_version"]}, Fabric Loader {manifest["min_loader"]}+.** One jar for the client and the '
            'server. Put it in your `mods` folder with these:\n\n'
            '| Mod | Version | Download |\n|---|---|---|\n'
            + ''.join(f'| {d["id"]} | {d["version"]} | [{d["name"]}]({d["url"]}) |\n' for d in deps)
            + '\nOnce installed, the mod keeps itself (and these dependencies) up to date. Remove the old *Aftr Hrs '
              'Companion* jar (`aftrhrs-fabric-*.jar`) if you still have it.\n\n'
              '| File | SHA-256 |\n|---|---|\n'
            + f'| `{manifest["mod"]["name"]}` | `{manifest["mod"]["sha256"]}` |\n'
            + ''.join(f'| `{d["name"]}` | `{d["sha256"]}` |\n' for d in deps))
    gh('release', 'create', tag, '--repo', REPO, '--title', f'Aftr Hrs {version}', '--notes', body, '--latest',
       os.path.join(out, os.path.basename(jar)), mp, sp, capture=False)
    print(f'published https://github.com/{REPO}/releases/tag/{tag}')


if __name__ == '__main__':
    main()

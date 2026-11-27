"""Collects the Christmas module's message keys (tr("key", "fallback") calls) into assets/aftrhrs/lang/en_us.christmas.json."""
import glob
import json
import os
import re

MOD = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out = {}
for p in glob.glob(os.path.join(MOD, 'src', 'main', 'java', 'net', 'axther', 'aftrhrs', 'feature', 'christmas', '*.java')):
    s = open(p, encoding='utf-8').read()
    for m in re.finditer(r'tr\("([a-z_.]+)",\s*"((?:[^"\\]|\\.)*)"', s):
        out['aftrhrs.christmas.' + m.group(1)] = m.group(2)
    for m in re.finditer(r'translatableWithFallback\("(aftrhrs\.christmas\.[a-z_.]+)",\s*"((?:[^"\\]|\\.)*)"', s):
        out[m.group(1)] = m.group(2)
out = dict(sorted(out.items()))
path = os.path.join(MOD, 'src', 'main', 'resources', 'assets', 'aftrhrs', 'lang', 'en_us.christmas.json')
with open(path, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(out, f, indent=1, ensure_ascii=False)
    f.write('\n')
print(len(out), 'keys ->', path)

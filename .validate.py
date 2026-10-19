import re, sys, yaml
path = '/Users/akshatpratap/.dsh/profiles/web/cordis.patch.yml'
src = open(path).read()
# Replace the !!js tag with a plain quoted scalar so PyYAML can parse it.
safe = re.sub(r'!!js\s+([^\n]*)', lambda m: chr(39) + m.group(1).strip().strip(chr(39)) + chr(39), src)
d = yaml.safe_load(safe)
print('YAML structurally valid. entries:', len(d))
ids = []
for e in d:
    if not isinstance(e, dict):
        continue
    if 'id' in e:
        ids.append(e['id'])
    for r in (e.get('insert') or []):
        ids.append(r.get('id'))
print('mcp rows   :', [i for i in ids if i and str(i).startswith('mcp')])
print('web rows   :', [i for i in ids if i and 'web' in str(i)])
print('serper rows:', [i for i in ids if i and 'serper' in str(i)])
for e in d:
    if not isinstance(e, dict):
        continue
    if e.get('id') == 'web':
        print('web pin    :', e.get('config'))
    for r in (e.get('insert') or []):
        if r.get('id') == 'web-search-serper':
            print('serper cfg :', r.get('config'))
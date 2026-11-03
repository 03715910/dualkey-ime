from pathlib import Path
import urllib.request, json, unicodedata, xml.etree.ElementTree as ET
from collections import defaultdict
rev='2ef784e3a4168bc2a43cd1b5b9839b6636f5899c'
base=f'https://raw.githubusercontent.com/unicode-org/cldr/{rev}/'
root=Path('app/src/main/assets/expressions')
root.mkdir(exist_ok=True)
def get(name): return urllib.request.urlopen(base+name,timeout=30).read()
def norm(s):
    return ''.join(chr(ord(c)-96) if 'ァ'<=c<='ヶ' else c for c in unicodedata.normalize('NFKC',s).lower().strip())
def read(lang):
    out=defaultdict(list)
    for section in ('annotations','annotationsDerived'):
        source=get(f'common/{section}/{lang}.xml')
        for node in ET.fromstring(source).iter('annotation'):
            cp=node.get('cp','')
            if not cp or not node.text or node.text in ('↑↑↑','∅∅∅'): continue
            for label in node.text.split('|'):
                key=norm(label)
                if key and len(key)<=36 and cp not in out[key]:
                    out[key].append(cp)
    return out
ja=read('ja'); en=read('en')
root.joinpath('emoji-ja.json').write_text(json.dumps(dict(sorted(ja.items())),ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8',newline='\n')
root.joinpath('emoji-en.json').write_text(json.dumps(dict(sorted(en.items())),ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8',newline='\n')
root.joinpath('LICENSE-CLDR.txt').write_bytes(get('LICENSE'))
print(len(ja),len(en))

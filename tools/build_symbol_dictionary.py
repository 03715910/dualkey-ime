"""Pinned general symbol readings. No per-example replacement dictionary."""
from pathlib import Path
import hashlib
import json
import unicodedata
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
REVISION='e5f1494923a0a624d01f19c50d57bbdfa03ea5ba'
EXPECTED='bc68b032a195f203dccba6f95667424199ba9b185fee2be64eeaa959253c097a'

def main():
    folder=ROOT/'app/src/main/assets/symbols'
    folder.mkdir(parents=True,exist_ok=True)
    source=folder/'symbol.tsv'
    if not source.exists():
        source.write_bytes(urllib.request.urlopen(f'https://raw.githubusercontent.com/google/mozc/{REVISION}/src/data/symbol/symbol.tsv',timeout=60).read())
    digest=hashlib.sha256(source.read_bytes()).hexdigest()
    if digest!=EXPECTED: raise SystemExit('Symbol source hash differs from the pinned input.')
    index={}
    for line in source.read_text(encoding='utf-8').splitlines()[1:]:
        cols=line.split('\t')
        if len(cols)<3: continue
        value=cols[1]
        for key in cols[2].split():
            key=unicodedata.normalize('NFKC',key)
            key=''.join(chr(ord(c)-0x60) if 'ァ'<=c<='ヶ' else c for c in key)
            if value and value not in index.setdefault(key,[]): index[key].append(value)
    for values in index.values():
        if '〜' in values and '～' not in values: values.insert(values.index('〜')+1,'～')
    (folder/'readings.json').write_text(json.dumps(index,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    (folder/'manifest.json').write_text(json.dumps(dict(source='google/mozc',revision=REVISION,sha256=digest,readings=len(index),pairs=sum(map(len,index.values()))),indent=2)+'\n',encoding='utf-8')
    if not (folder/'LICENSE').exists():
        (folder/'LICENSE').write_bytes(urllib.request.urlopen(f'https://raw.githubusercontent.com/google/mozc/{REVISION}/LICENSE',timeout=60).read())
    print(f'Symbol readings: {len(index)}; pairs: {sum(map(len,index.values()))}')

if __name__=='__main__': main()

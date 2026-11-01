"""Compile the pinned JMdict English/loanword subset; derived data is CC BY-SA 4.0.

The original compressed XML is retained outside the APK for reproducible builds.
No text entered by an app user is used or transmitted by this tool.
"""
from array import array
from collections import defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'data/semantic/JMdict_e.xml.gz'
SOURCE_URL = 'https://www.edrdg.org/pub/Nihongo/JMdict_e.gz'
SOURCE_SHA = '7cbacefaf3264a72476327c7bff837348b239ea605ad7818d5cf79c7f5ea9dcb'
OUTPUT = ROOT / 'app/src/main/assets/semantic'


def normalize(text):
    return ''.join(chr(ord(c)-0x60) if 'ァ' <= c <= 'ヶ' else c for c in text)


def gloss(text):
    while '(' in text:
        cleaned = re.sub(r'\([^()]*\)', '', text)
        if cleaned == text:
            return None
        text = cleaned
    text = ' '.join(text.split()).strip()
    if text.startswith('to '):
        text = text[3:]
    # Word/short-phrase equivalents only, not long dictionary explanations.
    return text if len(text) <= 36 and len(text.split()) <= 4 and re.fullmatch(r"[A-Za-z][A-Za-z '\-]*", text) else None


def build():
    assert hashlib.sha256(SOURCE.read_bytes()).hexdigest() == SOURCE_SHA, 'Pinned source mismatch'
    records = []
    loans = defaultdict(dict)
    total = 0
    with gzip.open(SOURCE) as stream:
        for _, entry in ET.iterparse(stream, events=('end',)):
            if entry.tag != 'entry':
                continue
            total += 1
            kanji = [e.findtext('keb') for e in entry.findall('k_ele')]
            readings = [(e.findtext('reb'), [r.text for r in e.findall('re_restr')], e.find('re_nokanji') is not None) for e in entry.findall('r_ele')]
            common = any(entry.findall(path) for path in ('k_ele/ke_pri', 'r_ele/re_pri'))
            for sense_index, sense in enumerate(entry.findall('sense')[:2]):
                if any(x.text in {'archaic', 'obsolete term', 'obscure term'} for x in sense.findall('misc')):
                    continue
                english = [v for e in sense.findall('gloss') if e.get('{http://www.w3.org/XML/1998/namespace}lang', 'eng') == 'eng'
                           and e.get('g_type') != 'expl' and (v := gloss(e.text or ''))][:3]
                if not english:
                    continue
                allowed_kanji = [e.text for e in sense.findall('stagk')]
                allowed_readings = [e.text for e in sense.findall('stagr')]
                keys = set()
                for reading, restrictions, no_kanji in readings:
                    if not reading or len(reading) > 80 or (allowed_readings and reading not in allowed_readings):
                        continue
                    applicable = [k for k in kanji if (not restrictions or k in restrictions) and (not allowed_kanji or k in allowed_kanji)]
                    if allowed_kanji and (no_kanji or not applicable):
                        continue
                    keys.add(normalize(reading))
                    if not no_kanji:
                        keys.update(k for k in applicable if len(k) <= 80)
                score = (0 if common else 1000) + sense_index * 100
                records.append((keys, english, score))
                if sense_index == 0:
                    for reading, _, _ in readings:
                        if reading and len(reading) <= 18 and all('ァ' <= c <= 'ヶ' or c in 'ーヴ・' for c in reading):
                            # Shared first-sense glosses provide actual loanwords,
                            # e.g. chair -> チェア, rather than invented transliterations.
                            if (len(reading) < 3 and not common) or '(' in (sense.findtext('gloss') or ''):
                                continue
                            for word in english[:1]:
                                old = loans[word.casefold()].get(reading, 32767)
                                loans[word.casefold()][reading] = min(old, score)
            entry.clear()
    groups = defaultdict(dict)
    for keys, english, score in records:
        for key in keys:
            for index, word in enumerate(english):
                groups[key][word] = min(groups[key].get(word, 32767), score + index)
            for word in (english[:1] if score % 1000 == 0 else []):
                for kana, _ in sorted(loans[word.casefold()].items(), key=lambda x: (x[1], len(x[0]), x[0]))[:2]:
                    groups[key][kana] = min(groups[key].get(kana, 32767), score + 5000)

    # Reuse the tested, read-only DKL1 mmap index with a 1x1 empty POS matrix.
    keys = sorted(groups, key=lambda value: value.encode('utf-8'))
    strings = bytearray()
    interned = {}
    def intern(value):
        if value not in interned:
            raw = value.encode('utf-8')
            interned[value] = len(strings), len(raw)
            strings.extend(raw)
        return interned[value]
    key_bytes, entry_bytes, ranks = bytearray(), bytearray(), []
    count = 0
    for key in keys:
        words = sorted(groups[key].items(), key=lambda pair: (pair[1], pair[0]))[:24]
        offset, length = intern(key)
        key_bytes.extend(struct.pack('<IHHI', offset, length, len(words), count))
        for word, cost in words:
            offset, length = intern(word)
            entry_bytes.extend(struct.pack('<IHHHh', offset, length, 0, 0, cost))
            count += 1
        ranks.append(words[0][1])
    if len(strings) % 2:
        strings.append(0)
    base = 1 << (len(keys)-1).bit_length()
    tree = array('i', [2**31-1]) * (2 * base)
    tree[base:base+len(keys)] = array('i', ranks)
    for index in range(base-1, 0, -1):
        tree[index] = min(tree[index*2], tree[index*2+1])
    entry_offset = 64 + len(key_bytes)
    string_offset = entry_offset + len(entry_bytes)
    matrix_offset = string_offset + len(strings)
    tree_offset = matrix_offset + 2
    size = tree_offset + len(tree)*4
    header = struct.pack('<16I', 0x314C4B44, 1, len(keys), count, 1, max(map(len, keys)),
                         64, entry_offset, string_offset, len(strings), matrix_offset, tree_offset, base, size, 0, 0)
    data = header + key_bytes + entry_bytes + strings + b'\0\0' + struct.pack('<' + str(len(tree)) + 'i', *tree)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with (OUTPUT / 'lexicon.dklz').open('wb') as target:
        with gzip.GzipFile(fileobj=target, mode='wb', filename='', mtime=0, compresslevel=9) as compressed:
            compressed.write(data)
    manifest = dict(format=1, source='JMdict (English components)', sourceUrl=SOURCE_URL, sourceSha256=SOURCE_SHA,
                    sourceEntries=total, keys=len(keys), entries=count, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                    license='CC-BY-SA-4.0', compressedBytes=(OUTPUT / 'lexicon.dklz').stat().st_size)
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(manifest, indent=2))
    for key in ('いす', '椅子', 'ねこ', '猫', 'つくえ'):
        print(key, sorted(groups[key].items(), key=lambda x: (x[1], x[0])))


if __name__ == '__main__':
    build()

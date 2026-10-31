"""Build the bundled, offline lexicon. No input text or runtime server is involved.

Source revision is pinned. The resulting DKL1 file is read by JapaneseLexicon.kt.
Only vocabulary/connection data are reused; this is not Mozc's native engine.
"""
import argparse
from array import array
from concurrent.futures import ThreadPoolExecutor
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = 'e5f1494923a0a624d01f19c50d57bbdfa03ea5ba'
FILES = [f'src/data/dictionary_oss/dictionary{i:02}.txt' for i in range(10)] + [
    'src/data/dictionary_oss/' + p for p in (
        'connection_single_column.txt', 'id.def', 'dictionary_filter.tsv', 'README.txt')
] + ['LICENSE']
CACHE = ROOT / '.local/mozc-source'
OUTPUT = ROOT / 'app/src/main/assets/japanese'


def fetch(path):
    local = CACHE / Path(path).name
    if not local.exists():
        request = urllib.request.Request(
            f'https://raw.githubusercontent.com/google/mozc/{REVISION}/{path}',
            headers={'User-Agent': 'DualKey-dictionary-builder'})
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read()
        local.write_bytes(data)
    return path, hashlib.sha256(local.read_bytes()).hexdigest()


def build():
    CACHE.mkdir(parents=True, exist_ok=True)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=5) as pool:
        source_hashes = dict(pool.map(fetch, FILES))
    filters = [tuple(map(re.compile, line.split('\t'))) for line in
               (CACHE / 'dictionary_filter.tsv').read_text(encoding='utf-8').splitlines()
               if line and not line.startswith('#')]
    # Repeated POS variants are retained: their connection costs affect sentences.
    groups = {}
    source_count = 0
    for filename in sorted(CACHE.glob('dictionary[0-9][0-9].txt')):
        for line in filename.read_text(encoding='utf-8').splitlines():
            columns = line.split('\t')
            if len(columns) < 5:
                continue
            key, left, right, cost, value = columns[:5]
            source_count += 1
            if not key or not value or any(a.fullmatch(key) and b.fullmatch(value) for a, b in filters):
                continue
            groups.setdefault(key, set()).add((value, int(left), int(right), int(cost)))
    keys = sorted(groups, key=lambda k: k.encode('utf-8'))
    with (CACHE / 'connection_single_column.txt').open() as stream:
        dimension = int(next(stream))
        matrix = array('H', (int(line) for line in stream))
    assert len(matrix) == dimension * dimension
    # Upstream single-column order is previous right ID first, next left ID second.
    def transition(right, left):
        return matrix[right * dimension + left]

    pos = {}
    for line in (CACHE / 'id.def').read_text(encoding='utf-8').splitlines():
        number, description = line.split(' ', 1)
        pos[int(number)] = description
    name_ids = {number for number, description in pos.items() if '固有名詞' in description}
    strings = bytearray()
    offsets = {}
    def intern(text):
        if text not in offsets:
            encoded = text.encode('utf-8')
            offsets[text] = len(strings), len(encoded)
            strings.extend(encoded)
        return offsets[text]

    group_bytes = bytearray()
    entry_bytes = bytearray()
    ranks = []
    unique_pairs = 0
    entry_count = 0
    for key in keys:
        entries = sorted(groups[key], key=lambda e: (e[3] + transition(0, e[1]) + transition(e[2], 0), e))
        key_offset, key_length = intern(key)
        assert len(entries) < 65536
        group_bytes.extend(struct.pack('<IHHI', key_offset, key_length, len(entries), entry_count))
        unique_pairs += len({e[0] for e in entries})
        rank = 2**31 - 1
        for value, left, right, cost in entries:
            assert 0 <= left < dimension and 0 <= right < dimension and -32768 <= cost < 32768
            value_offset, value_length = intern(value)
            entry_bytes.extend(struct.pack('<IHHHh', value_offset, value_length, left, right, cost))
            entry_count += 1
            rank = min(rank, cost + transition(0, left) + transition(right, 0) +
                       (2200 if left in name_ids else 0) + len(key) * 35)
        ranks.append(rank)
    # Range-minimum tree: bounded prefix prediction without scanning 100,000 words.
    base = 1 << (len(keys) - 1).bit_length()
    tree = array('i', [2**31 - 1]) * (2 * base)
    tree[base:base + len(keys)] = array('i', ranks)
    for i in range(base - 1, 0, -1):
        tree[i] = min(tree[i * 2], tree[i * 2 + 1])
    if sys.byteorder != 'little':
        matrix.byteswap()
        tree.byteswap()
    keys_offset = 64
    entries_offset = keys_offset + len(group_bytes)
    strings_offset = entries_offset + len(entry_bytes)
    if len(strings) % 2:
        strings.append(0)
    matrix_offset = strings_offset + len(strings)
    tree_offset = matrix_offset + len(matrix) * 2
    size = tree_offset + len(tree) * 4
    header = struct.pack('<16I', 0x314C4B44, 1, len(keys), entry_count, dimension,
                         max(map(len, keys)), keys_offset, entries_offset, strings_offset,
                         len(strings), matrix_offset, tree_offset, base, size, 0, 0)
    raw_path = CACHE / 'lexicon.dkl'
    with raw_path.open('wb') as stream:
        for part in (header, group_bytes, entry_bytes, strings, matrix.tobytes(), tree.tobytes()):
            stream.write(part)
    # AAPT expands .gz assets and removes that suffix. A dedicated suffix preserves
    # our gzip payload and its name so runtime expansion is consistent.
    with raw_path.open('rb') as source, (OUTPUT / 'lexicon.dklz').open('wb') as destination:
        with gzip.GzipFile(fileobj=destination, mode='wb', filename='', mtime=0, compresslevel=9) as compressed:
            while chunk := source.read(1024 * 1024):
                compressed.write(chunk)
    manifest = dict(format=1, source='google/mozc', revision=REVISION, sourceRows=source_count,
                    readings=len(keys), entries=entry_count, readingSurfacePairs=unique_pairs,
                    matrixDimension=dimension, bytes=size,
                    sha256=hashlib.file_digest(raw_path.open('rb'), 'sha256').hexdigest(),
                    compressedBytes=(OUTPUT / 'lexicon.dklz').stat().st_size,
                    sources=source_hashes)
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    (OUTPUT / 'LICENSE-MOZC.txt').write_bytes((CACHE / 'LICENSE').read_bytes().rstrip(b'\r\n') + b'\n')
    (OUTPUT / 'LICENSE-DICTIONARY.txt').write_bytes((CACHE / 'README.txt').read_bytes().rstrip(b'\r\n') + b'\n')
    print(json.dumps({k: v for k, v in manifest.items() if k != 'sources'}, indent=2))


if __name__ == '__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    build()

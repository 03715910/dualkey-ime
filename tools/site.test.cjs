const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync(require('node:path').join(__dirname, '../site/app.js'), 'utf8');
class Element {
  constructor() { this.children=[]; this.textContent=''; this.attrs={}; this.classes=new Set(['disabled']); this.classList={remove: value=>this.classes.delete(value)}; }
  removeAttribute(key) { delete this.attrs[key]; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children=children; }
}
const release = { packageName: 'io.github.user03715910.dualkey', versionCode: 2, versionName: '0.1.1',
  releaseDate: '2026-10-31', apkUrl: 'https://github.com/testowner/dualkey-ime/releases/download/v0.1.1/DualKeyIME-0.1.1.apk',
  websiteUrl: 'https://testowner.github.io/dualkey-ime/', sha256: 'a'.repeat(64), sizeBytes: 1024, releaseNotes: ['<script>alert(1)</script>'] };
async function run(value, offline=false) {
  const nodes={};
  const document={getElementById(id) { return nodes[id] ||= new Element(); }, createElement() { return new Element(); }};
  await vm.runInNewContext(script,{document,URL,location:{origin:'https://testowner.github.io'},fetch:async path=>{
    if (offline) throw new Error('offline');
    return {ok:path==='version.json',json:async()=>value};
  }});
  return nodes;
}
test('verified official metadata enables the exact APK URL; release notes remain text',async()=>{
  const nodes=await run(release);
  assert.equal(nodes.download.href,release.apkUrl);
  assert.equal(nodes.download.classes.has('disabled'),false);
  assert.equal(nodes['history-list'].children[0].children[2].children[0].textContent,release.releaseNotes[0]);
});
test('offline metadata leaves the download unavailable',async()=>{
  const nodes=await run(release,true); assert.equal(nodes.download.href,undefined); assert.equal(nodes.download.classes.has('disabled'),true);
});
for (const [label,patch] of Object.entries({
  'HTTP downgrade':{apkUrl:release.apkUrl.replace('https:','http:')},
  'host lookalike':{apkUrl:release.apkUrl.replace('github.com','github.com.evil.invalid')},
  'foreign package':{packageName:'io.evil.keyboard'},
  'foreign site':{websiteUrl:'https://evil.invalid/dualkey-ime/'},
  'oversized APK':{sizeBytes:151*1024*1024},
  'invalid hash':{sha256:'bad'},
  'missing versionCode':{versionCode:null},
})) test(`rejects ${label}`,async()=>{ const nodes=await run({...release,...patch}); assert.equal(nodes.download.href,undefined); assert.equal(nodes.download.classes.has('disabled'),true); });

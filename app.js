'use strict';
(async () => {
  const status = document.getElementById('release-status');
  const button = document.getElementById('download');
  try {
    const response = await fetch('version.json', { cache: 'no-store' });
    if (!response.ok) throw new Error('unpublished');
    const v = await response.json();
    const apk = new URL(v.apkUrl);
    const site = new URL(v.websiteUrl);
    const repo = site.pathname.split('/').filter(Boolean)[0];
    const owner = site.hostname.split('.')[0];
    const expected = `https://github.com/${owner}/${repo}/releases/download/v${v.versionName}/DualKeyIME-${v.versionName}.apk`;
    if (v.packageName !== 'io.github.user03715910.dualkey' || apk.href !== expected || site.origin !== location.origin || !/^[a-f0-9]{64}$/i.test(v.sha256) ||
        !Number.isSafeInteger(v.versionCode) || v.versionCode < 1 || !Number.isSafeInteger(v.sizeBytes) || v.sizeBytes < 1 || v.sizeBytes > 150*1024*1024 ||
        !Array.isArray(v.releaseNotes) || !/^\d+\.\d+\.\d+$/.test(v.versionName)) throw new Error('invalid');
    button.href = apk.href; button.classList.remove('disabled'); button.removeAttribute('aria-disabled');
    status.textContent = `v${v.versionName} · ${(v.sizeBytes / 1048576).toFixed(1)} MB · ${v.releaseDate} 更新`;
    document.getElementById('hash').textContent = `APK SHA-256: ${v.sha256}`;
    const source = document.getElementById('source'); source.href = `https://github.com/${owner}/${repo}`; source.hidden = false;
    let releases = [v];
    try { const history = await fetch('history.json', { cache: 'no-store' }); if (history.ok) { const h = await history.json(); if (Array.isArray(h)) releases = h; } } catch (_) {}
    const list = document.getElementById('history-list'); list.replaceChildren();
    for (const release of releases.slice(0, 30)) {
      const article = document.createElement('article'); const heading = document.createElement('strong');
      heading.textContent = `v${release.versionName}`;
      const date = document.createElement('time'); date.textContent = release.releaseDate;
      const notes = document.createElement('ul');
      for (const note of (release.releaseNotes || []).slice(0, 100)) { const li = document.createElement('li'); li.textContent = note; notes.append(li); }
      article.append(heading, date, notes); list.append(article);
    }
  } catch (_) {
    status.textContent = '配布APKはまだ公開されていないか、更新情報を取得できません。時間をおいて再度お試しください。';
    button.textContent = 'APKは公開準備中';
  }
})();

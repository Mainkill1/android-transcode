/* Size-goal UI. The runner is authoritative: never turn an estimate into success. */
(() => {
  'use strict';
  const $ = s => document.querySelector(s);
  const escape = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const presets = [10, 20, 25, 50, 100, 500];
  let selectedBytes = 10_000_000;
  let app;
  const mb = bytes => `${Number((bytes / 1_000_000).toFixed(3))} MB`;

  function selectGoal(bytes) {
    selectedBytes = bytes;
    document.querySelectorAll('[data-limit-mb]').forEach(button => {
      const value = button.dataset.limitMb;
      const active = value === 'custom' ? !presets.includes(bytes / 1_000_000) : Number(value) * 1_000_000 === bytes;
      button.setAttribute('aria-pressed', String(active));
    });
    $('#home-goal-summary').textContent = `Each output stays below ${mb(bytes)}. Settings are chosen after inspection.`;
  }

  function bind(instance) {
    app = instance;
    document.querySelectorAll('[data-limit-mb]').forEach(button => button.addEventListener('click', () => {
      const custom = button.dataset.limitMb === 'custom';
      $('#custom-limit-row').hidden = !custom;
      if (custom) { $('#custom-limit').focus(); return; }
      selectGoal(Number(button.dataset.limitMb) * 1_000_000);
    }));
    $('#apply-limit').onclick = () => {
      const value = Number($('#custom-limit').value);
      const bytes = Math.round(value * 1_000_000);
      if (!Number.isFinite(value) || bytes < 32_000 || bytes > 2_000_000_000) {
        $('#home-message').textContent = 'Enter a limit from 0.032 to 2,000 MB.'; return;
      }
      selectGoal(bytes); $('#home-message').textContent = ''; $('#custom-limit-row').hidden = true;
    };
    $('#custom-limit').onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); $('#apply-limit').click(); } };
    selectGoal(selectedBytes);
  }

  function options(target) {
    const values = presets.map(n => n * 1_000_000);
    if (target && !values.includes(target)) values.push(target);
    return values.map(bytes => `<option value="${bytes}" ${bytes === target ? 'selected' : ''}>Under ${mb(bytes)}${bytes === 10_000_000 ? ' · Small upload' : ''}</option>`).join('') +
      '<option value="custom">Custom limit…</option>';
  }

  function render() {
    const {settings: s, source: src, advanced} = app.state;
    const image = src.kind === 'image';
    const target = s.targetBytes;
    $('#conversion-panel').innerHTML = `<div class="conversion-content fit-content">
      ${image ? `<img id="image-preview" src="${escape(src.previewUrl)}" alt="Selected image preview">` : ''}
      <section id="fit-card" class="fit-card"><p class="eyebrow">${target ? 'READY FOR SHARING' : 'IMAGE CONVERSION'}</p>
      <h2>${target ? `Make it fit under ${mb(target)}.` : 'Save your image.'}</h2>
      <p>${image ? 'We adjust image quality and dimensions, then check the actual file size.' : 'We choose the format, picture size, and quality for your upload limit.'}</p>
      ${target ? `<label class="field"><span>Maximum file size</span><select id="workspace-limit" aria-label="Maximum file size">${options(target)}</select></label>
      <div id="workspace-custom-row" class="custom-limit-row" hidden><label class="field"><span>Custom limit (MB)</span><input id="workspace-custom-limit" type="number" min=".032" max="2000" step=".001" value="${target / 1_000_000}" inputmode="decimal"></label><button id="workspace-apply-limit" class="small-button">Apply</button></div>` : ''}
      <p id="fit-plan-summary" class="fit-plan-summary" role="status"></p>
      <p id="fit-warnings" class="fit-warnings"></p>
      ${target && src.size < target ? '<p class="already-fits">Your original is already below this limit. Conversion is optional.</p>' : ''}
      <p class="fit-assurance">${target ? 'If the result is too large, Forma tries stronger compression. Only an output below your limit is marked ready.' : 'No size limit. Image metadata is removed on export.'}</p></section>
      ${image ? `<label class="field"><span>Image format</span><select data-setting="imageFormat" aria-label="Image format">${[['auto','Automatic'],['webp','WebP · Keep transparency'],['jpg','JPEG · Photos without transparency'],['png','PNG · Lossless, may need smaller dimensions']].map(([v,l])=>`<option value="${v}" ${v===(s.imageFormat||'auto')?'selected':''}>${l}</option>`).join('')}</select></label>` : ''}
      <button id="advanced-toggle" class="advanced-toggle" aria-expanded="${advanced}" aria-controls="advanced-content">More settings<span>${advanced ? 'Hide details' : 'Optional'}</span></button>
      <div id="advanced-content" ${advanced ? '' : 'hidden'}><p class="helper">Upload mode manages compression settings. Your trims and sound edits remain active. Limits use decimal MB (1 MB = 1,000,000 bytes).</p>
      <p id="fit-technical-details" class="helper"></p><label class="field"><span>Output file name</span><input data-setting="filename" type="text" value="${escape(s.filename)}" placeholder="Automatic from source name" aria-label="Output file name"></label>
      ${target ? '<button id="manual-conversion" class="small-button">Use manual settings — no size limit</button>' : ''}</div>
      ${!image ? '<button id="edit-shortcut" class="edit-shortcut"><div><b>Trim first for better quality</b><span>Keep the part you need. The size budget updates with your edits.</span></div></button>' : ''}
      ${!app.state.engine.available ? '<p class="engine-note">Preview only. Start the local FFmpeg runner for inspection and verified conversion.</p>' : ''}
      </div>`;
    $('#advanced-toggle').onclick = () => { app.state.advanced = !app.state.advanced; render(); };
    if ($('#manual-conversion')) $('#manual-conversion').onclick = () => app.change({targetBytes: null});
    if ($('#edit-shortcut')) $('#edit-shortcut').onclick = () => app.setView('edit');
    if (target) {
      $('#workspace-limit').onchange = e => {
        if (e.target.value === 'custom') { $('#workspace-custom-row').hidden = false; $('#workspace-custom-limit').focus(); return; }
        const bytes = Number(e.target.value); selectGoal(bytes); app.change({targetBytes: bytes});
      };
      $('#workspace-custom-limit').onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); $('#workspace-apply-limit').click(); } };
      $('#workspace-apply-limit').onclick = () => {
        const bytes = Math.round(Number($('#workspace-custom-limit').value) * 1_000_000);
        if (!Number.isFinite(bytes) || bytes < 32_000 || bytes > 2_000_000_000) {
          $('#fit-warnings').textContent = 'Enter a limit from 0.032 to 2,000 MB.'; return;
        }
        selectGoal(bytes); app.change({targetBytes: bytes});
      };
    }
    updateSummary();
  }

  function updateSummary() {
    if (!$('#fit-plan-summary')) return;
    const {plan: p, planPending, planError, source} = app.state;
    $('#fit-plan-summary').textContent = p ?
      (p.kind === 'image' ? `${p.extension.toUpperCase()} image · ${p.width} × ${p.height}` :
       `${p.extension.toUpperCase()}${p.videoEncoder ? ` video · ${p.width} × ${p.height}` : ' audio'}${p.audioEncoder && p.videoEncoder ? ' · Sound included' : ''}`) :
      planError || (planPending ? 'Inspecting the best starting settings…' : source?.backend ? 'Preparing your upload settings…' : 'Connect FFmpeg to inspect this source and choose settings.');
    $('#fit-warnings').textContent = p?.warnings?.join(' ') || '';
    if ($('#fit-technical-details')) $('#fit-technical-details').textContent = !p ? 'Settings will appear after inspection.' : p.kind === 'image' ? `Image quality ${p.settings.imageQuality}; dimensions may decrease on retry.` : `${p.videoEncoder ? `${p.settings.videoBitrate} kb/s video · ${p.settings.fps} fps · ` : ''}${p.audioEncoder ? `${p.settings.audioBitrate} kb/s audio` : 'No audio'} · Up to ${p.maxAttempts || 1} attempts.`;
  }

  window.FormaUpload = {bind, render, updateSummary, mb, get selectedBytes() { return selectedBytes; }};
})();

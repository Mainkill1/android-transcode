/* Source-time trim selection. Draft gestures commit once to the existing export model. */
(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const app = window.FormaStudio;
  const root = $('#trim-timeline'), viewport = $('#timeline-viewport');
  const rail = $('#timeline-rail'), track = $('#trim-track'), media = $('#media');
  const handles = {start: $('#trim-start'), end: $('#trim-end')};
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const clone = value => JSON.parse(JSON.stringify(value));
  const stamp = value => app.time(value, true);
  let zoom = 1, source = null, gesture = null, panFrame = 0;
  let assetsAbort = null, decoder = null, audioContext = null;

  function ranges() { return gesture ? gesture.clips : app.state.settings?.segments || []; }
  function selected() { return ranges()[app.state.selected]; }

  // A source region can be used by multiple output clips. Only its complement is cut.
  function discarded(clips, duration) {
    const merged = [];
    for (const clip of [...clips].sort((a, b) => a.start - b.start)) {
      const last = merged.at(-1);
      if (last && clip.start <= last.end) last.end = Math.max(last.end, clip.end);
      else merged.push({...clip});
    }
    const gaps = []; let end = 0;
    for (const clip of merged) {
      if (clip.start > end) gaps.push({start: end, end: clip.start});
      end = Math.max(end, clip.end);
    }
    if (end < duration) gaps.push({start: end, end: duration});
    return gaps;
  }

  function playhead() {
    if (!source?.duration) return;
    const t = clamp(media.currentTime, 0, source.duration);
    $('#timeline-playhead').style.left = `${t / source.duration * 100}%`;
    $('#scrub').setAttribute('aria-valuetext', `${stamp(t)} of source`);
    const kept = ranges().some(r => t >= r.start && t < r.end);
    $('#preview-location').textContent = kept ? 'Kept footage' : 'Removed from output';
    $('#preview-location').classList.toggle('is-cut', !kept);
  }

  function paint() {
    const r = selected(); if (!r || !source?.duration) return;
    const duration = source.duration, pct = t => t / duration * 100;
    const start = pct(r.start), end = pct(r.end);
    $('#trim-selection').style.left = `${start}%`;
    $('#trim-selection').style.width = `${end - start}%`;
    for (const edge of ['start', 'end']) {
      const button = handles[edge];
      button.style.left = `${pct(r[edge])}%`;
      button.setAttribute('aria-valuenow', String(r[edge]));
      button.setAttribute('aria-valuemin', String(edge === 'start' ? 0 : Math.min(duration, r.start + .04)));
      button.setAttribute('aria-valuemax', String(edge === 'end' ? duration : Math.max(0, r.end - .04)));
      button.setAttribute('aria-valuetext', `${stamp(r[edge])}, ${edge === 'start' ? 'first kept moment' : 'end of kept range'}`);
      $(`#trim-${edge}-time`).textContent = stamp(r[edge]);
    }
    const cuts = discarded(ranges(), duration);
    $('#trim-cuts').innerHTML = cuts.map(c => `<span class="trim-cut" data-start="${c.start}" data-end="${c.end}" style="left:${pct(c.start)}%;width:${pct(c.end - c.start)}%"></span>`).join('');
    $('#trim-other-kept').innerHTML = ranges().filter((_, i) => i !== app.state.selected).map(c => `<span style="left:${pct(c.start)}%;width:${pct(c.end - c.start)}%"></span>`).join('');
    const removed = cuts.reduce((n, c) => n + c.end - c.start, 0);
    const output = ranges().reduce((n, c) => n + c.end - c.start, 0) / app.state.settings.speed;
    $('#trim-caption').textContent = `Trim clip ${String(app.state.selected + 1).padStart(2, '0')}`;
    $('#trim-kept-time').textContent = `${stamp((r.end - r.start) / app.state.settings.speed)} kept`;
    $('#trim-summary').textContent = `Removed ${stamp(removed)} from source · Output ${stamp(output)}`;
    $('#trim-other-legend').hidden = ranges().length < 2;
    $('#trim-hint').textContent = ranges().length > 1
      ? 'Brackets edit the selected clip. Hatched areas are not used by any kept clip.'
      : 'Drag [ and ] to keep the part between them. Hatched areas are cut out.';
    playhead();
  }

  function geometry() {
    const base = Math.max(1, viewport.clientWidth - 96);
    rail.style.width = `${base * zoom + 96}px`;
    $('#timeline-zoom-in').disabled = zoom >= 16;
    $('#timeline-zoom-out').disabled = zoom <= 1;
    $('#timeline-fit').textContent = zoom === 1 ? 'Fit' : `${zoom}× · Fit`;
    $('#timeline-zoom-note').hidden = zoom === 1;
    const count = Math.max(2, Math.min(96, Math.floor(base * zoom / 78)));
    $('#timeline-ruler').innerHTML = Array.from({length: count + 1}, (_, i) =>
      `<span style="left:${i / count * 100}%">${stamp(source.duration * i / count)}</span>`).join('');
  }

  function setZoom(value) {
    if (gesture || !source?.duration) return;
    const center = (viewport.scrollLeft + viewport.clientWidth / 2 - 48) / Math.max(1, track.clientWidth);
    zoom = clamp(value, 1, 16); geometry();
    viewport.scrollLeft = zoom === 1 ? 0 : center * track.clientWidth + 48 - viewport.clientWidth / 2;
  }
  $('#timeline-zoom-in').onclick = () => setZoom(zoom * 2);
  $('#timeline-zoom-out').onclick = () => setZoom(zoom / 2);
  $('#timeline-fit').onclick = () => setZoom(1);
  $('#timeline-prev').onclick = () => viewport.scrollBy({left: -viewport.clientWidth * .7});
  $('#timeline-next').onclick = () => viewport.scrollBy({left: viewport.clientWidth * .7});

  function seek(value) {
    media.pause(); media.currentTime = clamp(value, 0, source.duration); app.updatePlayhead();
  }
  function moveGesture(clientX) {
    if (!gesture) return;
    gesture.x = clientX;
    if (!gesture.moved && Math.abs(clientX - gesture.originX) < 2) return;
    gesture.moved = true;
    const rect = track.getBoundingClientRect();
    const delta = (clientX - gesture.originX + viewport.scrollLeft - gesture.scroll) / rect.width * source.duration;
    const r = gesture.clips[gesture.index];
    r[gesture.edge] = app.clampTrim(gesture.edge, gesture.value + delta, r, source.duration);
    seek(r[gesture.edge]); paint();
    $('#trim-live').textContent = `${gesture.edge === 'start' ? 'Start' : 'End'} ${stamp(r[gesture.edge])}`;
  }
  function autoPan() {
    if (!gesture) return;
    const bounds = viewport.getBoundingClientRect();
    // Only pan when zoomed and actually dragging near the viewport edge.
    const dx = gesture.x < bounds.left + 35 ? -8 : gesture.x > bounds.right - 35 ? 8 : 0;
    if (zoom > 1 && gesture.moved && dx) { viewport.scrollLeft += dx; moveGesture(gesture.x); }
    panFrame = requestAnimationFrame(autoPan);
  }
  function finish(commit) {
    if (!gesture) return;
    const g = gesture; gesture = null; cancelAnimationFrame(panFrame);
    root.classList.remove('is-trimming'); $('#trim-live').hidden = true;
    if (g.handle.hasPointerCapture(g.id)) g.handle.releasePointerCapture(g.id);
    if (source !== app.state.source || g.index !== app.state.selected) return;
    if (commit) app.change({segments: g.clips});
    else seek(g.beforeTime);
    paint();
    $('#trim-announcement').textContent = commit ? $('#trim-summary').textContent : 'Trim cancelled';
  }

  for (const [edge, handle] of Object.entries(handles)) {
    handle.addEventListener('pointerdown', e => {
      if (gesture || !selected() || !e.isPrimary || e.button !== 0) return;
      e.preventDefault(); handle.focus({preventScroll: true}); media.pause();
      gesture = {edge, handle, id: e.pointerId, clips: clone(ranges()), index: app.state.selected,
        value: selected()[edge], originX: e.clientX, x: e.clientX, moved: false,
        beforeSettings: JSON.stringify(app.state.settings),
        scroll: viewport.scrollLeft, beforeTime: media.currentTime};
      handle.setPointerCapture(e.pointerId); root.classList.add('is-trimming');
      $('#trim-live').hidden = false;
      $('#trim-live').textContent = `${edge === 'start' ? 'Start' : 'End'} ${stamp(selected()[edge])}`;
      seek(selected()[edge]); panFrame = requestAnimationFrame(autoPan);
    });
    handle.addEventListener('pointermove', e => { if (gesture?.id === e.pointerId) moveGesture(e.clientX); });
    handle.addEventListener('pointerup', e => { if (gesture?.id === e.pointerId) { moveGesture(e.clientX); finish(true); } });
    handle.addEventListener('pointercancel', () => finish(false));
    handle.addEventListener('lostpointercapture', () => finish(false));
    handle.addEventListener('keydown', e => {
      const r = selected(); if (!r || gesture) return;
      const step = e.shiftKey ? 1 : .1;
      const values = {ArrowLeft: r[edge] - step, ArrowDown: r[edge] - step,
        ArrowRight: r[edge] + step, ArrowUp: r[edge] + step,
        Home: edge === 'start' ? 0 : r.start + .04,
        End: edge === 'end' ? source.duration : r.end - .04};
      if (!(e.key in values)) return;
      e.preventDefault(); app.setTrim(edge, values[e.key]); handle.focus({preventScroll: true});
    });
  }
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && gesture) { e.preventDefault(); finish(false); }
    if (e.ctrlKey || e.metaKey || e.altKey || /INPUT|SELECT|TEXTAREA|BUTTON|A/.test(e.target.tagName) || e.target.isContentEditable) return;
    if (app.state.page !== 'workspace' || app.state.view !== 'edit' || !selected()) return;
    if (['i', 'o'].includes(e.key.toLowerCase())) {
      e.preventDefault(); app.setTrim(e.key.toLowerCase() === 'i' ? 'start' : 'end', media.currentTime);
    }
  });
  window.addEventListener('blur', () => finish(false));
  document.addEventListener('visibilitychange', () => { if (document.hidden) finish(false); });
  // A tap on the footage seeks; a swipe remains available for native horizontal scrolling.
  let tap = null;
  track.addEventListener('pointerdown', e => {
    if (!e.target.closest('button,input')) tap = {x: e.clientX, y: e.clientY, scroll: viewport.scrollLeft};
  });
  track.addEventListener('pointerup', e => {
    if (tap && !gesture && Math.hypot(tap.x - e.clientX, tap.y - e.clientY) < 8 && Math.abs(tap.scroll - viewport.scrollLeft) < 3) {
      const rect = track.getBoundingClientRect(); seek((e.clientX - rect.left) / rect.width * source.duration);
    }
    tap = null;
  });
  track.addEventListener('pointercancel', () => { tap = null; });

  function waitEvent(target, name, signal, action) {
    return new Promise((resolve, reject) => {
      let timer;
      const done = error => {
        clearTimeout(timer); target.removeEventListener(name, ok); target.removeEventListener('error', fail);
        signal.removeEventListener('abort', fail); error ? reject(error) : resolve();
      };
      const ok = () => done();
      const fail = () => done(new Error('Preview unavailable'));
      if (signal.aborted) { reject(new Error('Cancelled')); return; }
      target.addEventListener(name, ok, {once: true}); target.addEventListener('error', fail, {once: true});
      signal.addEventListener('abort', fail, {once: true}); timer = setTimeout(fail, 5000);
      try { action(); } catch (error) { done(error); }
    });
  }

  async function filmstrip(src, url, signal) {
    const v = document.createElement('video'); decoder = v;
    v.preload = 'auto'; v.muted = true; v.playsInline = true;
    try {
      await waitEvent(v, 'loadeddata', signal, () => { v.src = url; v.load(); });
      const canvas = document.createElement('canvas'); canvas.width = 144; canvas.height = 81;
      const ctx = canvas.getContext('2d');
      for (let i = 0; i < 12; i++) {
        if (signal.aborted) return;
        // Separate decoder: generating thumbnails must never move the user's playhead.
        const t = Math.min(src.duration - .001, src.duration * (i + .5) / 12);
        await waitEvent(v, 'seeked', signal, () => { v.currentTime = Math.max(.001, t); });
        if (signal.aborted) return;
        ctx.fillStyle = '#142820'; ctx.fillRect(0, 0, 144, 81);
        const scale = Math.min(144 / v.videoWidth, 81 / v.videoHeight);
        ctx.drawImage(v, (144 - v.videoWidth * scale) / 2, (81 - v.videoHeight * scale) / 2, v.videoWidth * scale, v.videoHeight * scale);
        const image = new Image(); image.alt = `Source at ${stamp(t)}`; image.draggable = false;
        image.src = canvas.toDataURL('image/jpeg', .65); $('#trim-filmstrip').append(image);
        $('#timeline-assets-status').textContent = i === 11 ? 'Source thumbnails' : `Loading thumbnails ${i + 1}/12`;
      }
    } catch (_) {
      if (!signal.aborted) $('#timeline-assets-status').textContent = 'Thumbnails unavailable — time-based trimming still works';
    } finally { v.removeAttribute('src'); v.load(); if (decoder === v) decoder = null; }
  }

  async function waveform(src, url, signal) {
    // Browser-only fallback is bounded. Large-file waveforms belong in the native/FFmpeg worker.
    if (src.size > 24 * 1024 * 1024 || src.duration > 600) {
      $('#timeline-assets-status').textContent = 'Waveform omitted for large audio — trim by time or listen'; return;
    }
    let context;
    try {
      const Audio = window.AudioContext || window.webkitAudioContext;
      if (!Audio) throw new Error('No decoder');
      const response = await fetch(url, {signal}); if (!response.ok) throw new Error('No media');
      const bytes = await response.arrayBuffer(); if (signal.aborted) return;
      context = new Audio({sampleRate: 8000}); audioContext = context;
      const buffer = await context.decodeAudioData(bytes); if (signal.aborted) return;
      const channels = Array.from({length: Math.min(2, buffer.numberOfChannels)}, (_, c) => buffer.getChannelData(c));
      const bins = 240, stride = Math.max(1, Math.ceil(buffer.length / bins)); let d = '';
      for (let i = 0; i < bins; i++) {
        let peak = 0;
        for (const samples of channels) for (let j = i * stride; j < Math.min(samples.length, (i + 1) * stride); j++) peak = Math.max(peak, Math.abs(samples[j]));
        const h = peak * 35; d += `M${i * 3 + 1},${40 - h}v${2 * h} `;
      }
      $('#trim-waveform').innerHTML = `<path d="${d}"/>`;
      $('#trim-waveform').setAttribute('data-measured', 'true');
      $('#timeline-assets-status').textContent = 'Measured source waveform';
    } catch (_) {
      if (!signal.aborted) $('#timeline-assets-status').textContent = 'Waveform unavailable — time-based trimming still works';
    } finally { if (context && context.state !== 'closed') await context.close().catch(() => {}); if (audioContext === context) audioContext = null; }
  }

  function refresh() {
    const next = app.state.source;
    if (source !== next) {
      finish(false); assetsAbort?.abort();
      if (decoder) { decoder.removeAttribute('src'); decoder.load(); decoder = null; }
      if (audioContext && audioContext.state !== 'closed') audioContext.close().catch(() => {});
      audioContext = null; source = next; zoom = 1; viewport.scrollLeft = 0;
      $('#trim-filmstrip').replaceChildren(); $('#trim-waveform').replaceChildren();
      $('#trim-waveform').removeAttribute('data-measured'); assetsAbort = null;
    }
    const valid = Number.isFinite(next?.duration) && next.duration > 0 && selected();
    root.hidden = !valid; if (!valid) return;
    if (gesture && (gesture.index !== app.state.selected || gesture.beforeSettings !== JSON.stringify(app.state.settings))) finish(false);
    if (!assetsAbort && app.state.view === 'edit') {
      assetsAbort = new AbortController();
      $('#timeline-assets-status').textContent = next.hasVideo ? 'Loading source thumbnails…' : 'Reading source waveform…';
      $('#trim-filmstrip').hidden = !next.hasVideo; $('#trim-waveform').hidden = next.hasVideo;
      (next.hasVideo ? filmstrip : waveform)(next, media.src, assetsAbort.signal);
    }
    geometry(); paint();
  }
  new ResizeObserver(() => { if (source?.duration && !root.hidden) { if (gesture) finish(false); geometry(); paint(); } }).observe(viewport);
  for (const event of ['timeupdate', 'seeked', 'loadeddata']) media.addEventListener(event, playhead);
  window.FormaTimeline = {refresh, playhead, discarded};
  refresh();
})();

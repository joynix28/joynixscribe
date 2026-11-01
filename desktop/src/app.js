/* JoynixScribe — logique de l'interface desktop */
(() => {
  'use strict';

  const T = window.__TAURI__;
  const invoke = T && T.core ? T.core.invoke.bind(T.core) : null;
  const listen = T && T.event ? T.event.listen.bind(T.event) : null;

  const $ = (id) => document.getElementById(id);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  const state = {
    settings: null,
    history: [],
    recording: false,
    busy: false,
    compact: false,
    live: '',
    baseText: '',
    startedAt: 0,
    poll: null,
    streamTimer: null,
    streamBusy: false,
    levels: Array.from({ length: 48 }, () => 0),
    compactLevels: Array.from({ length: 16 }, () => 0),
    pendingLevel: 0,
    lastTick: 0,
    lastPush: 0,
  };

  const PAGE_META = {
    dictate: ['Dictée', 'Parlez, JoynixScribe écrit — et insère le texte où vous voulez.'],
    history: ['Historique', 'Toutes vos transcriptions, conservées localement.'],
    settings: ['Réglages', 'Fournisseurs, langue, raccourci et confidentialité.'],
    about: ['À propos', 'JoynixScribe, licence et liens.'],
  };

  /* ── utilitaires ─────────────────────────────────────── */

  async function call(cmd, args) {
    if (!invoke) throw new Error("Le pont applicatif n'est pas chargé.");
    try {
      return await invoke(cmd, args);
    } catch (e) {
      const msg = typeof e === 'string' ? e : (e && e.message) || String(e);
      throw new Error(msg);
    }
  }

  const SVG_NS = 'http://www.w3.org/2000/svg';

  function svgIcon(href) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'ico');
    const use = document.createElementNS(SVG_NS, 'use');
    use.setAttribute('href', href);
    svg.appendChild(use);
    return svg;
  }

  function toast(message, kind = 'info') {
    const box = $('toasts');
    if (!box) return;
    const el = document.createElement('div');
    el.className = 'toast ' + kind;
    const href = kind === 'error' ? '#i-alert' : kind === 'ok' ? '#i-check' : '#i-bolt';
    const span = document.createElement('span');
    span.textContent = message;
    el.append(svgIcon(href), span);
    box.appendChild(el);
    setTimeout(() => {
      el.style.opacity = '0';
      el.style.transform = 'translateY(6px)';
      setTimeout(() => el.remove(), 220);
    }, kind === 'error' ? 7000 : 3600);
  }

  async function copyToClipboard(text) {
    if (!text) return toast('Rien à copier.', 'info');
    try {
      await call('copy_text', { text });
      toast('Texte copié.', 'ok');
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  async function insertText(text) {
    if (!text) return toast('Rien à insérer.', 'info');
    try {
      await call('type_text', { text });
      toast('Texte inséré dans la fenêtre active.', 'ok');
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  function mergeText(a, b) {
    a = (a || '').trim();
    b = (b || '').trim();
    if (!a) return b;
    if (!b) return a;
    const aw = a.split(/\s+/);
    const bw = b.split(/\s+/);
    const max = Math.min(14, aw.length, bw.length);
    for (let k = max; k > 0; k--) {
      const tail = aw.slice(-k).join(' ').toLowerCase().replace(/[.,;:!?]/g, '');
      const head = bw.slice(0, k).join(' ').toLowerCase().replace(/[.,;:!?]/g, '');
      if (tail === head) return aw.concat(bw.slice(k)).join(' ');
    }
    return a + ' ' + b;
  }

  function fmtTime(secs) {
    const s = Math.max(0, Math.floor(secs));
    return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
  }

  function fmtDate(ms) {
    try {
      return new Date(ms).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' });
    } catch (_) {
      return '';
    }
  }

  function setState(label, cls) {
    const el = $('recState');
    el.className = 'state' + (cls ? ' ' + cls : '');
    el.replaceChildren();
    const dot = document.createElement('i');
    dot.className = 'dot';
    el.append(dot, document.createTextNode(label));
    const c = $('compactState');
    if (c) c.textContent = label;
  }

  function setTimer(text) {
    $('recTimer').textContent = text;
    const c = $('compactTimer');
    if (c) c.textContent = text;
  }

  function setTranscript(text) {
    $('transcript').value = text;
    updateWordCount();
    $('transcript').scrollTop = $('transcript').scrollHeight;
  }

  function updateWordCount() {
    const words = $('transcript').value.trim().split(/\s+/).filter(Boolean).length;
    $('wordCount').textContent = words + (words === 1 ? ' mot' : ' mots');
  }

  function updateUsage(used, limit) {
    $('usageMini').textContent = used || 0;
    $('usageLine').textContent =
      (used || 0) + (limit > 0 ? ' / ' + limit : '') + " dictée" + (used === 1 ? '' : 's') + " aujourd'hui";
  }

  /* ── navigation ──────────────────────────────────────── */

  function showPage(name) {
    $$('.nav-item').forEach((b) => b.classList.toggle('is-active', b.dataset.page === name));
    $$('.page').forEach((p) => p.classList.toggle('is-active', p.id === 'page-' + name));
    const meta = PAGE_META[name] || PAGE_META.dictate;
    $('pageTitle').textContent = meta[0];
    $('pageSub').textContent = meta[1];
  }

  /* ── forme d'onde ────────────────────────────────────── */

  function roundRect(ctx, x, y, w, h, r) {
    const radius = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.arcTo(x + w, y, x + w, y + h, radius);
    ctx.arcTo(x + w, y + h, x, y + h, radius);
    ctx.arcTo(x, y + h, x, y, radius);
    ctx.arcTo(x, y, x + w, y, radius);
    ctx.closePath();
  }

  function drawWave(canvas, levels, active) {
    if (!canvas || !canvas.getContext) return;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth || canvas.width || 300;
    const h = canvas.clientHeight || canvas.height || 56;
    const pw = Math.max(1, Math.round(w * dpr));
    const ph = Math.max(1, Math.round(h * dpr));
    if (canvas.width !== pw || canvas.height !== ph) {
      canvas.width = pw;
      canvas.height = ph;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    const n = levels.length;
    const gap = n > 24 ? 3 : 2;
    const bw = Math.max(2, (w - gap * (n - 1)) / n);
    let fill = active ? '#8b6bff' : 'rgba(164, 157, 189, 0.35)';
    if (active && ctx.createLinearGradient) {
      const grad = ctx.createLinearGradient(0, 0, w, 0);
      grad.addColorStop(0, '#8b6bff');
      grad.addColorStop(1, '#c0a8ff');
      fill = grad;
    }
    ctx.fillStyle = fill;
    for (let i = 0; i < n; i++) {
      const v = Math.max(0.05, Math.min(1, levels[i]));
      const bh = Math.max(3, v * (h - 4));
      roundRect(ctx, i * (bw + gap), (h - bh) / 2, bw, bh, 2.5);
      ctx.fill();
    }
  }

  function animationLoop(now) {
    const dt = now - (state.lastTick || now);
    state.lastTick = now;
    if (state.recording && now - state.lastPush > 70) {
      state.lastPush = now;
      const v = Math.min(1, state.pendingLevel * 7);
      state.levels.shift();
      state.levels.push(v);
      state.compactLevels.shift();
      state.compactLevels.push(v);
    } else if (!state.recording) {
      state.levels = state.levels.map((v) => v * 0.9);
      state.compactLevels = state.compactLevels.map((v) => v * 0.9);
    }
    if (dt >= 0) {
      drawWave($('wave'), state.levels, state.recording);
      drawWave($('compactWave'), state.compactLevels, state.recording);
    }
    requestAnimationFrame(animationLoop);
  }

  async function pollLevel() {
    if (!state.recording) return;
    try {
      const s = await call('record_status');
      state.pendingLevel = s.level || 0;
      setTimer(fmtTime(s.seconds || 0));
    } catch (_) {
      /* on ignore : le thread d'arrêt gère les erreurs */
    }
  }

  /* ── enregistrement ──────────────────────────────────── */

  function requestSettings() {
    const s = Object.assign({}, state.settings);
    const today = new Date().toISOString().slice(0, 10);
    s.prompt = (s.prompt || '')
      .replace(/\{\{lang\}\}/g, s.language || 'auto')
      .replace(/\{\{date\}\}/g, today)
      .replace(/\{\{app\}\}/g, 'Windows');
    return s;
  }

  async function runTranscribe(wavB64, seconds) {
    const r = await call('transcribe', {
      settings: requestSettings(),
      wavB64,
      durationSecs: seconds,
      appHint: '',
    });
    if (Array.isArray(r.history)) {
      state.history = r.history;
      renderHistory();
    }
    if (state.settings) {
      state.settings.used_today = r.used_today;
      state.settings.daily_limit = r.daily_limit;
    }
    updateUsage(r.used_today, r.daily_limit);
    $('providerPill').textContent = r.provider;
    $('txMeta').textContent = r.provider + ' · ' + r.latency_ms + ' ms' + (r.fallback ? ' · secours' : '');
    if (r.fallback) toast('Fournisseur principal indisponible — secours Aperture utilisé.', 'info');
    return r;
  }

  async function startRec() {
    if (state.busy || state.recording) return;
    state.busy = true;
    setState('Démarrage…', 'is-busy');
    try {
      await call('record_start');
      state.recording = true;
      state.live = '';
      state.baseText = $('transcript').value;
      state.startedAt = performance.now();
      state.pendingLevel = 0;
      setRecordUI(true);
      setState('Enregistrement', 'is-live');
      setTimer('00:00');
      state.poll = setInterval(pollLevel, 120);
      if ($('streaming').checked) {
        state.streamTimer = setInterval(streamTick, 3200);
      }
    } catch (e) {
      setState('Erreur micro', 'is-error');
      toast(e.message, 'error');
    } finally {
      state.busy = false;
    }
  }

  async function stopRec() {
    if (!state.recording) return;
    state.recording = false;
    setRecordUI(false);
    clearInterval(state.poll);
    clearInterval(state.streamTimer);
    state.poll = null;
    state.streamTimer = null;
    state.busy = true;
    setState('Transcription…', 'is-busy');
    try {
      const res = await call('record_stop');
      let text = state.live;
      if (res.wav_b64 && res.seconds >= 0.4) {
        const r = await runTranscribe(res.wav_b64, res.seconds);
        text = mergeText(text, r.text);
        setTranscript(mergeText(state.baseText, text));
        if (state.settings && state.settings.auto_type) {
          await call('type_text', { text: r.text });
        }
      } else if (text) {
        setTranscript(mergeText(state.baseText, text));
      } else {
        setState('Rien à transcrire', '');
        toast("Aucune parole détectée — vérifie le micro sélectionné dans Windows.", 'error');
        return;
      }
      setState('Terminé', '');
      toast('Transcription prête.', 'ok');
    } catch (e) {
      setState('Erreur', 'is-error');
      toast(e.message, 'error');
    } finally {
      state.busy = false;
      state.pendingLevel = 0;
      setTimer('00:00');
      if (state.compact) $('compactState').textContent = 'Prêt';
    }
  }

  async function streamTick() {
    if (!state.recording || state.streamBusy) return;
    state.streamBusy = true;
    try {
      const snap = await call('record_snapshot');
      if (!snap.wav_b64 || snap.seconds < 0.8 || snap.rms < 0.004) return;
      const r = await runTranscribe(snap.wav_b64, snap.seconds);
      state.live = mergeText(state.live, r.text);
      setTranscript(mergeText(state.baseText, state.live));
      setState('Enregistrement · streaming', 'is-live');
    } catch (_) {
      /* le mode streaming ne doit jamais interrompre la dictée */
    } finally {
      state.streamBusy = false;
    }
  }

  function setRecordUI(active) {
    const btn = $('recordBtn');
    btn.classList.toggle('is-recording', active);
    btn.setAttribute('aria-label', active ? 'Arrêter la dictée' : 'Démarrer la dictée');
    $('recordLabel').textContent = active ? 'Arrêter' : 'Parler';
    $('compactMic').classList.toggle('is-recording', active);
    renderRecHint(active);
  }

  // Compose « … Ctrl+Alt+Espace … » avec des nœuds DOM (pas d'innerHTML).
  function renderRecHint(active) {
    const el = $('recHint');
    el.textContent = '';
    const before = active ? 'Parle normalement — clique à nouveau ou ' : 'Appuie sur ';
    const after = active ? ' pour terminer.' : ' depuis n\u2019importe quelle application.';
    el.append(document.createTextNode(before));
    ['Ctrl', 'Alt', 'Espace'].forEach((key, i) => {
      if (i > 0) el.append(document.createTextNode('+'));
      const kbd = document.createElement('kbd');
      kbd.textContent = key;
      el.append(kbd);
    });
    el.append(document.createTextNode(after));
  }

  async function toggleRecord() {
    if (state.busy) return;
    if (state.recording) await stopRec();
    else await startRec();
  }

  /* ── historique ──────────────────────────────────────── */

  function renderHistory() {
    const list = $('historyList');
    const q = ($('historySearch').value || '').trim().toLowerCase();
    list.replaceChildren();
    const items = state.history.filter((h) => !q || (h.text || '').toLowerCase().includes(q));
    $('historyEmpty').classList.toggle('hidden', items.length > 0);

    for (const item of items) {
      const card = document.createElement('div');
      card.className = 'history-item';

      const head = document.createElement('div');
      head.className = 'history-head';
      const badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = item.provider || '—';
      const date = document.createElement('span');
      date.textContent = fmtDate(item.created_at);
      const dur = document.createElement('span');
      dur.textContent = Math.max(1, Math.round(item.duration_secs || 0)) + ' s';
      const actions = document.createElement('div');
      actions.className = 'history-actions';

      const copy = document.createElement('button');
      copy.className = 'icon-btn';
      copy.title = 'Copier';
      copy.dataset.copy = item.id;
      copy.appendChild(svgIcon('#i-copy'));

      const del = document.createElement('button');
      del.className = 'icon-btn';
      del.title = 'Supprimer';
      del.dataset.del = item.id;
      del.appendChild(svgIcon('#i-trash'));

      actions.append(copy, del);
      head.append(badge, date, dur, actions);

      const text = document.createElement('div');
      text.className = 'history-text';
      text.textContent = item.text || '';

      card.append(head, text);
      list.appendChild(card);
    }
  }

  async function onHistoryClick(e) {
    const copyBtn = e.target.closest('[data-copy]');
    const delBtn = e.target.closest('[data-del]');
    if (copyBtn) {
      const item = state.history.find((h) => h.id === copyBtn.dataset.copy);
      if (item) await copyToClipboard(item.text);
    }
    if (delBtn) {
      try {
        state.history = await call('history_delete', { id: delBtn.dataset.del });
        renderHistory();
      } catch (err) {
        toast(err.message, 'error');
      }
    }
  }

  /* ── réglages ────────────────────────────────────────── */

  function fillSettings(s) {
    $('geminiKey').value = s.gemini_key || '';
    $('geminiModel').value = s.gemini_model || '';
    $('groqKey').value = s.groq_key || '';
    $('groqModel').value = s.groq_model || '';
    $('cfKey').value = s.cf_key || '';
    $('cfModel').value = s.cf_model || '';
    $('language').value = s.language || 'auto';
    $('prompt').value = s.prompt || '';
    $('streaming').checked = !!s.streaming;
    $('apertureFallback').checked = !!s.aperture_fallback;
    $('autoType').checked = !!s.auto_type;
    $('alwaysOnTop').checked = !!s.always_on_top;
    $('autostart').checked = !!s.autostart;
    $('closeToTray').checked = !!s.close_to_tray;
    $('dailyLimit').value = s.daily_limit || 0;
    $('theme').value = s.theme || 'dark';
    selectProvider(s.provider || 'Gemini', false);
    updateUsage(s.used_today || 0, s.daily_limit || 0);
    $('providerPill').textContent = s.provider || 'Gemini';
    updateStreamHint();
  }

  function selectProvider(name, persist) {
    $$('#providerSeg button').forEach((b) => b.classList.toggle('is-active', b.dataset.provider === name));
    $$('.provider-block').forEach((b) => b.classList.toggle('hidden', b.dataset.block !== name));
    if (state.settings) state.settings.provider = name;
    $('providerPill').textContent = name;
    if (persist) persistSettings();
  }

  function collectSettings() {
    const base = state.settings || {};
    const active = document.querySelector('#providerSeg button.is-active');
    return Object.assign({}, base, {
      provider: active ? active.dataset.provider : base.provider,
      gemini_key: $('geminiKey').value.trim(),
      gemini_model: $('geminiModel').value.trim(),
      groq_key: $('groqKey').value.trim(),
      groq_model: $('groqModel').value.trim(),
      cf_key: $('cfKey').value.trim(),
      cf_model: $('cfModel').value.trim(),
      language: $('language').value,
      prompt: $('prompt').value,
      streaming: $('streaming').checked,
      aperture_fallback: $('apertureFallback').checked,
      auto_type: $('autoType').checked,
      always_on_top: $('alwaysOnTop').checked,
      autostart: $('autostart').checked,
      close_to_tray: $('closeToTray').checked,
      daily_limit: Math.max(0, parseInt($('dailyLimit').value, 10) || 0),
      theme: $('theme').value,
    });
  }

  let saveTimer = null;
  function persistSettings(delay = 0) {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(async () => {
      if (!invoke) return;
      try {
        const saved = await call('save_settings', { settings: collectSettings() });
        state.settings = saved;
        flashSaved();
      } catch (e) {
        toast(e.message, 'error');
      }
    }, delay);
  }

  function flashSaved() {
    const el = $('savedFlash');
    el.textContent = 'Réglages enregistrés';
    el.classList.add('show');
    setTimeout(() => el.classList.remove('show'), 1600);
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme === 'light' ? 'light' : 'dark');
  }

  async function onSaveClick() {
    const s = collectSettings();
    try {
      const saved = await call('save_settings', { settings: s });
      state.settings = saved;
      flashSaved();
      toast('Réglages enregistrés.', 'ok');
      if (invoke) {
        await call('set_always_on_top', { value: !!saved.always_on_top }).catch(() => {});
        await call('set_autostart', { enabled: !!saved.autostart }).catch(() => {});
      }
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  function updateStreamHint() {
    $('streamHint').textContent = $('streaming').checked
      ? 'Streaming actif : le texte s\u2019écrit pendant que tu parles.'
      : '';
  }

  function toggleTheme() {
    const next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    applyTheme(next);
    $('theme').value = next;
    persistSettings(120);
  }

  function openDataFolder() {
    call('open_data_folder').catch((e) => toast(e.message, 'error'));
  }

  function openMicSettings() {
    call('open_mic_settings').catch((e) => toast(e.message, 'error'));
  }

  async function onResetUsage() {
    if (!state.settings) return;
    try {
      state.settings = await call('reset_usage');
      updateUsage(0, state.settings.daily_limit);
      toast('Compteur remis à zéro.', 'ok');
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  async function onClearHistory() {
    if (!confirm("Effacer tout l'historique ?")) return;
    try {
      state.history = await call('history_clear');
      renderHistory();
      toast('Historique effacé.', 'ok');
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  async function onTestKey() {
    const btn = $('testKeyBtn');
    const out = $('testResult');
    const s = collectSettings();
    btn.disabled = true;
    out.className = 'test-result';
    out.textContent = 'Vérification…';
    try {
      const msg = await call('test_key', { settings: s });
      out.className = 'test-result ok';
      out.textContent = '✓ ' + msg;
    } catch (e) {
      out.className = 'test-result err';
      out.textContent = '✕ ' + e.message;
    } finally {
      btn.disabled = false;
    }
  }

  async function enterCompact() {
    state.compact = true;
    document.body.classList.add('is-compact');
    try {
      await call('set_compact', { compact: true });
    } catch (_) {
      // L'interface passe quand même en mode compact : seule la fenêtre native
      // garde sa taille si le redimensionnement est refusé.
    }
  }

  async function exitCompact() {
    state.compact = false;
    document.body.classList.remove('is-compact');
    try {
      await call('set_compact', { compact: false });
    } catch (_) {
      // Idem : on ne bloque pas la sortie du mode compact.
    }
  }

  /* ── câblage ─────────────────────────────────────────── */

  function bindRecording() {
    $('recordBtn').addEventListener('click', toggleRecord);
    $('compactMic').addEventListener('click', toggleRecord);
    $('compactBtn').addEventListener('click', enterCompact);
    $('compactBtn2').addEventListener('click', enterCompact);
    $('compactClose').addEventListener('click', exitCompact);

    $('copyBtn').addEventListener('click', () => copyToClipboard($('transcript').value.trim()));
    $('typeBtn').addEventListener('click', () => insertText($('transcript').value.trim()));

    $('clearBtn').addEventListener('click', () => {
      setTranscript('');
      $('txMeta').textContent = '—';
    });

    $('transcript').addEventListener('input', updateWordCount);
  }

  function bindHistory() {
    $('historySearch').addEventListener('input', renderHistory);
    $('historyList').addEventListener('click', onHistoryClick);
    $('clearHistory').addEventListener('click', onClearHistory);
  }

  function bindSettings() {
    $$('#providerSeg button').forEach((b) =>
      b.addEventListener('click', () => selectProvider(b.dataset.provider, true))
    );

    $$('.eye').forEach((b) =>
      b.addEventListener('click', () => {
        const input = $(b.dataset.eye);
        input.type = input.type === 'password' ? 'text' : 'password';
      })
    );

    ['geminiKey', 'geminiModel', 'groqKey', 'groqModel', 'cfKey', 'cfModel', 'language', 'prompt'].forEach(
      (id) => $(id).addEventListener('change', () => persistSettings(250))
    );

    ['streaming', 'apertureFallback', 'autoType', 'alwaysOnTop', 'autostart', 'closeToTray', 'dailyLimit'].forEach(
      (id) =>
        $(id).addEventListener('change', () => {
          persistSettings(120);
          if (id === 'streaming') updateStreamHint();
        })
    );

    $('theme').addEventListener('change', () => {
      applyTheme($('theme').value);
      persistSettings(120);
    });

    $('themeBtn').addEventListener('click', toggleTheme);
    $('saveBtn').addEventListener('click', onSaveClick);
    $('testKeyBtn').addEventListener('click', onTestKey);
    $('openDataBtn').addEventListener('click', openDataFolder);
    $('aboutFolderBtn').addEventListener('click', openDataFolder);
    $('micSettingsBtn').addEventListener('click', openMicSettings);
    $('resetUsageBtn').addEventListener('click', onResetUsage);
  }

  function bindGlobalKeys() {
    $$('[data-url]').forEach((el) =>
      el.addEventListener('click', (e) => {
        e.preventDefault();
        call('open_url', { url: el.dataset.url }).catch((err) => toast(err.message, 'error'));
      })
    );

    document.addEventListener('keydown', (e) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
      if (e.key === 'Escape' && state.compact) {
        exitCompact();
        return;
      }
      if (e.code === 'Space' && !typing && !e.ctrlKey && !e.altKey && !e.metaKey) {
        e.preventDefault();
        toggleRecord();
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'enter') {
        e.preventDefault();
        $('copyBtn').click();
      }
    });

    if (listen) {
      listen('hotkey-toggle', () => toggleRecord());
      listen('tray-record', () => toggleRecord());
    }
  }

  function bind() {
    $$('.nav-item').forEach((b) => b.addEventListener('click', () => showPage(b.dataset.page)));
    bindRecording();
    bindHistory();
    bindSettings();
    bindGlobalKeys();
  }

  let booted = false;
  async function boot() {
    if (booted) return;
    booted = true;
    if (!invoke) {
      $('apiError').classList.remove('hidden');
      $('recordBtn').disabled = true;
      return;
    }
    bind();
    requestAnimationFrame(animationLoop);

    try {
      const s = await call('load_settings');
      state.settings = s;
      fillSettings(s);
      applyTheme(s.theme);
    } catch (e) {
      toast('Réglages illisibles : ' + e.message, 'error');
    }

    try {
      state.history = await call('history_list');
      renderHistory();
    } catch (_) {
      // Aucun fichier d'historique au premier lancement : la liste reste vide.
    }

    try {
      $('dataPath').textContent = await call('data_dir');
      $('versionPill').textContent = await call('app_version');
    } catch (_) {
      // Informations purement décoratives : on garde les valeurs par défaut.
    }

    try {
      const info = await call('mic_info');
      if (!info.has_mic) {
        toast('Aucun micro détecté. Vérifie les paramètres Windows.', 'error');
        $('recHint').textContent = 'Aucun micro détecté — ouvre « Autorisation micro Windows » dans les réglages.';
      }
      if (!info.hotkey_ok) {
        toast('Raccourci global indisponible : une autre application utilise Ctrl+Alt+Espace.', 'error');
      }
    } catch (_) {
      // La vérification du micro est indicative : l'app reste utilisable.
    }
  }

  window.addEventListener('DOMContentLoaded', boot);
})();

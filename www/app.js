(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const COUNTRIES = window.NOMAD_COUNTRIES;
  const CONT = window.NOMAD_CONTINENTS;
  const TOTAL = COUNTRIES.length;
  const byCode = new Map(COUNTRIES.map(c => [c.c, c]));
  const byNum = new Map(COUNTRIES.map(c => [c.n, c]));
  const PRESETS = ['#2EBD59', '#F6BF26', '#E8704F', '#2BA3A0', '#4C7BD9', '#8B6CD6', '#D9629A', '#D2453E'];
  const KEY = 'nomad.v1';
  const SUBS = window.NOMAD_SUBS || [];
  const SUBMAP = new Map(SUBS.map(s => [s.c, s]));
  const info = c => byCode.get(c) || SUBMAP.get(c);
  const known = c => !!info(c);
  const flagSrc = c => `flags/${(SUBMAP.has(c) ? SUBMAP.get(c).f : c).toLowerCase()}.svg`;
  const CHECK = '<svg viewBox="0 0 24 24"><path d="m5 12 5 5 9-10"/></svg>';
  const Plugins = (window.Capacitor && window.Capacitor.Plugins) || {};

  const state = (() => {
    const d = { visited: {}, color: PRESETS[0], theme: 'dark', autoRotate: true, home: null, unit: 'n', mode: 'globe', years: {}, cities: {}, cityColor: '#FFC93C' };
    try { const s = { ...d, ...JSON.parse(localStorage.getItem(KEY) || '{}') }; s.years = s.years || {}; s.cities = s.cities || {};
      ['ES-CN', 'ES-IB', 'PT-20'].forEach(k => { delete s.visited[k]; delete s.years[k]; delete s.cities[k]; });
      return s; } catch { return d; }
  })();
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch {} };
  const isVisited = c => !!state.visited[c];
  const visitedList = () => Object.keys(state.visited).filter(c => byCode.has(c));
  const visitedCount = () => visitedList().length;
  let photoCounts = {};

  const ALIASES = { PS: 'israel' };
  const norm = s => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const esc = t => String(t).replace(/[&<>"]/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[ch]);
  const searchKey = c => norm(c.name + ' ' + (ALIASES[c.c] || ''));

  /* ---------- Géographie ---------- */
  function buildGeo(topo) {
    const obj = topo.objects.countries;
    const codeOf = id => { const c = byNum.get(id === '376' ? '275' : id); return c ? c.c : null; };
    const byC = new Map();
    const add = f => { if (!byC.has(f.code)) byC.set(f.code, []); byC.get(f.code).push(f); };
    const subById = new Map(SUBS.filter(s => s.id).map(s => [s.id, s.c]));
    const inBox = (pt, boxes) => boxes.some(b => pt[0] >= b[0] && pt[0] <= b[2] && pt[1] >= b[1] && pt[1] <= b[3]);
    topojson.feature(topo, obj).features.forEach(f => {
      f.code = subById.get(f.id) || codeOf(f.id);
      if (!f.code) return;
      const boxed = SUBS.filter(s => s.p === f.code && s.b);
      if (!boxed.length || f.geometry.type !== 'MultiPolygon') return add(f);
      const groups = new Map();
      f.geometry.coordinates.forEach(poly => {
        const ctr = d3.geoCentroid({ type: 'Polygon', coordinates: poly });
        const sub = boxed.find(s => inBox(ctr, s.b));
        const k = sub ? sub.c : f.code;
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k).push(poly);
      });
      groups.forEach((polys, k) => add({ type: 'Feature', id: f.id, code: k, properties: {}, geometry: { type: 'MultiPolygon', coordinates: polys } }));
    });
    const ip = (a, b) => (a.id === '376' && b.id === '275') || (a.id === '275' && b.id === '376');
    return {
      byC,
      land: topojson.feature(topo, topo.objects.land),
      borders: topojson.mesh(topo, obj, (a, b) => a !== b && !ip(a, b))
    };
  }
  const HI = buildGeo(window.NOMAD_WORLD);
  const LO = buildGeo(window.NOMAD_WORLD110);
  const largestCenter = feats => {
    let best = null, area = -1;
    feats.forEach(f => {
      const g = f.geometry;
      const polys = g.type === 'MultiPolygon' ? g.coordinates : g.type === 'Polygon' ? [g.coordinates] : [];
      polys.forEach(c => {
        const p = { type: 'Polygon', coordinates: c };
        const a = d3.geoArea(p);
        if (a > area) { area = a; best = p; }
      });
    });
    return best ? d3.geoCentroid(best) : null;
  };
  const GEO = new Map();
  [...COUNTRIES, ...SUBS].forEach(c => {
    const feats = HI.byC.get(c.c) || [];
    if (!feats.length && SUBMAP.has(c.c)) return;
    const area = feats.reduce((s, f) => s + d3.geoArea(f), 0);
    GEO.set(c.c, { feats, center: largestCenter(feats) || (c.c === 'TV' ? [179.2, -8.52] : [0, 0]), small: !feats.length || area < 2.2e-4 });
  });
  const SMALL = [...GEO.keys()].filter(c => GEO.get(c).small);
  const visitedGeo = () => Object.keys(state.visited).filter(c => GEO.has(c));
  const hitCountry = ll => {
    for (const [code, g] of GEO) if (g.feats.some(f => d3.geoContains(f, ll))) return code;
    return null;
  };

  /* ---------- Thème ---------- */
  const colors = {};
  const media = matchMedia('(prefers-color-scheme: dark)');
  const inkFor = hex => {
    const n = parseInt(hex.slice(1), 16);
    const l = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
    return l > 0.62 ? '#15142A' : '#FFFFFF';
  };
  function applyTheme() {
    const mode = state.theme === 'auto' ? (media.matches ? 'dark' : 'light') : state.theme;
    const root = document.documentElement;
    root.dataset.theme = mode;
    root.style.setProperty('--accent', state.color);
    root.style.setProperty('--accent-ink', inkFor(state.color));
    const cs = getComputedStyle(root);
    ['ocean', 'ocean-2', 'land', 'border', 'graticule', 'accent', 'brand', 'bg'].forEach(k => { colors[k] = cs.getPropertyValue('--' + k).trim(); });
    colors.city = state.cityColor;
    root.style.setProperty('--city', state.cityColor);
    try { Plugins.StatusBar && Plugins.StatusBar.setStyle({ style: mode === 'dark' ? 'DARK' : 'LIGHT' }); } catch {}
    globe.dirty = true;
    plan.redraw();
    mini.redraw();
  }
  media.addEventListener('change', () => state.theme === 'auto' && applyTheme());

  /* ---------- UI commune ---------- */
  function renderCounters() {
    const n = visitedCount();
    $$('[data-n]').forEach(el => { el.textContent = n; });
    $$('[data-total]').forEach(el => { el.textContent = TOTAL; });
    $$('[data-counter-text]').forEach(el => { el.textContent = `${n} pays visités sur ${TOTAL}`; });
    renderPassport();
  }

  let toastTimer;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2200);
  }

  let confirmResolve = null;
  function ask(text, yes = 'Confirmer') {
    $('#confirm-text').textContent = text;
    $('#confirm-yes').textContent = yes;
    $('#confirm').classList.add('show');
    return new Promise(r => { confirmResolve = r; });
  }
  const closeConfirm = v => { $('#confirm').classList.remove('show'); confirmResolve && confirmResolve(v); confirmResolve = null; };
  $('#confirm-yes').onclick = () => closeConfirm(true);
  $('#confirm-no').onclick = () => closeConfirm(false);
  $('#confirm').onclick = e => e.target.id === 'confirm' && closeConfirm(false);

  function setVisited(code, on, year) {
    const c = info(code);
    if (on) { state.visited[code] = Date.now(); if (year) state.years[code] = year; }
    else { delete state.visited[code]; delete state.years[code]; }
    save();
    renderCounters();
    updateVisitedUI(code);
    if (on) toast(SUBMAP.has(code) ? `${c.name} ajouté` : `${c.name} débloqué · ${visitedCount()}/${TOTAL}`);
  }

  function updateVisitedUI(code) {
    const on = isVisited(code);
    $$(`[data-c="${code}"]`).forEach(el => el.classList.toggle('visited', on));
    $$(`.check[data-toggle="${code}"]`).forEach(el => el.classList.toggle('on', on));
    globe.dirty = true;
    plan.redraw();
    if (sheet.code === code) renderSheetHead();
    applyListFilter();
    applyFlagFilter();
  }

  /* ---------- Navigation ---------- */
  let current = 'map';
  function show(view) {
    if (view === current) return;
    current = view;
    $$('.view').forEach(v => v.classList.toggle('active', v.dataset.view === view));
    $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === view));
    if (view === 'flags') buildFlags();
    if (view === 'passport') { mini.init(); renderPassport(); }
    syncMode();
  }
  function syncMode() {
    const onMap = current === 'map';
    $('#globe').hidden = state.mode !== 'globe';
    $('#plan').hidden = state.mode !== 'plan';
    $$('#mode button').forEach(b => b.classList.toggle('active', b.dataset.mode === state.mode));
    globe.active = onMap && state.mode === 'globe';
    if (globe.active) { globe.resize(); globe.start(); }
    if (onMap && state.mode === 'plan') plan.init();
  }
  $$('.tab').forEach(t => t.addEventListener('click', () => show(t.dataset.tab)));
  $$('[data-goto]').forEach(b => b.addEventListener('click', () => show(b.dataset.goto)));
  $('[data-goto-plan]').addEventListener('click', () => { state.mode = 'plan'; save(); show('map'); syncMode(); });
  $('#mode').addEventListener('click', e => {
    const b = e.target.closest('[data-mode]');
    if (!b || b.dataset.mode === state.mode) return;
    state.mode = b.dataset.mode; save(); syncMode();
  });
  $('#recenter').onclick = () => (state.mode === 'globe' ? globe.reset() : plan.reset());

  /* ---------- Villes : dessin ---------- */
  function drawCities(ctx, project, r, labelsAll, selected, k = 1) {
    const codes = Object.keys(state.cities);
    if (!codes.length) return;
    const fs = 10 / k, pad = 2 / k;
    const boxes = [];
    const pts = [];
    codes.forEach(code => (state.cities[code] || []).forEach(city => {
      const pt = project(city.c);
      if (pt) pts.push([pt, city.n, labelsAll || code === selected]);
    }));
    ctx.save();
    pts.forEach(([pt]) => {
      ctx.beginPath(); ctx.arc(pt[0], pt[1], r, 0, Math.PI * 2);
      ctx.fillStyle = colors.city; ctx.fill();
      ctx.lineWidth = 1 / k; ctx.strokeStyle = '#000'; ctx.stroke();
    });
    ctx.font = `600 ${fs}px Outfit, system-ui, sans-serif`;
    ctx.textBaseline = 'middle';
    pts.forEach(([pt, n, show]) => {
      if (!show) return;
      const w = ctx.measureText(n).width;
      const x = pt[0] + r + 3 / k, y = pt[1];
      const b = [x - pad, y - fs / 2 - pad, x + w + pad, y + fs / 2 + pad];
      if (boxes.some(o => b[0] < o[2] && b[2] > o[0] && b[1] < o[3] && b[3] > o[1])) return;
      boxes.push(b);
      ctx.lineWidth = 2.5 / k; ctx.strokeStyle = 'rgba(0,0,0,.8)'; ctx.strokeText(n, x, y);
      ctx.fillStyle = 'rgba(255,255,255,.92)'; ctx.fillText(n, x, y);
    });
    ctx.restore();
  }

  /* ---------- Globe ---------- */
  const globe = (() => {
    const canvas = $('#globe');
    const ctx = canvas.getContext('2d');
    const proj = d3.geoOrthographic().clipAngle(90);
    const path = d3.geoPath(proj, ctx);
    const grat = d3.geoGraticule().step([20, 20])();
    const g = { dirty: true, active: true, running: false, selected: null };
    let W = 0, H = 0, dpr = 1, base = 1, zoom = 1;
    const rot = [-8, -38, 0];
    let vel = [0, 0], lastInteract = 0, anim = null, hiPending = false, offY = 0;

    g.resize = () => {
      const r = canvas.getBoundingClientRect();
      if (!r.width) return;
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = r.width; H = r.height;
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
      base = Math.min(W, H - 200) / 2 * 0.78;
      g.dirty = true;
    };
    const cy = () => H / 2 + 6 + offY;
    const radius = () => base * zoom;
    const clampLat = v => Math.max(-85, Math.min(85, v));

    function draw(fast) {
      const G = fast ? LO : HI;
      const R = radius(), X = W / 2, Y = cy();
      proj.translate([X, Y]).scale(R).rotate(rot).precision(fast ? 1.6 : 0.5);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);

      const halo = ctx.createRadialGradient(X, Y, R * 0.95, X, Y, R * 1.25);
      halo.addColorStop(0, 'rgba(255, 255, 255, .10)');
      halo.addColorStop(1, 'rgba(255, 255, 255, 0)');
      ctx.fillStyle = halo;
      ctx.beginPath(); ctx.arc(X, Y, R * 1.25, 0, Math.PI * 2); ctx.fill();

      const sea = ctx.createRadialGradient(X - R * 0.35, Y - R * 0.4, R * 0.1, X, Y, R);
      sea.addColorStop(0, colors['ocean-2']);
      sea.addColorStop(1, colors.ocean);
      ctx.fillStyle = sea;
      ctx.beginPath(); ctx.arc(X, Y, R, 0, Math.PI * 2); ctx.fill();

      ctx.beginPath(); path(grat); ctx.strokeStyle = colors.graticule; ctx.lineWidth = 0.7; ctx.stroke();

      ctx.beginPath(); path(G.land); ctx.fillStyle = colors.land; ctx.fill();
      ctx.beginPath();
      visitedGeo().forEach(code => (G.byC.get(code) || []).forEach(f => path(f)));
      ctx.fillStyle = colors.accent; ctx.fill();
      ctx.beginPath(); path(G.borders); ctx.strokeStyle = colors.border; ctx.lineWidth = 0.6; ctx.lineJoin = 'round'; ctx.stroke();

      const c = proj.invert([X, Y]);
      SMALL.forEach(code => {
        const ctr = GEO.get(code).center;
        if (d3.geoDistance(ctr, c) > Math.PI / 2 - 0.02) return;
        const [x, y] = proj(ctr);
        const v = isVisited(code);
        ctx.beginPath(); ctx.arc(x, y, v ? 3.6 : 2.2, 0, Math.PI * 2);
        ctx.fillStyle = v ? colors.accent : colors.land; ctx.fill();
      });

      if (g.selected) {
        const geo = GEO.get(g.selected);
        ctx.lineWidth = 2.2; ctx.strokeStyle = colors.brand;
        if (geo.small) {
          if (d3.geoDistance(geo.center, c) < Math.PI / 2) {
            const [x, y] = proj(geo.center);
            ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.stroke();
          }
        } else { ctx.beginPath(); (G.byC.get(g.selected) || geo.feats).forEach(f => path(f)); ctx.stroke(); }
      }

      drawCities(ctx, ll => (d3.geoDistance(ll, c) < Math.PI / 2 - 0.01 ? proj(ll) : null), Math.min(3.5, 2.2 + zoom * 0.1), zoom > 3.5, g.selected);

      const shade = ctx.createRadialGradient(X - R * 0.3, Y - R * 0.35, R * 0.15, X, Y, R);
      shade.addColorStop(0, 'rgba(255, 255, 255, .07)');
      shade.addColorStop(0.65, 'rgba(0, 0, 0, 0)');
      shade.addColorStop(1, 'rgba(0, 0, 0, .38)');
      ctx.fillStyle = shade;
      ctx.beginPath(); ctx.arc(X, Y, R, 0, Math.PI * 2); ctx.fill();
      ctx.lineWidth = 1; ctx.strokeStyle = 'rgba(255, 255, 255, .14)'; ctx.stroke();
    }

    let prev = 0;
    function frame(t) {
      if (!g.active || document.hidden) { g.running = false; return; }
      const dt = prev ? Math.min(t - prev, 50) : 16;
      prev = t;
      let motion = false;
      if (anim) {
        const k = Math.min(1, (performance.now() - anim.t0) / anim.dur);
        const e = (anim.ease || d3.easeCubicInOut)(k);
        const r = anim.interp(e);
        rot[0] = r[0]; rot[1] = r[1];
        zoom = anim.z0 + (anim.z1 - anim.z0) * e;
        g.dirty = motion = true;
        if (anim.oy1 !== undefined) offY = anim.oy0 + (anim.oy1 - anim.oy0) * e;
        if (k >= 1) { const done = anim.done; anim = null; done && done(); }
      } else if (pointers.size) {
        motion = true;
      } else if (Math.abs(vel[0]) > 0.004 || Math.abs(vel[1]) > 0.004) {
        rot[0] += vel[0] * dt; rot[1] = clampLat(rot[1] + vel[1] * dt);
        const f = Math.pow(0.93, dt / 16);
        vel = [vel[0] * f, vel[1] * f];
        g.dirty = motion = true;
      } else if (state.autoRotate && !sheet.open && performance.now() - lastInteract > 2500) {
        rot[0] += 0.006 * dt;
        g.dirty = motion = true;
      }
      if (motion) {
        if (g.dirty) { draw(true); hiPending = true; }
      } else if (g.dirty || hiPending) {
        draw(false); hiPending = false;
      }
      g.dirty = false;
      requestAnimationFrame(frame);
    }
    g.start = () => {
      if (g.running) return;
      g.running = true; prev = 0;
      requestAnimationFrame(frame);
    };
    document.addEventListener('visibilitychange', () => !document.hidden && g.active && g.start());

    const pointers = new Map();
    let gesture = null;
    canvas.addEventListener('pointerdown', e => {
      canvas.setPointerCapture(e.pointerId);
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      anim = null; vel = [0, 0]; lastInteract = performance.now();
      if (pointers.size === 1) gesture = { tap: true, x0: e.clientX, y0: e.clientY, t0: performance.now(), lt: performance.now() };
      else if (pointers.size === 2) {
        const [a, b] = [...pointers.values()];
        gesture = { tap: false, d0: Math.hypot(a.x - b.x, a.y - b.y), z0: zoom };
      }
    });
    canvas.addEventListener('pointermove', e => {
      const p = pointers.get(e.pointerId);
      if (!p || !gesture) return;
      const dx = e.clientX - p.x, dy = e.clientY - p.y;
      p.x = e.clientX; p.y = e.clientY;
      lastInteract = performance.now();
      if (pointers.size >= 2) {
        const [a, b] = [...pointers.values()];
        if (gesture.d0) zoom = Math.max(0.75, Math.min(10, gesture.z0 * Math.hypot(a.x - b.x, a.y - b.y) / gesture.d0));
        g.dirty = true;
        return;
      }
      if (Math.hypot(e.clientX - gesture.x0, e.clientY - gesture.y0) > 8) gesture.tap = false;
      const k = 180 / (Math.PI * radius());
      rot[0] += dx * k; rot[1] = clampLat(rot[1] - dy * k);
      const now = performance.now(), dt = Math.max(1, now - gesture.lt);
      gesture.lt = now;
      vel = [vel[0] * 0.5 + (dx * k / dt) * 0.5, vel[1] * 0.5 + (-dy * k / dt) * 0.5];
      g.dirty = true;
    });
    const end = e => {
      if (!pointers.has(e.pointerId)) return;
      pointers.delete(e.pointerId);
      lastInteract = performance.now();
      if (gesture && gesture.tap && e.type === 'pointerup' && performance.now() - gesture.t0 < 500) {
        vel = [0, 0];
        const r = canvas.getBoundingClientRect();
        pick(e.clientX - r.left, e.clientY - r.top);
      }
      if (performance.now() - (gesture && gesture.lt || 0) > 80) vel = [0, 0];
      if (pointers.size === 1) {
        const [p] = [...pointers.values()];
        gesture = { tap: false, x0: p.x, y0: p.y, t0: 0, lt: performance.now() };
      } else if (!pointers.size) gesture = null;
    };
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    canvas.addEventListener('wheel', e => {
      e.preventDefault();
      zoom = Math.max(0.75, Math.min(10, zoom * Math.exp(-e.deltaY * 0.0015)));
      lastInteract = performance.now();
      g.dirty = true;
    }, { passive: false });

    function pick(x, y) {
      const X = W / 2, Y = cy();
      if (Math.hypot(x - X, y - Y) > radius()) return;
      proj.translate([X, Y]).scale(radius()).rotate(rot);
      if (placing) return placeAt(proj.invert([x, y]));
      const c = proj.invert([X, Y]);
      let best = null, bd = 18;
      SMALL.forEach(code => {
        const ctr = GEO.get(code).center;
        if (d3.geoDistance(ctr, c) > Math.PI / 2) return;
        const [px, py] = proj(ctr);
        const d = Math.hypot(px - x, py - y);
        if (d < bd) { bd = d; best = code; }
      });
      const hit = hitCountry(proj.invert([x, y]));
      if (hit && !(best && bd < 9)) best = hit;
      if (best) {
        if (navigator.vibrate) navigator.vibrate(8);
        openSheet(best);
      }
    }

    const fitZoom = (code, size) => {
      const geo = GEO.get(code);
      let a = 0.01;
      const pts = [geo.center, ...(state.cities[code] || []).map(c => c.c)];
      geo.feats.forEach(f => { const b = d3.geoBounds(f); pts.push(b[0], b[1], [b[0][0], b[1][1]], [b[1][0], b[0][1]]); });
      pts.forEach(pt => { a = Math.max(a, d3.geoDistance(geo.center, pt)); });
      return Math.max(1, Math.min(40, (size / 2) / (base * Math.sin(Math.min(a * 1.08, 1.4)))));
    };
    g.focus = (code, zoomIn, area) => {
      const geo = GEO.get(code);
      g.selected = code;
      g.dirty = true;
      if (!geo || !g.active) return;
      const target = [-geo.center[0], clampLat(-geo.center[1])];
      const from = [rot[0], rot[1]];
      target[0] = from[0] + ((((target[0] - from[0]) % 360) + 540) % 360 - 180);
      let z1 = zoom, oy1 = offY;
      if (zoomIn && area) {
        z1 = fitZoom(code, Math.min(W - 40, area.bottom - area.top - 30));
        oy1 = (area.top + area.bottom) / 2 - (H / 2 + 6);
      }
      anim = { t0: performance.now(), dur: 950, interp: d3.interpolate(from, target), z0: zoom, z1, oy0: offY, oy1 };
    };
    g.flyTo = (ll, z) => {
      const from = [rot[0], rot[1]];
      const target = [-ll[0], clampLat(-ll[1])];
      target[0] = from[0] + ((((target[0] - from[0]) % 360) + 540) % 360 - 180);
      anim = { t0: performance.now(), dur: 800, interp: d3.interpolate(from, target), z0: zoom, z1: Math.max(zoom, z), oy0: offY, oy1: offY };
    };
    g.unfocus = () => {
      anim = { t0: performance.now(), dur: 700, interp: d3.interpolate([rot[0], rot[1]], [rot[0], rot[1]]), z0: zoom, z1: Math.min(zoom, 1), oy0: offY, oy1: 0 };
    };
    g.top = () => canvas.getBoundingClientRect().top;
    g.lookAt = (code, z) => {
      const geo = GEO.get(code);
      if (!geo) return;
      rot[0] = -geo.center[0]; rot[1] = clampLat(-geo.center[1]);
      zoom = z; anim = null; vel = [0, 0]; g.dirty = true;
    };
    g.hold = ms => { lastInteract = performance.now() + ms; };
    g.reset = () => {
      anim = { t0: performance.now(), dur: 700, interp: d3.interpolate([rot[0], rot[1]], [rot[0], -20]), z0: zoom, z1: 1 };
    };
    return g;
  })();

  /* ---------- Carte à plat (canvas) ---------- */
  function FlatMap(canvas, interactive) {
    const ctx = canvas.getContext('2d');
    const m = { ready: false, selected: null };
    let W = 0, H = 0, dpr = 1, proj, P = null, t = d3.zoomIdentity, zoomer = null, home = d3.zoomIdentity, raf = 0, key = '';
    m.init = () => {
      const r = canvas.getBoundingClientRect();
      if (!r.width) return;
      const k = `${Math.round(r.width)}x${Math.round(r.height)}`;
      if (m.ready && k === key) return m.redraw();
      key = k;
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = r.width; H = r.height;
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
      proj = d3.geoNaturalEarth1();
      if (interactive) proj.fitSize([W, H], { type: 'Sphere' });
      else proj.fitExtent([[8, 8], [W - 8, H - 8]], { type: 'MultiPoint', coordinates: [[-168, -56], [180, 82], [-180, 82]] });
      const path = d3.geoPath(proj);
      P = { sphere: new Path2D(path({ type: 'Sphere' })), land: new Path2D(path(HI.land)), borders: new Path2D(path(HI.borders)), byC: new Map(), pts: new Map() };
      GEO.forEach((g, code) => {
        if (g.feats.length) P.byC.set(code, new Path2D(g.feats.map(f => path(f)).join('')));
        P.pts.set(code, proj(g.center));
      });
      if (interactive) {
        const b = path.bounds({ type: 'Sphere' });
        const mapH = b[1][1] - b[0][1];
        const k0 = Math.max(1, Math.min(3, (H * 0.5) / mapH));
        zoomer = d3.zoom().scaleExtent([1, 40])
          .translateExtent([[b[0][0], b[0][1] - H * 0.45], [b[1][0], b[1][1] + H * 0.45]])
          .on('zoom', e => { t = e.transform; m.redraw(); });
        d3.select(canvas).call(zoomer).on('dblclick.zoom', null);
        const [ex, ey] = proj([10, 30]);
        home = d3.zoomIdentity.translate(W / 2 - ex * k0, H / 2 - ey * k0).scale(k0);
        d3.select(canvas).call(zoomer.transform, home);
      }
      m.ready = true;
      m.redraw();
    };
    function draw() {
      if (!m.ready) return;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = interactive ? colors.bg : colors.ocean;
      ctx.fillRect(0, 0, W, H);
      ctx.setTransform(dpr * t.k, 0, 0, dpr * t.k, dpr * t.x, dpr * t.y);
      if (interactive) { ctx.fillStyle = colors.ocean; ctx.fill(P.sphere); }
      ctx.fillStyle = colors.land; ctx.fill(P.land);
      ctx.fillStyle = colors.accent;
      visitedGeo().forEach(code => { const p = P.byC.get(code); if (p) ctx.fill(p); });
      ctx.lineWidth = (interactive ? 0.6 : 0.4) / t.k; ctx.strokeStyle = colors.border; ctx.stroke(P.borders);
      const rv = (interactive ? 3 : 1.8) / Math.sqrt(t.k);
      SMALL.forEach(code => {
        const v = isVisited(code);
        if (!v && !interactive) return;
        const [x, y] = P.pts.get(code);
        ctx.beginPath(); ctx.arc(x, y, v ? rv : rv * 0.65, 0, Math.PI * 2);
        ctx.fillStyle = v ? colors.accent : colors.land; ctx.fill();
      });
      if (interactive) drawCities(ctx, ll => proj(ll), 2.4 / t.k, t.k > 4, m.selected, t.k);
      else drawCities(ctx, ll => proj(ll), 1.6, false, null);
      if (m.selected) {
        ctx.lineWidth = 2 / t.k; ctx.strokeStyle = colors.brand;
        const p = P.byC.get(m.selected);
        if (p && !GEO.get(m.selected).small) ctx.stroke(p);
        else { const [x, y] = P.pts.get(m.selected); ctx.beginPath(); ctx.arc(x, y, 8 / t.k, 0, Math.PI * 2); ctx.stroke(); }
      }
    }
    m.redraw = () => { if (m.ready && !raf) raf = requestAnimationFrame(() => { raf = 0; draw(); }); };
    m.select = code => { m.selected = code; m.redraw(); };
    m.zoomTo = (code, area) => {
      if (!zoomer || !GEO.has(code)) return;
      const geo = GEO.get(code);
      const path = d3.geoPath(proj);
      const pts = [proj(geo.center), ...(state.cities[code] || []).map(c => proj(c.c))];
      geo.feats.forEach(f => { const b = path.bounds(f); pts.push(b[0], b[1]); });
      const xs = pts.map(q => q[0]), ys = pts.map(q => q[1]);
      const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
      const top = area ? area.top : 70, bottom = area ? area.bottom : H - 70;
      const k = Math.max(1, Math.min(40, 0.85 / Math.max((x1 - x0 + 6) / W, (y1 - y0 + 6) / (bottom - top))));
      const tr = d3.zoomIdentity.translate(W / 2 - k * (x0 + x1) / 2, (top + bottom) / 2 - k * (y0 + y1) / 2).scale(k);
      d3.select(canvas).transition().duration(800).call(zoomer.transform, tr);
    };
    m.top = () => canvas.getBoundingClientRect().top;
    m.reset = () => zoomer && d3.select(canvas).transition().duration(600).call(zoomer.transform, home);
    m.invalidate = () => { m.ready = false; key = ''; };
    if (interactive) {
      canvas.addEventListener('click', e => {
        if (!m.ready) return;
        const r = canvas.getBoundingClientRect();
        const [px, py] = t.invert([e.clientX - r.left, e.clientY - r.top]);
        if (placing) { const ll = proj.invert([px, py]); return ll && placeAt(ll); }
        let best = null, bd = 14 / t.k;
        SMALL.forEach(code => {
          const [x, y] = P.pts.get(code);
          const d = Math.hypot(x - px, y - py);
          if (d < bd) { bd = d; best = code; }
        });
        const ll = proj.invert([px, py]);
        const hit = ll && hitCountry(ll);
        if (hit && !(best && bd < 7 / t.k)) best = hit;
        if (best) { if (navigator.vibrate) navigator.vibrate(8); openSheet(best); }
      });
    }
    return m;
  }
  const plan = FlatMap($('#plan'), true);
  new ResizeObserver(() => globe.resize()).observe($('#globe'));
  const mini = FlatMap($('#mini'), false);

  /* ---------- Passeport ---------- */
  $('#unit').addEventListener('click', e => {
    const b = e.target.closest('[data-unit]');
    if (!b) return;
    state.unit = b.dataset.unit; save(); renderPassport();
  });
  function renderPassport() {
    const list = visitedList(), n = list.length, pct = Math.round(n / TOTAL * 100);
    $('#big-num').innerHTML = state.unit === 'pct' ? `${pct}<small>%</small>` : `${n}<small>/ ${TOTAL}</small>`;
    $('#big-bar').style.width = Math.max(pct, n ? 2 : 0) + '%';
    $$('#unit button').forEach(b => b.classList.toggle('active', b.dataset.unit === state.unit));
    const per = {}, tot = {};
    COUNTRIES.forEach(c => { tot[c.k] = (tot[c.k] || 0) + 1; if (isVisited(c.c)) per[c.k] = (per[c.k] || 0) + 1; });
    $('#st-cont').textContent = Object.keys(per).length;
    $('#by-continent').innerHTML = Object.keys(CONT).map(k => {
      const v = per[k] || 0, p = Math.round(v / tot[k] * 100);
      return `<div class="cont-row"><div>${CONT[k]}<span>${state.unit === 'pct' ? p + ' %' : `${v} / ${tot[k]}`}</span></div><div class="bar"><i style="width:${p}%"></i></div></div>`;
    }).join('');
    const recent = list.sort((a, b) => state.visited[b] - state.visited[a]).slice(0, 6);
    $('#recent').innerHTML = recent.length
      ? recent.map(c => `<button data-open="${c}"><img src="${flagSrc(c)}" alt=""><span>${byCode.get(c).name}</span>${state.years[c] ? `<em>${state.years[c]}</em>` : '<i class="dot"></i>'}</button>`).join('')
      : '<div class="empty">Aucun pays pour l\'instant. Touchez un pays sur la carte pour commencer.</div>';
    const photos = Object.values(photoCounts).reduce((s, v) => s + v, 0);
    const albums = Object.values(photoCounts).filter(v => v > 0).length;
    $('#st-photos').textContent = photos;
    $('#st-albums').textContent = `${albums} album${albums > 1 ? 's' : ''}`;
    mini.redraw();
  }
  $('#recent').addEventListener('click', e => { const b = e.target.closest('[data-open]'); b && openSheet(b.dataset.open); });

  /* ---------- Drapeaux ---------- */
  const filters = { flags: 'all', list: 'all' };
  $$('[data-filter-group]').forEach(group => {
    group.addEventListener('click', e => {
      const b = e.target.closest('[data-filter]');
      if (!b) return;
      filters[group.dataset.filterGroup] = b.dataset.filter;
      $$('.pill', group).forEach(p => p.classList.toggle('active', p === b));
      group.dataset.filterGroup === 'flags' ? applyFlagFilter() : applyListFilter();
    });
  });
  const passes = (code, f) => f === 'all' || (f === 'visited') === isVisited(code);

  let flagsBuilt = false;
  function buildFlags() {
    if (flagsBuilt) return;
    flagsBuilt = true;
    const grid = $('#flag-grid');
    grid.innerHTML = COUNTRIES.map(c =>
      `<button class="flag${isVisited(c.c) ? ' visited' : ''}" data-c="${c.c}"><img src="${flagSrc(c.c)}" alt="" loading="lazy" decoding="async"><span>${c.name}</span></button>`).join('');
    grid.addEventListener('click', e => { const b = e.target.closest('[data-c]'); b && openSheet(b.dataset.c); });
    applyFlagFilter();
  }
  function applyFlagFilter() {
    if (!flagsBuilt) return;
    $$('#flag-grid .flag').forEach(el => { el.hidden = !passes(el.dataset.c, filters.flags); });
  }

  /* ---------- Recherche ---------- */
  let listBuilt = false;
  function buildList() {
    if (listBuilt) return;
    listBuilt = true;
    const ul = $('#country-list');
    const row = (c, q, sub) =>
      `<li data-row="${c.c}" data-q="${q}"${sub ? ' class="sub"' : ''}><button class="open" data-open="${c.c}"><img src="${flagSrc(c.c)}" alt="" loading="lazy"><div><div class="nm">${c.name}</div><div class="ph" data-ph="${c.c}"></div></div></button><button class="check${isVisited(c.c) ? ' on' : ''}" data-toggle="${c.c}" aria-label="Visité">${CHECK}</button></li>`;
    ul.innerHTML = COUNTRIES.map(c => row(c, searchKey(c), false)
      + SUBS.filter(s => s.p === c.c && GEO.has(s.c)).map(s => row(s, norm(s.name + ' ' + (s.g || '') + ' ' + c.name), true)).join('')).join('')
      + '<li class="empty" hidden>Aucun pays trouvé</li>';
    ul.addEventListener('click', e => {
      const t = e.target.closest('[data-toggle]');
      if (t) { if (navigator.vibrate) navigator.vibrate(8); return toggleVisit(t.dataset.toggle); }
      const o = e.target.closest('[data-open]');
      if (o) { closeFinder(); show('map'); setTimeout(() => openSheet(o.dataset.open), 120); }
    });
    renderPhotoCounts();
    applyListFilter();
  }
  let listTimer;
  function applyListFilter() {
    if (!listBuilt) return;
    const q = norm($('#search').value.trim());
    let any = false;
    $$('#country-list [data-row]').forEach(li => {
      const ok = passes(li.dataset.row, filters.list) && (!q || li.dataset.q.includes(q));
      li.hidden = !ok; any = any || ok;
    });
    $('#country-list .empty').hidden = any;
  }
  $('#search').addEventListener('input', () => { clearTimeout(listTimer); listTimer = setTimeout(applyListFilter, 80); });
  function renderPhotoCounts() {
    $$('[data-ph]').forEach(el => {
      const n = photoCounts[el.dataset.ph] || 0;
      el.textContent = n ? `${n} photo${n > 1 ? 's' : ''}` : '';
    });
    renderPassport();
  }
  const finderOpen = () => $('#finder').classList.contains('show');
  function openFinder() {
    buildList();
    $('#finder').classList.add('show');
    $('#finder').setAttribute('aria-hidden', 'false');
    setTimeout(() => $('#search').focus(), 250);
  }
  function closeFinder() {
    $('#finder').classList.remove('show');
    $('#finder').setAttribute('aria-hidden', 'true');
    $('#search').blur();
  }
  $('#open-finder').onclick = openFinder;
  $('#finder-close').onclick = closeFinder;

  /* ---------- Fiche pays + album ---------- */
  const sheet = { code: null, open: false, photos: [], urls: [] };
  const sheetEl = $('#sheet');

  function renderSheetHead() {
    const c = info(sheet.code);
    const v = isVisited(c.c);
    const n = sheet.photos.length;
    const y = state.years[c.c];
    $('#sheet-flag').src = flagSrc(c.c);
    $('#sheet-name').textContent = c.name;
    const parent = SUBMAP.has(c.c) ? ` · ${c.g ? c.g + ', ' : ''}${byCode.get(c.p).name}` : '';
    $('#sheet-sub').textContent = (v ? (y ? `Visité · première visite en ${y}` : 'Visité') : 'Pas encore visité') + parent;
    const btn = $('#visit-btn');
    btn.classList.toggle('on', v);
    btn.innerHTML = v ? `${CHECK}<span>Visité</span>` : '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg><span>Marquer comme visité</span>';
    $('#year-row').hidden = !v;
    $('#year-value').textContent = y || 'Ajouter';
    $('#album-count').textContent = n ? `${n} photo${n > 1 ? 's' : ''}` : '';
    $('#hl-label').textContent = n ? c.name : 'Vide';
    $('#hl-open').classList.toggle('is-empty', !n);
  }

  const outlineCache = new Map();
  function outline(code) {
    if (outlineCache.has(code)) return outlineCache.get(code);
    const geo = GEO.get(code);
    let best = null, area = -1;
    geo.feats.forEach(f => {
      const g = f.geometry;
      (g.type === 'MultiPolygon' ? g.coordinates : g.type === 'Polygon' ? [g.coordinates] : []).forEach(c => {
        const p = { type: 'Polygon', coordinates: c };
        const a = d3.geoArea(p);
        if (a > area) { area = a; best = p; }
      });
    });
    let d = '';
    if (best) {
      const ctr = d3.geoCentroid(best);
      const pr = d3.geoAzimuthalEqualArea().rotate([-ctr[0], -ctr[1]]).fitExtent([[18, 18], [82, 82]], best);
      d = d3.geoPath(pr)(best);
    }
    outlineCache.set(code, d);
    return d;
  }
  const shapeSVG = code => {
    const d = outline(code);
    return d ? `<path d="${d}"/>` : `<image href="${flagSrc(code)}" x="20" y="30" width="60" height="40"/>`;
  };

  async function loadAlbum() {
    const code = sheet.code;
    let photos = [];
    try { photos = await PhotoDB.byCountry(code); } catch {}
    if (code !== sheet.code) return;
    sheet.photos = photos;
    renderSheetHead();
  }

  async function toggleVisit(code) {
    if (isVisited(code)) return setVisited(code, false);
    const y = await pickYear(code);
    if (y === undefined) return;
    setVisited(code, true, y);
    if (!sheet.open || sheet.code !== code) {
      if (finderOpen()) closeFinder();
      if (current !== 'map') show('map');
      openSheet(code);
    }
    askPhotos(code);
  }

  /* Sélecteur d'année (roue) + étape photos */
  const wheel = $('#wheel');
  const IH = 44;
  let years = [], yearResolve = null, wheelIdx = -1;
  const modal = $('#years');
  const step = name => { $('#step-year').hidden = name !== 'year'; $('#step-photos').hidden = name !== 'photos'; };
  function markWheel() {
    const k = Math.max(0, Math.min(years.length - 1, Math.round(wheel.scrollTop / IH)));
    if (k === wheelIdx) return;
    wheelIdx = k;
    $$('div', wheel).forEach((el, n) => {
      const d = Math.abs(n - k);
      el.className = d === 0 ? 'on' : d === 1 ? 'near' : '';
    });
    if (navigator.vibrate && modal.classList.contains('show')) navigator.vibrate(3);
  }
  let wheelRaf = 0;
  wheel.addEventListener('scroll', () => { if (!wheelRaf) wheelRaf = requestAnimationFrame(() => { wheelRaf = 0; markWheel(); }); });
  wheel.addEventListener('click', e => {
    const el = e.target.closest('[data-y]');
    if (el) wheel.scrollTo({ top: years.indexOf(+el.dataset.y) * IH, behavior: 'smooth' });
  });
  function pickYear(code, current) {
    const c = info(code);
    const now = new Date().getFullYear();
    years = d3.range(now, 1939, -1);
    $('#years-title').textContent = c.name;
    wheel.innerHTML = years.map(y => `<div data-y="${y}">${y}</div>`).join('');
    wheelIdx = -1;
    step('year');
    modal.classList.add('show');
    modal.setAttribute('aria-hidden', 'false');
    const k = Math.max(0, years.indexOf(current || now));
    requestAnimationFrame(() => { wheel.scrollTop = k * IH; markWheel(); });
    return new Promise(r => { yearResolve = r; });
  }
  const hideModal = () => { modal.classList.remove('show'); modal.setAttribute('aria-hidden', 'true'); };
  const closeYears = v => {
    hideModal();
    const r = yearResolve; yearResolve = null;
    r && r(v);
  };
  $('#years-ok').onclick = () => closeYears(years[Math.round(wheel.scrollTop / IH)]);
  $('#years-skip').onclick = () => closeYears(null);
  $('#years-close').onclick = () => (yearResolve ? closeYears(undefined) : hideModal());
  modal.onclick = e => { if (e.target === modal) yearResolve ? closeYears(undefined) : hideModal(); };

  function askPhotos(code) {
    $('#step-shape').innerHTML = shapeSVG(code);
    $('#step-title').textContent = `Ton album : ${info(code).name}`;
    step('photos');
    modal.classList.add('show');
    modal.setAttribute('aria-hidden', 'false');
  }
  $('#photos-add').onclick = () => { hideModal(); $('#file').click(); };
  $('#photos-later').onclick = hideModal;

  $('#year-row').onclick = async () => {
    const code = sheet.code;
    const y = await pickYear(code, state.years[code]);
    if (y === undefined) return;
    if (y) state.years[code] = y; else delete state.years[code];
    save();
    renderSheetHead();
    renderPassport();
  };

  function openSheet(code) {
    if (!known(code)) return;
    sheet.code = code;
    sheet.photos = [];
    sheet.open = true;
    sheet.openedAt = performance.now();
    $('#hl-shape').innerHTML = shapeSVG(code);
    renderSheetHead();
    sheetEl.style.transform = '';
    sheetEl.classList.add('show');
    $('#scrim').classList.add('show');
    plan.select(code);
    renderCities();
    loadAlbum();
    requestAnimationFrame(() => focusArea(code));
  }
  function focusArea(code) {
    const sheetTop = window.innerHeight - sheetEl.offsetHeight;
    const off = state.mode === 'globe' ? globe.top() : plan.top();
    const area = { top: 78, bottom: Math.max(240, sheetTop - off - 8) };
    if (state.mode === 'globe') globe.focus(code, true, area);
    else plan.zoomTo(code, area);
  }
  function closeSheet() {
    if (!sheet.open) return;
    sheet.open = false;
    sheetEl.classList.remove('show');
    sheetEl.style.transform = '';
    $('#scrim').classList.remove('show');
    globe.selected = null; globe.dirty = true;
    if (globe.active && !placing) globe.unfocus();
    plan.select(null);
    setTimeout(() => { if (!sheet.open) sheet.photos = []; }, 400);
  }
  $('#scrim').onclick = () => { if (performance.now() - (sheet.openedAt || 0) > 450) closeSheet(); };
  $('#sheet-close').onclick = closeSheet;
  $('#visit-btn').onclick = () => {
    if (navigator.vibrate) navigator.vibrate(12);
    toggleVisit(sheet.code);
  };

  (() => {
    let y0 = null, dy = 0;
    const start = e => { if (e.target.closest('button')) return; y0 = e.clientY; dy = 0; sheetEl.classList.add('dragging'); e.currentTarget.setPointerCapture(e.pointerId); };
    const move = e => { if (y0 === null) return; dy = Math.max(0, e.clientY - y0); sheetEl.style.transform = `translateY(${dy}px)`; };
    const end = () => {
      if (y0 === null) return;
      y0 = null; sheetEl.classList.remove('dragging');
      if (dy > 110) closeSheet(); else sheetEl.style.transform = '';
    };
    $$('.grab, .sheet-head').forEach(el => {
      el.addEventListener('pointerdown', start);
      el.addEventListener('pointermove', move);
      el.addEventListener('pointerup', end);
      el.addEventListener('pointercancel', end);
    });
  })();

  $('#add-photo').onclick = () => $('#file').click();
  $('#hl-open').onclick = () => (sheet.photos.length ? openViewer(0) : $('#file').click());

  async function decode(file) {
    try { return await createImageBitmap(file, { imageOrientation: 'from-image' }); }
    catch {
      return new Promise((res, rej) => {
        const i = new Image(), u = URL.createObjectURL(file);
        i.onload = () => { URL.revokeObjectURL(u); res(i); };
        i.onerror = () => { URL.revokeObjectURL(u); rej(new Error('image')); };
        i.src = u;
      });
    }
  }
  function encode(src, max, q) {
    const w = src.width, h = src.height, sc = Math.min(1, max / Math.max(w, h));
    const cv = document.createElement('canvas');
    cv.width = Math.round(w * sc); cv.height = Math.round(h * sc);
    cv.getContext('2d').drawImage(src, 0, 0, cv.width, cv.height);
    return new Promise((res, rej) => cv.toBlob(b => { cv.width = cv.height = 0; b ? res(b) : rej(new Error('encode')); }, 'image/jpeg', q));
  }

  const FSP = Plugins.Filesystem;
  const NATIVE_FS = !!(FSP && window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
  const blobToB64 = async blob => (await toDataURL(blob)).split(',')[1];
  const newId = () => `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  async function storePhoto(code, blob, date, id = newId()) {
    const rec = { id, country: code, date: date || Date.now() };
    if (NATIVE_FS) {
      rec.file = `photos/${id}.jpg`;
      await FSP.writeFile({ path: rec.file, data: await blobToB64(blob), directory: 'DATA', recursive: true });
    } else rec.blob = blob;
    await PhotoDB.add(rec);
  }
  async function photoSrc(p) {
    if (p.file) { const { uri } = await FSP.getUri({ path: p.file, directory: 'DATA' }); return window.Capacitor.convertFileSrc(uri); }
    return URL.createObjectURL(p.blob);
  }
  async function photoDataURL(p) {
    if (p.file) { const r = await FSP.readFile({ path: p.file, directory: 'DATA' }); return 'data:image/jpeg;base64,' + r.data; }
    return toDataURL(p.blob);
  }
  async function deletePhoto(p) {
    await PhotoDB.remove(p.id);
    if (p.file) { try { await FSP.deleteFile({ path: p.file, directory: 'DATA' }); } catch {} }
  }
  async function migratePhotos() {
    const legacy = { 'ES-CN': 'ES', 'ES-IB': 'ES', 'PT-20': 'PT' };
    const ids = await PhotoDB.keys();
    for (const id of ids) {
      const p = await PhotoDB.get(id);
      if (!p) continue;
      const country = legacy[p.country] || p.country;
      if (NATIVE_FS && p.blob && !p.file) {
        try {
          const file = `photos/${p.id}.jpg`;
          await FSP.writeFile({ path: file, data: await blobToB64(p.blob), directory: 'DATA', recursive: true });
          await PhotoDB.add({ id: p.id, country, date: p.date, file });
        } catch { return; }
      } else if (country !== p.country) await PhotoDB.add({ ...p, country });
    }
  }

  $('#file').addEventListener('change', async e => {
    const files = [...e.target.files];
    e.target.value = '';
    if (!files.length) return;
    const code = sheet.code;
    $('#hl-open').classList.add('loading');
    $('#hl-label').textContent = 'Import…';
    let ok = 0;
    for (const f of files) {
      try {
        const src = await decode(f);
        const blob = await encode(src, 2048, 0.85);
        if (src.close) src.close();
        await storePhoto(code, blob);
        ok++;
        $('#hl-label').textContent = `${ok}/${files.length}`;
      } catch (err) { console.error('photo', err); }
    }
    $('#hl-open').classList.remove('loading');
    photoCounts[code] = (photoCounts[code] || 0) + ok;
    renderPhotoCounts();
    if (ok < files.length) toast(`${files.length - ok} photo(s) n'ont pas pu être ajoutées`);
    if (ok && !isVisited(code)) setVisited(code, true);
    else if (ok) toast(`${ok} photo${ok > 1 ? 's' : ''} ajoutée${ok > 1 ? 's' : ''}`);
    if (sheet.code === code && sheet.open) loadAlbum();
  });

  /* ---------- Villes ---------- */
  function renderCities() {
    const list = state.cities[sheet.code] || [];
    $('#city-count').textContent = list.length ? `${list.length} ville${list.length > 1 ? 's' : ''}` : '';
    $('#city-chips').innerHTML = '<button class="chip add" id="city-add"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>Ajouter</button>'
      + list.map((c, i) =>
      `<span class="chip"><button data-ci="${i}"><i></i>${esc(c.n)}</button><button class="x" data-del="${i}" aria-label="Retirer ${esc(c.n)}">×</button></span>`).join('');
  }
  $('#city-chips').addEventListener('click', async e => {
    const code = sheet.code, list = state.cities[code] || [];
    if (e.target.closest('#city-add')) return openCityPick(code);
    const d = e.target.closest('[data-del]');
    if (d) {
      const c = list[+d.dataset.del];
      if (!await ask(`Retirer ${c.n} de tes villes ?`, 'Retirer')) return;
      list.splice(+d.dataset.del, 1);
      if (!list.length) delete state.cities[code];
      save(); renderCities(); globe.dirty = true; plan.redraw(); mini.redraw();
      return;
    }
    const b = e.target.closest('[data-ci]');
    if (b && list[+b.dataset.ci]) {
      if (state.mode === 'globe') globe.flyTo(list[+b.dataset.ci].c, 6);
    }
  });

  const cityCache = {};
  async function loadCities(code) {
    const sub = SUBMAP.get(code);
    const tryLoad = async cc => {
      if (cityCache[cc]) return cityCache[cc];
      try { const r = await fetch(`data/cities/${cc.toUpperCase()}.json`); if (!r.ok) throw 0; return (cityCache[cc] = await r.json()); }
      catch { return null; }
    };
    if (!sub) return (await tryLoad(code)) || [];
    const own = sub.f && sub.f.toUpperCase() !== sub.p ? await tryLoad(sub.f) : null;
    if (own) return own;
    const all = (await tryLoad(sub.p)) || [];
    const geo = GEO.get(code);
    if (!geo) return [];
    const b = geo.feats.map(f => d3.geoBounds(f));
    return all.filter(([, x, y]) => b.some(([[x0, y0], [x1, y1]]) => x >= x0 - 0.05 && x <= x1 + 0.05 && y >= y0 - 0.05 && y <= y1 + 0.05));
  }
  let pickCode = null, pickList = [];
  async function openCityPick(code) {
    pickCode = code;
    $('#city-title').textContent = info(code).name;
    $('#city-search').value = '';
    $('#city-results').innerHTML = '<li class="empty">Chargement…</li>';
    $('#citypick').classList.add('show');
    pickList = await loadCities(code);
    renderCityResults();
  }
  const closeCityPick = () => { $('#citypick').classList.remove('show'); $('#city-search').blur(); };
  function renderCityResults() {
    const raw = $('#city-search').value.trim(), q = norm(raw);
    const have = new Set((state.cities[pickCode] || []).map(c => c.n));
    let res = q ? pickList.filter(c => norm(c[0]).startsWith(q)) : pickList.slice(0, 40);
    if (q && res.length < 40) res = res.concat(pickList.filter(c => !norm(c[0]).startsWith(q) && norm(c[0]).includes(q)));
    res = res.slice(0, 60);
    $('#city-results').innerHTML = res.map((c, i) =>
      `<li><button data-pick="${i}"${have.has(c[0]) ? ' class="on"' : ''}><span>${esc(c[0])}</span>${have.has(c[0]) ? CHECK : ''}</button></li>`).join('')
      + (raw ? `<li><button class="place" data-place="1"><svg viewBox="0 0 24 24"><path d="M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/></svg><span>Placer « ${esc(raw)} » sur la carte</span></button></li>` : '')
      + (!res.length && !raw ? '<li class="empty">Aucune ville en base pour ce lieu. Tape un nom pour la placer sur la carte.</li>' : '');
    $('#city-results').dataset.res = JSON.stringify(res.map(c => [c[0], c[1], c[2]]));
  }
  let cityTimer;
  $('#city-search').addEventListener('input', () => { clearTimeout(cityTimer); cityTimer = setTimeout(renderCityResults, 90); });
  $('#city-close').onclick = closeCityPick;
  function addCity(code, n, ll) {
    const list = state.cities[code] || (state.cities[code] = []);
    if (list.some(c => c.n === n)) return toast(`${n} est déjà dans tes villes`);
    list.push({ n, c: [+ll[0].toFixed(4), +ll[1].toFixed(4)] });
    if (!isVisited(code)) state.visited[code] = Date.now();
    save();
    renderCounters();
    updateVisitedUI(code);
    if (sheet.code === code) renderCities();
    globe.dirty = true; plan.redraw(); mini.redraw();
    toast(`${n} ajoutée`);
  }
  $('#city-results').addEventListener('click', e => {
    const b = e.target.closest('[data-pick]');
    if (b) {
      const c = JSON.parse($('#city-results').dataset.res || '[]')[+b.dataset.pick];
      if (!c) return;
      addCity(pickCode, c[0], [c[1], c[2]]);
      closeCityPick();
      if (sheet.open && sheet.code === pickCode) requestAnimationFrame(() => focusArea(pickCode));
      return;
    }
    if (e.target.closest('[data-place]')) startPlacing(pickCode, $('#city-search').value.trim());
  });

  let placing = null;
  function startPlacing(code, name) {
    placing = { code, name };
    closeCityPick();
    sheetEl.classList.remove('show');
    $('#scrim').classList.remove('show');
    $('#place-name').textContent = name;
    $('#placebar').classList.add('show');
  }
  function stopPlacing() {
    const p = placing;
    placing = null;
    $('#placebar').classList.remove('show');
    if (p && sheet.open && sheet.code === p.code) {
      sheetEl.classList.add('show');
      $('#scrim').classList.add('show');
      sheet.openedAt = performance.now();
    }
  }
  function placeAt(ll) {
    if (!placing || !ll) return;
    addCity(placing.code, placing.name, ll);
    stopPlacing();
  }
  $('#place-cancel').onclick = stopPlacing;

  /* ---------- Visionneuse (stories) ---------- */
  const DUR = 5000;
  const story = { open: false, i: 0, urls: {}, t0: 0, elapsed: 0, paused: true, ready: false };
  const sImg = $('#story-img');
  function openViewer(i) {
    story.open = true;
    $('#story-bars').innerHTML = sheet.photos.map(() => '<i><b></b></i>').join('');
    $('#story-shape').innerHTML = shapeSVG(sheet.code);
    $('#story-name').textContent = info(sheet.code).name;
    $('#viewer').classList.add('show');
    $('#viewer').setAttribute('aria-hidden', 'false');
    goStory(i);
    requestAnimationFrame(storyLoop);
  }
  const urlFor = async i => {
    const p = sheet.photos[i];
    if (!p) return null;
    if (!story.urls[p.id]) story.urls[p.id] = await photoSrc(p);
    return story.urls[p.id];
  };
  function goStory(i) {
    const n = sheet.photos.length;
    if (i >= n) return closeViewer();
    story.i = Math.max(0, i);
    story.elapsed = 0; story.paused = false; story.ready = false;
    const p = sheet.photos[story.i];
    sImg.onload = () => { story.ready = true; story.t0 = performance.now(); };
    const token = story.token = (story.token || 0) + 1;
    urlFor(story.i).then(u => { if (u && token === story.token) sImg.src = u; }).catch(() => {});
    urlFor(story.i + 1).then(u => { if (u) new Image().src = u; }).catch(() => {});
    $('#story-date').textContent = new Date(p.date).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
    $$('#story-bars b').forEach((b, k) => { b.style.width = k < story.i ? '100%' : '0%'; });
  }
  function progress() {
    if (!story.ready) return 0;
    return story.elapsed + (story.paused ? 0 : performance.now() - story.t0);
  }
  function storyLoop() {
    if (!story.open) return;
    const k = Math.min(1, progress() / DUR);
    const bar = $$('#story-bars b')[story.i];
    if (bar) bar.style.width = k * 100 + '%';
    if (k >= 1) goStory(story.i + 1);
    requestAnimationFrame(storyLoop);
  }
  const pause = () => { if (!story.paused) { story.elapsed = progress(); story.paused = true; } };
  const resume = () => { if (story.paused) { story.paused = false; story.t0 = performance.now(); } };
  function closeViewer() {
    story.open = false;
    $('#viewer').classList.remove('show');
    $('#viewer').setAttribute('aria-hidden', 'true');
    $('#viewer').style.transform = '';
    setTimeout(() => {
      if (story.open) return;
      Object.values(story.urls).forEach(u => u.startsWith('blob:') && URL.revokeObjectURL(u));
      story.urls = {}; sImg.removeAttribute('src');
    }, 300);
  }
  $('#viewer-close').onclick = closeViewer;
  $('#viewer-delete').onclick = async () => {
    pause();
    if (!await ask('Supprimer cette photo de l\'album ?', 'Supprimer')) return resume();
    const p = sheet.photos[story.i];
    try { await deletePhoto(p); } catch { resume(); return toast('Suppression impossible'); }
    if (story.urls[p.id]) { if (story.urls[p.id].startsWith('blob:')) URL.revokeObjectURL(story.urls[p.id]); delete story.urls[p.id]; }
    photoCounts[sheet.code] = Math.max(0, (photoCounts[sheet.code] || 1) - 1);
    renderPhotoCounts();
    const i = story.i;
    await loadAlbum();
    if (!sheet.photos.length) return closeViewer();
    $('#story-bars').innerHTML = sheet.photos.map(() => '<i><b></b></i>').join('');
    goStory(Math.min(i, sheet.photos.length - 1));
  };
  (() => {
    const tap = $('#story-tap');
    let g = null;
    tap.addEventListener('pointerdown', e => {
      tap.setPointerCapture(e.pointerId);
      g = { x: e.clientX, y: e.clientY, t: performance.now(), dy: 0 };
      pause();
    });
    tap.addEventListener('pointermove', e => {
      if (!g) return;
      g.dy = Math.max(0, e.clientY - g.y);
      if (g.dy > 10) $('#viewer').style.transform = `translateY(${g.dy}px) scale(${1 - g.dy / 2000})`;
    });
    const end = e => {
      if (!g) return;
      const quick = performance.now() - g.t < 250 && Math.abs(e.clientX - g.x) < 12 && g.dy < 12;
      const dy = g.dy;
      g = null;
      $('#viewer').style.transform = '';
      if (dy > 110) return closeViewer();
      if (quick) return goStory(e.clientX < window.innerWidth / 3 ? story.i - 1 : story.i + 1);
      resume();
    };
    tap.addEventListener('pointerup', end);
    tap.addEventListener('pointercancel', end);
  })();

  /* ---------- Réglages ---------- */
  function renderSettings() {
    $('#swatches').innerHTML = PRESETS.map(c =>
      `<button class="swatch${c.toLowerCase() === state.color.toLowerCase() ? ' active' : ''}" style="background:${c}" data-color="${c}" aria-label="Couleur ${c}"></button>`).join('');
    $('#custom-color').value = state.color;
    $('#city-swatches').innerHTML = CITY_COLORS.map(c =>
      `<button class="swatch${c.toLowerCase() === state.cityColor.toLowerCase() ? ' active' : ''}" style="background:${c}" data-city-color="${c}" aria-label="Couleur ${c}"></button>`).join('');
    $$('#theme button').forEach(b => b.classList.toggle('active', b.dataset.theme === state.theme));
    $('#autorotate').checked = state.autoRotate;
    const h = byCode.get(state.home);
    $('#home-value').textContent = h ? h.name : 'Choisir';
  }
  const CITY_COLORS = ['#FFC93C', '#FFFFFF', '#FF6B6B', '#4CC9F0', '#B388FF', '#FF9F1C', '#7CFFB2', '#FF4FD8'];
  $('#city-swatches').addEventListener('click', e => {
    const b = e.target.closest('[data-city-color]');
    if (!b) return;
    state.cityColor = b.dataset.cityColor; save(); applyTheme(); renderSettings();
  });
  const setColor = c => { state.color = c; save(); applyTheme(); renderSettings(); };
  $('#swatches').addEventListener('click', e => { const b = e.target.closest('[data-color]'); b && setColor(b.dataset.color); });
  $('#custom-color').addEventListener('input', e => { state.color = e.target.value; applyTheme(); });
  $('#custom-color').addEventListener('change', e => setColor(e.target.value));
  $('#theme').addEventListener('click', e => {
    const b = e.target.closest('[data-theme]');
    if (!b) return;
    state.theme = b.dataset.theme; save(); applyTheme(); renderSettings();
  });
  $('#autorotate').addEventListener('change', e => { state.autoRotate = e.target.checked; save(); });
  $('#reset').onclick = async () => {
    if (!await ask('Retirer tous les pays visités ? Vos photos sont conservées.', 'Réinitialiser')) return;
    const codes = Object.keys(state.visited);
    state.visited = {};
    save();
    renderCounters();
    codes.forEach(updateVisitedUI);
    toast('Pays visités réinitialisés');
  };

  /* ---------- Export / import JSON ---------- */
  const toDataURL = blob => new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(r.result);
    r.onerror = () => rej(r.error);
    r.readAsDataURL(blob);
  });
  const busy = (on, label) => {
    ['#export', '#import'].forEach(s => { $(s).disabled = on; });
    if (label) toast(label);
  };

  $('#export').onclick = async () => {
    busy(true, 'Préparation de l\'export…');
    try {
      const stamp = new Date().toISOString().slice(0, 10);
      const name = `nomad-${stamp}.json`;
      const head = JSON.stringify({
        app: 'nomad', format: 1, exportedAt: new Date().toISOString(),
        settings: { color: state.color, theme: state.theme, autoRotate: state.autoRotate },
        visited: state.visited,
        years: state.years,
        cities: state.cities
      });
      const FS = Plugins.Filesystem, Share = Plugins.Share;
      const native = FS && Share && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform();
      const parts = [];
      const write = async (data, first) => {
        if (!native) return parts.push(data);
        const o = { path: name, data, directory: 'CACHE', encoding: 'utf8' };
        await (first ? FS.writeFile(o) : FS.appendFile(o));
      };
      await write(head.slice(0, -1) + ',"photos":[', true);
      const ids = await PhotoDB.keys();
      for (let i = 0; i < ids.length; i++) {
        const p = await PhotoDB.get(ids[i]);
        if (!p) continue;
        const item = { id: p.id, country: p.country, date: p.date, image: await photoDataURL(p) };
        await write((i ? ',' : '') + JSON.stringify(item));
      }
      await write(']}');
      if (native) {
        const { uri } = await FS.getUri({ path: name, directory: 'CACHE' });
        await Share.share({ title: 'Export Nomad', files: [uri], dialogTitle: 'Enregistrer l\'export Nomad' });
      } else {
        const a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob(parts, { type: 'application/json' }));
        a.download = name;
        a.click();
        setTimeout(() => URL.revokeObjectURL(a.href), 2000);
      }
      toast(`Export prêt · ${visitedCount()} pays, ${ids.length} photo${ids.length > 1 ? 's' : ''}`);
    } catch (e) {
      if (!/cancel/i.test(String(e && e.message))) toast('Échec de l\'export');
    } finally { busy(false); }
  };

  $('#import').onclick = () => $('#import-file').click();
  $('#import-file').addEventListener('change', async e => {
    const file = e.target.files[0];
    e.target.value = '';
    if (!file) return;
    let data;
    try {
      data = JSON.parse(await file.text());
      if (!data || data.app !== 'nomad' || typeof data.visited !== 'object') throw 0;
    } catch { return toast('Fichier invalide'); }
    const codes = Object.keys(data.visited).filter(known);
    const photos = Array.isArray(data.photos) ? data.photos.filter(p => p && p.image && known(p.country)) : [];
    if (!await ask(`Importer ${codes.length} pays et ${photos.length} photo${photos.length > 1 ? 's' : ''} ? Ils s'ajoutent à vos données actuelles.`, 'Importer')) return;
    busy(true, 'Import en cours…');
    let ok = 0;
    try {
      codes.forEach(c => { if (!state.visited[c]) state.visited[c] = Number(data.visited[c]) || Date.now(); });
      Object.entries(data.cities || {}).forEach(([c, list]) => {
        if (!known(c) || !Array.isArray(list)) return;
        const cur = state.cities[c] || (state.cities[c] = []);
        list.forEach(x => { if (x && x.n && Array.isArray(x.c) && !cur.some(y => y.n === x.n)) cur.push({ n: String(x.n), c: [Number(x.c[0]), Number(x.c[1])] }); });
      });
      Object.entries(data.years || {}).forEach(([c, y]) => { if (known(c) && !state.years[c] && Number(y) > 1900) state.years[c] = Number(y); });
      const s = data.settings || {};
      if (/^#[0-9a-f]{6}$/i.test(s.color)) state.color = s.color;
      if (['auto', 'light', 'dark'].includes(s.theme)) state.theme = s.theme;
      if (typeof s.autoRotate === 'boolean') state.autoRotate = s.autoRotate;
      save();
      for (const p of photos) {
        try {
          const blob = await (await fetch(p.image)).blob();
          await storePhoto(p.country, blob, Number(p.date) || Date.now(), String(p.id || newId()));
          ok++;
        } catch {}
      }
      photoCounts = await PhotoDB.counts().catch(() => photoCounts);
      applyTheme();
      renderSettings();
      renderCounters();
      renderPhotoCounts();
      [...COUNTRIES, ...SUBS].forEach(c => updateVisitedUI(c.c));
      toast(`Import terminé · ${codes.length} pays, ${ok} photo${ok > 1 ? 's' : ''}`);
    } catch { toast('Échec de l\'import'); }
    finally { busy(false); }
  });

  /* ---------- Bouton retour Android ---------- */
  try {
    Plugins.App && Plugins.App.addListener('backButton', () => {
      if ($('#confirm').classList.contains('show')) return closeConfirm(false);
      if ($('#onboard').classList.contains('show')) { if (!onboardFirst) closeOnboard(); return; }
      if ($('#years').classList.contains('show')) return yearResolve ? closeYears(undefined) : hideModal();
      if (story.open) return closeViewer();
      if ($('#citypick').classList.contains('show')) return closeCityPick();
      if (placing) return stopPlacing();
      if (finderOpen()) return closeFinder();
      if (sheet.open) return closeSheet();
      if (current !== 'map') return show('map');
      Plugins.App.exitApp();
    });
  } catch {}

  /* ---------- Pays d'origine + animation de lancement ---------- */
  /* ---------- Animation de lancement ---------- */
  const intro = (() => {
    const el = $('#intro'), canvas = $('#intro-globe'), ctx = canvas.getContext('2d');
    const proj = d3.geoOrthographic().clipAngle(90);
    const path = d3.geoPath(proj, ctx);
    const C = { ocean: '#141414', ocean2: '#262626', land: '#D9D9D9', line: '#141414' };
    const stars = d3.range(120).map(() => [Math.random(), Math.random(), 0.4 + Math.random() * 1.3, Math.random()]);
    let W, H, dpr, skip = false;
    const size = () => {
      const r = el.getBoundingClientRect();
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = r.width; H = r.height;
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
    };
    function draw(rot, f, alpha, hl, fast) {
      if (el.clientWidth !== W || el.clientHeight !== H) size();
      const X = W / 2, Y = H / 2 - 30, R = Math.min(W, H) * 0.24 * f;
      const G = fast ? LO : HI;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      ctx.globalAlpha = alpha;
      stars.forEach(([sx, sy, sr, ph]) => {
        const x = sx * W, y = sy * H;
        if (Math.hypot(x - X, y - Y) < R * 1.08) return;
        ctx.globalAlpha = alpha * (0.25 + 0.6 * Math.abs(Math.sin(ph * 6 + performance.now() / 900)));
        ctx.beginPath(); ctx.arc(x, y, sr, 0, Math.PI * 2); ctx.fillStyle = C.land; ctx.fill();
      });
      ctx.globalAlpha = alpha;
      proj.translate([X, Y]).scale(R).rotate(rot).precision(fast ? 1.6 : 0.5);
      const sea = ctx.createRadialGradient(X - R * 0.35, Y - R * 0.4, R * 0.1, X, Y, R);
      sea.addColorStop(0, C.ocean2); sea.addColorStop(1, C.ocean);
      ctx.beginPath(); ctx.arc(X, Y, R, 0, Math.PI * 2); ctx.fillStyle = sea; ctx.fill();
      ctx.beginPath(); path(G.land); ctx.fillStyle = C.land; ctx.fill();
      if (hl > 0) {
        ctx.beginPath(); (G.byC.get(state.home) || GEO.get(state.home).feats).forEach(f => path(f));
        ctx.fillStyle = d3.interpolateRgb(C.land, colors.accent)(hl); ctx.fill();
        if (GEO.get(state.home).small) {
          const p = proj(GEO.get(state.home).center);
          if (p) { ctx.beginPath(); ctx.arc(p[0], p[1], 2 + 3 * hl, 0, Math.PI * 2); ctx.fillStyle = colors.accent; ctx.fill(); }
        }
      }
      ctx.beginPath(); path(G.borders); ctx.strokeStyle = C.line; ctx.lineWidth = 0.5; ctx.stroke();
      ctx.beginPath(); ctx.arc(X, Y, R, 0, Math.PI * 2); ctx.lineWidth = 3; ctx.strokeStyle = C.ocean; ctx.stroke();
      ctx.globalAlpha = 1;
    }
    const wait = ms => new Promise(r => setTimeout(r, ms));
    const run = (dur, fn, ease) => new Promise(res => {
      const t0 = performance.now();
      const step = () => {
        const k = skip ? 1 : Math.min(1, (performance.now() - t0) / dur);
        fn(ease ? ease(k) : k, k);
        k < 1 ? requestAnimationFrame(step) : res();
      };
      requestAnimationFrame(step);
    });
    async function play() {
      const code = state.home, geo = GEO.get(code);
      skip = false;
      el.classList.add('show');
      document.body.classList.add('intro');
      size();
      globe.lookAt(code, geo.small ? 2.2 : 1);
      const target = [-geo.center[0], 0];
      const close = [target[0], Math.max(-70, Math.min(70, -geo.center[1]))];
      const from = [target[0] - 720, 0];
      const spin = d3.interpolate(from, target);
      const name = $('#intro-name');
      name.getAnimations().forEach(a => a.cancel());
      name.innerHTML = 'NOMAD'.split('').map((l, i) => `<span style="--i:${i}">${l}</span>`).join('');
      await run(1700, (e, k) => {
        const appear = d3.easeBackOut.overshoot(1.3)(Math.min(1, k / 0.25));
        draw(spin(e), Math.max(0.01, appear), Math.min(1, k / 0.15), Math.max(0, (k - 0.7) / 0.3), true);
      }, d3.easeCubicOut);
      const veil = $('#veil');
      $('#intro-name').animate([{ opacity: 1 }, { opacity: 0 }], { duration: 300, fill: 'forwards' });
      await run(900, (e, k) => {
        draw(d3.interpolate(target, close)(Math.min(1, e * 2.5)), 1 + 34 * e, 1, 1, true);
        veil.style.opacity = Math.max(0, (k - 0.35) / 0.65);
      }, d3.easeCubicIn);
      veil.style.opacity = 1;
      el.classList.remove('show');
      document.body.classList.remove('intro');
      globe.hold(3500);
      await wait(80);
      await veil.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 500, easing: 'ease-out', fill: 'forwards' }).finished;
      veil.getAnimations().forEach(a => a.cancel());
      veil.style.opacity = 0;
    }
    el.addEventListener('click', () => { skip = true; });
    return { play };
  })();
  const playIntro = () => intro.play();

  let onboardFirst = false;
  function openOnboard(first) {
    onboardFirst = first;
    $('#onboard-title').textContent = first ? 'Bienvenue sur Nomad' : "Pays d'origine";
    $('#onboard-close').hidden = first;
    const ul = $('#onboard-list');
    if (!ul.children.length) {
      ul.innerHTML = COUNTRIES.map(c =>
        `<li data-q="${searchKey(c)}"><button data-home="${c.c}"><img src="${flagSrc(c.c)}" alt="" loading="lazy"><span>${c.name}</span></button></li>`).join('');
    }
    $$('[data-home]', ul).forEach(b => b.classList.toggle('on', b.dataset.home === state.home));
    $('#onboard-search').value = '';
    $$('li', ul).forEach(li => { li.hidden = false; });
    $('#onboard').classList.add('show');
  }
  const closeOnboard = () => $('#onboard').classList.remove('show');
  $('#onboard-close').onclick = closeOnboard;
  $('#onboard-search').addEventListener('input', e => {
    const q = norm(e.target.value.trim());
    $$('#onboard-list li').forEach(li => { li.hidden = !!q && !li.dataset.q.includes(q); });
  });
  $('#onboard-list').addEventListener('click', e => {
    const b = e.target.closest('[data-home]');
    if (!b) return;
    const code = b.dataset.home;
    state.home = code;
    if (!isVisited(code)) state.visited[code] = Date.now();
    save();
    renderCounters();
    updateVisitedUI(code);
    renderSettings();
    closeOnboard();
    if (onboardFirst) setTimeout(playIntro, 350);
  });
  $('#home-row').onclick = () => openOnboard(false);

  /* ---------- Démarrage ---------- */
  state.mode = 'globe';
  applyTheme();
  renderSettings();
  renderCounters();
  syncMode();
  if (state.home && byCode.has(state.home)) playIntro(); else openOnboard(true);
  window.addEventListener('resize', () => {
    globe.resize();
    plan.invalidate(); mini.invalidate();
    if (current === 'map' && state.mode === 'plan') plan.init();
    if (current === 'passport') mini.init();
  });
  migratePhotos().catch(() => {}).then(() => PhotoDB.counts()).then(c => { photoCounts = c; renderPhotoCounts(); }).catch(() => {});
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
})();

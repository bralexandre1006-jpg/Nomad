(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const COUNTRIES = window.NOMAD_COUNTRIES;
  const TOTAL = COUNTRIES.length;
  const byCode = new Map(COUNTRIES.map(c => [c.c, c]));
  const byNum = new Map(COUNTRIES.map(c => [c.n, c]));
  const PRESETS = ['#E8704F', '#E3A33B', '#6FA387', '#2BA3A0', '#4C7BD9', '#8B6CD6', '#D9629A', '#D2453E'];
  const KEY = 'nomad.v1';
  const flagSrc = c => `flags/${c.toLowerCase()}.svg`;
  const CHECK = '<svg viewBox="0 0 24 24"><path d="m5 12 5 5 9-10"/></svg>';
  const Plugins = (window.Capacitor && window.Capacitor.Plugins) || {};

  const state = (() => {
    const d = { visited: {}, color: PRESETS[0], theme: 'dark', autoRotate: true, home: null };
    try { return { ...d, ...JSON.parse(localStorage.getItem(KEY) || '{}') }; } catch { return d; }
  })();
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch {} };
  const isVisited = c => !!state.visited[c];
  const visitedCount = () => Object.keys(state.visited).filter(c => byCode.has(c)).length;
  let photoCounts = {};

  /* ---------- Géographie ---------- */
  const topo = window.NOMAD_WORLD;
  const ALIASES = { PS: 'israel' };
  const searchKey = c => (c.name + ' ' + (ALIASES[c.c] || '')).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const geoms = topo.objects.countries.geometries;
  const merged = ['376', '275'];
  const features = topojson.feature(topo, { type: 'GeometryCollection', geometries: geoms.filter(g => !merged.includes(g.id)) }).features;
  features.push({ type: 'Feature', id: '275', properties: {}, geometry: topojson.merge(topo, geoms.filter(g => merged.includes(g.id))) });
  features.forEach(f => { f.country = byNum.get(f.id) || null; });
  if (!features.some(f => f.id === '798')) {
    features.push({ type: 'Feature', id: '798', country: byNum.get('798'), geometry: { type: 'Point', coordinates: [179.2, -8.52] } });
  }
  const mainPoint = f => {
    const g = f.geometry;
    if (g.type === 'Point') return g.coordinates;
    if (g.type === 'MultiPolygon') {
      let best = null, area = -1;
      g.coordinates.forEach(c => {
        const p = { type: 'Polygon', coordinates: c };
        const a = d3.geoArea(p);
        if (a > area) { area = a; best = p; }
      });
      return d3.geoCentroid(best);
    }
    return d3.geoCentroid(f);
  };
  features.forEach(f => {
    f.small = f.geometry.type === 'Point' || d3.geoArea(f) < 2.2e-4;
    f.center = mainPoint(f);
  });
  const countryFeatures = features.filter(f => f.country);
  const otherFeatures = features.filter(f => !f.country);
  const smallFeatures = countryFeatures.filter(f => f.small);
  const featByCode = new Map(countryFeatures.map(f => [f.country.c, f]));

  /* ---------- Thème ---------- */
  const colors = {};
  const media = matchMedia('(prefers-color-scheme: dark)');
  const inkFor = hex => {
    const n = parseInt(hex.slice(1), 16);
    const l = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
    return l > 0.68 ? '#1E2638' : '#FFFFFF';
  };
  function applyTheme() {
    const mode = state.theme === 'auto' ? (media.matches ? 'dark' : 'light') : state.theme;
    const root = document.documentElement;
    root.dataset.theme = mode;
    root.style.setProperty('--accent', state.color);
    root.style.setProperty('--accent-ink', inkFor(state.color));
    const cs = getComputedStyle(root);
    ['ocean', 'ocean-2', 'land', 'graticule', 'accent', 'bg'].forEach(k => { colors[k] = cs.getPropertyValue('--' + k).trim(); });
    colors.other = d3.interpolateRgb(colors.land, colors.ocean)(0.3);
    try { Plugins.StatusBar && Plugins.StatusBar.setStyle({ style: mode === 'dark' ? 'DARK' : 'LIGHT' }); } catch {}
    globe.dirty = true;
  }
  media.addEventListener('change', () => state.theme === 'auto' && applyTheme());

  /* ---------- UI commune ---------- */
  function renderCounters() {
    const n = visitedCount();
    const pct = Math.round(n / TOTAL * 100);
    $$('[data-counter]').forEach(el => {
      el.innerHTML = `<div class="num">${n} <small>/ ${TOTAL}</small></div>
        <div class="lbl">pays visités · ${pct}%</div><div class="bar"><i style="width:${pct}%"></i></div>`;
    });
    $$('[data-count]').forEach(el => { el.textContent = `${n}/${TOTAL}`; });
    $$('[data-counter-text]').forEach(el => { el.textContent = `${n} pays visités sur ${TOTAL}`; });
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

  function setVisited(code, on) {
    const c = byCode.get(code);
    if (on) state.visited[code] = Date.now(); else delete state.visited[code];
    save();
    renderCounters();
    updateVisitedUI(code);
    if (on) toast(`${c.name} débloqué · ${visitedCount()}/${TOTAL}`);
  }

  function updateVisitedUI(code) {
    const on = isVisited(code);
    $$(`[data-c="${code}"]`).forEach(el => el.classList.toggle('visited', on));
    $$(`.check[data-toggle="${code}"]`).forEach(el => el.classList.toggle('on', on));
    globe.dirty = true;
    if (sheet.code === code) renderSheetHead();
    applyListFilter();
    applyFlagFilter();
  }

  /* ---------- Navigation ---------- */
  let current = 'globe';
  function show(view) {
    if (view === current) return;
    current = view;
    $$('.view').forEach(v => v.classList.toggle('active', v.dataset.view === view));
    $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === view));
    if (view === 'map') map.init();
    if (view === 'flags') buildFlags();
    if (view === 'list') buildList();
    globe.active = view === 'globe';
    if (globe.active) { globe.resize(); globe.start(); }
  }
  $$('.tab').forEach(t => t.addEventListener('click', () => show(t.dataset.tab)));

  /* ---------- Globe ---------- */
  const globe = (() => {
    const canvas = $('#globe');
    const ctx = canvas.getContext('2d');
    const proj = d3.geoOrthographic().clipAngle(90).precision(0.6);
    const path = d3.geoPath(proj, ctx);
    const grat = d3.geoGraticule10();
    const g = { dirty: true, active: true, running: false, selected: null };
    let W = 0, H = 0, dpr = 1, base = 1, zoom = 1;
    const rot = [-8, -38, 0];
    let vel = [0, 0], lastInteract = 0, anim = null;

    g.resize = () => {
      const r = canvas.getBoundingClientRect();
      if (!r.width) return;
      dpr = Math.min(window.devicePixelRatio || 1, 3);
      W = r.width; H = r.height;
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
      base = Math.min(W, H - 170) / 2 * 0.88;
      g.dirty = true;
    };
    const cy = () => H / 2 + 34;
    const radius = () => base * zoom;

    function draw() {
      proj.translate([W / 2, cy()]).scale(radius()).rotate(rot);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      const R = radius(), X = W / 2, Y = cy();

      const halo = ctx.createRadialGradient(X, Y, R * 0.9, X, Y, R * 1.18);
      halo.addColorStop(0, colors.accent + '33');
      halo.addColorStop(1, colors.accent + '00');
      ctx.fillStyle = halo;
      ctx.beginPath(); ctx.arc(X, Y, R * 1.18, 0, Math.PI * 2); ctx.fill();

      const sea = ctx.createRadialGradient(X - R * 0.35, Y - R * 0.4, R * 0.1, X, Y, R);
      sea.addColorStop(0, colors['ocean-2']);
      sea.addColorStop(1, colors.ocean);
      ctx.beginPath(); path({ type: 'Sphere' }); ctx.fillStyle = sea; ctx.fill();

      ctx.beginPath(); path(grat); ctx.strokeStyle = colors.graticule; ctx.lineWidth = 0.7; ctx.stroke();

      ctx.lineWidth = 0.6; ctx.strokeStyle = colors.ocean; ctx.lineJoin = 'round';
      ctx.beginPath(); otherFeatures.forEach(f => path(f)); ctx.fillStyle = colors.other; ctx.fill(); ctx.stroke();
      ctx.beginPath(); countryFeatures.forEach(f => !isVisited(f.country.c) && f.geometry.type !== 'Point' && path(f));
      ctx.fillStyle = colors.land; ctx.fill(); ctx.stroke();
      ctx.beginPath(); countryFeatures.forEach(f => isVisited(f.country.c) && f.geometry.type !== 'Point' && path(f));
      ctx.fillStyle = colors.accent; ctx.fill(); ctx.stroke();

      const c = proj.invert([X, Y]);
      smallFeatures.forEach(f => {
        if (d3.geoDistance(f.center, c) > Math.PI / 2 - 0.02) return;
        const [x, y] = proj(f.center);
        const v = isVisited(f.country.c);
        ctx.beginPath(); ctx.arc(x, y, v ? 3.6 : 2.2, 0, Math.PI * 2);
        ctx.fillStyle = v ? colors.accent : colors.land; ctx.fill();
        ctx.lineWidth = 1; ctx.strokeStyle = colors.ocean; ctx.stroke();
      });

      if (g.selected) {
        const f = featByCode.get(g.selected);
        if (f) {
          ctx.lineWidth = 2.2; ctx.strokeStyle = isVisited(g.selected) ? colors.bg : colors.accent;
          if (f.small) {
            if (d3.geoDistance(f.center, c) < Math.PI / 2) {
              const [x, y] = proj(f.center);
              ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.stroke();
            }
          } else { ctx.beginPath(); path(f); ctx.stroke(); }
        }
      }

      if (g.glow > 0 && g.glowCode) {
        const f = featByCode.get(g.glowCode);
        if (f && d3.geoDistance(f.center, c) < Math.PI / 2) {
          ctx.save();
          ctx.globalAlpha = Math.min(1, g.glow * 1.3);
          ctx.shadowColor = 'rgba(255, 214, 64, .95)';
          ctx.shadowBlur = 12 + 60 * g.glow;
          ctx.fillStyle = d3.interpolateRgb('#FFC93C', '#FFF6C8')(g.glow);
          ctx.beginPath();
          if (f.small) { const [x, y] = proj(f.center); ctx.arc(x, y, 5 + 12 * g.glow, 0, Math.PI * 2); } else path(f);
          ctx.fill(); ctx.fill();
          ctx.restore();
        }
      }

      ctx.beginPath(); path({ type: 'Sphere' });
      ctx.lineWidth = 1; ctx.strokeStyle = 'rgba(255,255,255,.08)'; ctx.stroke();
    }

    let prev = 0;
    function frame(t) {
      if (!g.active || document.hidden) { g.running = false; return; }
      const dt = prev ? Math.min(t - prev, 50) : 16;
      prev = t;
      if (anim) {
        const k = Math.min(1, (t - anim.t0) / anim.dur);
        const e = (anim.ease || d3.easeCubicInOut)(k);
        const r = anim.interp(e);
        rot[0] = r[0]; rot[1] = r[1];
        zoom = anim.z0 + (anim.z1 - anim.z0) * e;
        if (k >= 1) { const done = anim.done; anim = null; done && done(); }
        g.dirty = true;
      } else if (!pointers.size) {
        if (Math.abs(vel[0]) > 0.01 || Math.abs(vel[1]) > 0.01) {
          rot[0] += vel[0] * dt; rot[1] = clampLat(rot[1] + vel[1] * dt);
          const f = Math.pow(0.94, dt / 16);
          vel = [vel[0] * f, vel[1] * f];
          g.dirty = true;
        } else if (state.autoRotate && !sheet.open && t - lastInteract > 2500) {
          rot[0] += 0.006 * dt;
          g.dirty = true;
        }
      }
      if (g.dirty) { g.dirty = false; draw(); }
      requestAnimationFrame(frame);
    }
    g.start = () => {
      if (g.running) return;
      g.running = true; prev = 0;
      requestAnimationFrame(frame);
    };
    document.addEventListener('visibilitychange', () => !document.hidden && g.active && g.start());

    const clampLat = v => Math.max(-85, Math.min(85, v));
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
      g.dirty = true;
    }, { passive: false });

    function pick(x, y) {
      const X = W / 2, Y = cy();
      if (Math.hypot(x - X, y - Y) > radius()) return;
      proj.translate([X, Y]).scale(radius()).rotate(rot);
      const c = proj.invert([X, Y]);
      let best = null, bd = 18;
      smallFeatures.forEach(f => {
        if (d3.geoDistance(f.center, c) > Math.PI / 2) return;
        const [px, py] = proj(f.center);
        const d = Math.hypot(px - x, py - y);
        if (d < bd) { bd = d; best = f; }
      });
      if (!best) {
        const ll = proj.invert([x, y]);
        best = countryFeatures.find(f => f.geometry.type !== 'Point' && d3.geoContains(f, ll));
      }
      if (best) {
        if (navigator.vibrate) navigator.vibrate(8);
        openSheet(best.country.c);
      }
    }

    g.focus = (code, zoomIn) => {
      const f = featByCode.get(code);
      g.selected = code;
      g.dirty = true;
      if (!f) return;
      const target = [-f.center[0], clampLat(-f.center[1])];
      const from = [rot[0], rot[1]];
      target[0] = from[0] + ((((target[0] - from[0]) % 360) + 540) % 360 - 180);
      anim = { t0: performance.now(), dur: 900, interp: d3.interpolate(from, target), z0: zoom, z1: zoomIn ? Math.max(zoom, f.small ? 3 : 1.6) : zoom };
    };
    g.intro = code => new Promise(resolve => {
      const f = featByCode.get(code);
      if (!f) return resolve();
      const target = [-f.center[0], clampLat(-f.center[1])];
      rot[0] = target[0] - 600; rot[1] = clampLat(target[1] + 35);
      zoom = 0.08; vel = [0, 0]; g.glow = 0; g.glowCode = code; g.dirty = true;
      g.intro.skip = () => { if (anim) anim.t0 = -1e9; };
      anim = {
        t0: performance.now(), dur: 3000, ease: d3.easeCubicOut,
        interp: d3.interpolate([rot[0], rot[1]], target), z0: zoom, z1: f.small ? 3.4 : 1.7,
        done: resolve
      };
    });
    g.setGlow = v => { g.glow = v; g.dirty = true; };
    g.screenPoint = code => {
      const f = featByCode.get(code);
      if (!f) return [W / 2, cy()];
      proj.translate([W / 2, cy()]).scale(radius()).rotate(rot);
      return proj(f.center);
    };
    g.hold = ms => { lastInteract = performance.now() + ms; };
    g.reset = () => {
      anim = { t0: performance.now(), dur: 700, interp: d3.interpolate([rot[0], rot[1]], [rot[0], -20]), z0: zoom, z1: 1 };
    };
    return g;
  })();
  $('#recenter').onclick = () => globe.reset();

  /* ---------- Carte ---------- */
  const map = (() => {
    const svg = d3.select('#map');
    const m = { ready: false };
    let zoomer, root, size = '';
    m.init = () => {
      const r = svg.node().getBoundingClientRect();
      const key = `${Math.round(r.width)}x${Math.round(r.height)}`;
      if (!r.width || (m.ready && key === size)) return;
      size = key;
      svg.selectAll('*').remove();
      const W = r.width, H = r.height;
      svg.attr('viewBox', `0 0 ${W} ${H}`);
      const proj = d3.geoNaturalEarth1().fitSize([W, H], { type: 'Sphere' });
      const path = d3.geoPath(proj);
      root = svg.append('g');
      root.append('path').attr('class', 'sphere').attr('d', path({ type: 'Sphere' }));
      root.append('path').attr('class', 'grat').attr('d', path(d3.geoGraticule10()));
      root.append('g').selectAll('path').data(otherFeatures).join('path').attr('class', 'land other').attr('d', path);
      root.append('g').selectAll('path').data(countryFeatures.filter(f => f.geometry.type !== 'Point')).join('path')
        .attr('class', f => 'land' + (isVisited(f.country.c) ? ' visited' : ''))
        .attr('data-c', f => f.country.c).attr('d', path);
      const dots = root.append('g').selectAll('circle').data(smallFeatures).join('circle')
        .attr('class', f => 'dot' + (isVisited(f.country.c) ? ' visited' : ''))
        .attr('data-c', f => f.country.c)
        .attr('cx', f => proj(f.center)[0]).attr('cy', f => proj(f.center)[1]).attr('r', 2.4);
      const b = path.bounds({ type: 'Sphere' });
      const mapH = b[1][1] - b[0][1];
      const k0 = Math.max(1, Math.min(3, (H * 0.55) / mapH));
      zoomer = d3.zoom().scaleExtent([1, 60])
        .translateExtent([[b[0][0], b[0][1] - H * 0.4], [b[1][0], b[1][1] + H * 0.4]])
        .on('zoom', e => {
          root.attr('transform', e.transform);
          dots.attr('r', 2.6 / Math.sqrt(e.transform.k));
        });
      svg.call(zoomer).on('dblclick.zoom', null);
      const [ex, ey] = proj([10, 30]);
      m.home = d3.zoomIdentity.translate(W / 2 - ex * k0, H / 2 + 20 - ey * k0).scale(k0);
      svg.call(zoomer.transform, m.home);
      m.ready = true;
      m.select(globe.selected);
    };
    svg.on('click', e => {
      const el = e.target.closest('[data-c]');
      if (el) { if (navigator.vibrate) navigator.vibrate(8); openSheet(el.dataset.c); }
    });
    m.select = code => {
      if (!m.ready) return;
      svg.selectAll('.sel').classed('sel', false);
      if (code) svg.selectAll(`[data-c="${code}"]`).classed('sel', true).raise();
    };
    m.reset = () => m.ready && svg.transition().duration(600).call(zoomer.transform, m.home);
    return m;
  })();
  $('#map-reset').onclick = () => map.reset();

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

  /* ---------- Liste ---------- */
  const norm = s => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  let listBuilt = false;
  function buildList() {
    if (listBuilt) return;
    listBuilt = true;
    const ul = $('#country-list');
    ul.innerHTML = COUNTRIES.map(c =>
      `<li data-row="${c.c}" data-q="${searchKey(c)}"><button class="open" data-open="${c.c}"><img src="${flagSrc(c.c)}" alt="" loading="lazy"><div><div class="nm">${c.name}</div><div class="ph" data-ph="${c.c}"></div></div></button><button class="check${isVisited(c.c) ? ' on' : ''}" data-toggle="${c.c}" aria-label="Visité">${CHECK}</button></li>`).join('')
      + '<li class="empty" hidden>Aucun pays trouvé</li>';
    ul.addEventListener('click', e => {
      const t = e.target.closest('[data-toggle]');
      if (t) { if (navigator.vibrate) navigator.vibrate(8); return setVisited(t.dataset.toggle, !isVisited(t.dataset.toggle)); }
      const o = e.target.closest('[data-open]');
      if (o) openSheet(o.dataset.open);
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
  }

  /* ---------- Fiche pays + album ---------- */
  const sheet = { code: null, open: false, photos: [], urls: [] };
  const sheetEl = $('#sheet');

  function renderSheetHead() {
    const c = byCode.get(sheet.code);
    const v = isVisited(c.c);
    const n = sheet.photos.length;
    $('#sheet-flag').src = flagSrc(c.c);
    $('#sheet-name').textContent = c.name;
    $('#sheet-sub').textContent = v
      ? `Visité${state.visited[c.c] > 1 ? ' · ajouté le ' + new Date(state.visited[c.c]).toLocaleDateString('fr-FR') : ''}`
      : 'Pas encore visité';
    const btn = $('#visit-btn');
    btn.classList.toggle('on', v);
    btn.innerHTML = v ? `${CHECK}<span>Visité</span>` : '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg><span>Marquer comme visité</span>';
    $('#album-count').textContent = n ? `${n} photo${n > 1 ? 's' : ''}` : '';
  }

  function revokeUrls() { sheet.urls.forEach(u => URL.revokeObjectURL(u)); sheet.urls = []; }

  async function loadAlbum() {
    const code = sheet.code;
    let photos = [];
    try { photos = await PhotoDB.byCountry(code); } catch {}
    if (code !== sheet.code) return;
    sheet.photos = photos;
    revokeUrls();
    const album = $('#album');
    album.innerHTML = '<button class="add" id="add-photo"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>Ajouter</button>';
    photos.forEach((p, i) => {
      const u = URL.createObjectURL(p.thumb || p.blob);
      sheet.urls.push(u);
      const img = document.createElement('img');
      img.src = u; img.alt = ''; img.dataset.i = i; img.decoding = 'async';
      album.appendChild(img);
    });
    renderSheetHead();
  }

  function openSheet(code) {
    if (!byCode.has(code)) return;
    sheet.code = code;
    sheet.photos = [];
    sheet.open = true;
    $('#album').innerHTML = '';
    renderSheetHead();
    sheetEl.style.transform = '';
    sheetEl.classList.add('show');
    $('#scrim').classList.add('show');
    globe.focus(code, true);
    map.select(code);
    loadAlbum();
  }
  function closeSheet() {
    if (!sheet.open) return;
    sheet.open = false;
    sheetEl.classList.remove('show');
    sheetEl.style.transform = '';
    $('#scrim').classList.remove('show');
    globe.selected = null; globe.dirty = true;
    map.select(null);
    setTimeout(() => { if (!sheet.open) { revokeUrls(); $('#album').innerHTML = ''; } }, 400);
  }
  $('#scrim').onclick = closeSheet;
  $('#sheet-close').onclick = closeSheet;
  $('#visit-btn').onclick = () => {
    if (navigator.vibrate) navigator.vibrate(12);
    setVisited(sheet.code, !isVisited(sheet.code));
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

  $('#album').addEventListener('click', e => {
    if (e.target.closest('#add-photo')) return $('#file').click();
    const img = e.target.closest('img[data-i]');
    if (img) openViewer(+img.dataset.i);
  });

  async function resize(file, max, q) {
    let src;
    try { src = await createImageBitmap(file, { imageOrientation: 'from-image' }); }
    catch {
      src = await new Promise((res, rej) => {
        const i = new Image(), u = URL.createObjectURL(file);
        i.onload = () => { URL.revokeObjectURL(u); res(i); };
        i.onerror = () => { URL.revokeObjectURL(u); rej(new Error('image')); };
        i.src = u;
      });
    }
    const w = src.width, h = src.height, s = Math.min(1, max / Math.max(w, h));
    const cv = document.createElement('canvas');
    cv.width = Math.round(w * s); cv.height = Math.round(h * s);
    cv.getContext('2d').drawImage(src, 0, 0, cv.width, cv.height);
    if (src.close) src.close();
    return new Promise(res => cv.toBlob(res, 'image/jpeg', q));
  }

  $('#file').addEventListener('change', async e => {
    const files = [...e.target.files];
    e.target.value = '';
    if (!files.length) return;
    const code = sheet.code;
    const album = $('#album');
    const ph = files.map(() => { const d = document.createElement('div'); d.className = 'loading'; album.appendChild(d); return d; });
    let ok = 0;
    for (const f of files) {
      try {
        const [blob, thumb] = await Promise.all([resize(f, 2400, 0.88), resize(f, 480, 0.8)]);
        await PhotoDB.add({ id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, country: code, blob, thumb, date: Date.now() });
        ok++;
      } catch {}
    }
    ph.forEach(d => d.remove());
    photoCounts[code] = (photoCounts[code] || 0) + ok;
    renderPhotoCounts();
    if (ok < files.length) toast(`${files.length - ok} photo(s) n'ont pas pu être ajoutées`);
    if (ok && !isVisited(code)) setVisited(code, true);
    else if (ok) toast(`${ok} photo${ok > 1 ? 's' : ''} ajoutée${ok > 1 ? 's' : ''}`);
    if (sheet.code === code && sheet.open) loadAlbum();
  });

  /* ---------- Visionneuse ---------- */
  const viewer = { open: false, i: 0, urls: [] };
  const track = $('#viewer-track');
  function openViewer(i) {
    viewer.open = true;
    viewer.i = i;
    track.innerHTML = sheet.photos.map(() => '<div><img alt=""></div>').join('');
    $('#viewer').classList.add('show');
    $('#viewer').setAttribute('aria-hidden', 'false');
    goTo(i, false);
  }
  function ensureImg(i) {
    const slide = track.children[i];
    if (!slide) return;
    const img = slide.firstChild;
    if (!img.src) {
      const u = URL.createObjectURL(sheet.photos[i].blob);
      viewer.urls.push(u);
      img.src = u;
    }
  }
  function goTo(i, animate = true) {
    const n = sheet.photos.length;
    viewer.i = Math.max(0, Math.min(n - 1, i));
    [viewer.i - 1, viewer.i, viewer.i + 1].forEach(ensureImg);
    track.style.transition = animate ? 'transform .3s cubic-bezier(.2,.8,.2,1)' : 'none';
    track.style.transform = `translateX(${-viewer.i * window.innerWidth}px)`;
    $('#viewer-index').textContent = `${viewer.i + 1} / ${n}`;
  }
  function closeViewer() {
    viewer.open = false;
    $('#viewer').classList.remove('show');
    $('#viewer').setAttribute('aria-hidden', 'true');
    setTimeout(() => { if (!viewer.open) { viewer.urls.forEach(u => URL.revokeObjectURL(u)); viewer.urls = []; track.innerHTML = ''; } }, 300);
  }
  $('#viewer-close').onclick = closeViewer;
  $('#viewer-delete').onclick = async () => {
    if (!await ask('Supprimer cette photo de l\'album ?', 'Supprimer')) return;
    const p = sheet.photos[viewer.i];
    try { await PhotoDB.remove(p.id); } catch { return toast('Suppression impossible'); }
    photoCounts[sheet.code] = Math.max(0, (photoCounts[sheet.code] || 1) - 1);
    renderPhotoCounts();
    const i = viewer.i;
    closeViewer();
    await loadAlbum();
    if (sheet.photos.length) setTimeout(() => openViewer(Math.min(i, sheet.photos.length - 1)), 320);
  };
  (() => {
    let x0 = null, dx = 0, multi = 0;
    track.addEventListener('pointerdown', e => { multi++; if (multi > 1) return; x0 = e.clientX; dx = 0; track.style.transition = 'none'; });
    track.addEventListener('pointermove', e => {
      if (x0 === null || multi > 1) return;
      dx = e.clientX - x0;
      const edge = (viewer.i === 0 && dx > 0) || (viewer.i === sheet.photos.length - 1 && dx < 0);
      track.style.transform = `translateX(${-viewer.i * window.innerWidth + dx * (edge ? 0.35 : 1)}px)`;
    });
    const end = () => {
      multi = Math.max(0, multi - 1);
      if (x0 === null) return;
      x0 = null;
      const w = window.innerWidth;
      if (Math.abs(dx) < 8) return goTo(viewer.i);
      goTo(dx < -w * 0.18 ? viewer.i + 1 : dx > w * 0.18 ? viewer.i - 1 : viewer.i);
    };
    track.addEventListener('pointerup', end);
    track.addEventListener('pointercancel', end);
  })();

  /* ---------- Réglages ---------- */
  function renderSettings() {
    $('#swatches').innerHTML = PRESETS.map(c =>
      `<button class="swatch${c.toLowerCase() === state.color.toLowerCase() ? ' active' : ''}" style="background:${c}" data-color="${c}" aria-label="Couleur ${c}"></button>`).join('');
    $('#custom-color').value = state.color;
    $$('#theme button').forEach(b => b.classList.toggle('active', b.dataset.theme === state.theme));
    $('#autorotate').checked = state.autoRotate;
    const h = byCode.get(state.home);
    $('#home-value').textContent = h ? h.name : 'Choisir';
  }
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
        visited: state.visited
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
        const item = { id: p.id, country: p.country, date: p.date, image: await toDataURL(p.blob), thumb: p.thumb ? await toDataURL(p.thumb) : null };
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
    const codes = Object.keys(data.visited).filter(c => byCode.has(c));
    const photos = Array.isArray(data.photos) ? data.photos.filter(p => p && p.image && byCode.has(p.country)) : [];
    if (!await ask(`Importer ${codes.length} pays et ${photos.length} photo${photos.length > 1 ? 's' : ''} ? Ils s'ajoutent à vos données actuelles.`, 'Importer')) return;
    busy(true, 'Import en cours…');
    let ok = 0;
    try {
      codes.forEach(c => { if (!state.visited[c]) state.visited[c] = Number(data.visited[c]) || Date.now(); });
      const s = data.settings || {};
      if (/^#[0-9a-f]{6}$/i.test(s.color)) state.color = s.color;
      if (['auto', 'light', 'dark'].includes(s.theme)) state.theme = s.theme;
      if (typeof s.autoRotate === 'boolean') state.autoRotate = s.autoRotate;
      save();
      for (const p of photos) {
        try {
          const blob = await (await fetch(p.image)).blob();
          const thumb = p.thumb ? await (await fetch(p.thumb)).blob() : await resize(blob, 480, 0.8);
          await PhotoDB.add({ id: String(p.id || `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`), country: p.country, blob, thumb, date: Number(p.date) || Date.now() });
          ok++;
        } catch {}
      }
      photoCounts = await PhotoDB.counts().catch(() => photoCounts);
      applyTheme();
      renderSettings();
      renderCounters();
      renderPhotoCounts();
      COUNTRIES.forEach(c => updateVisitedUI(c.c));
      toast(`Import terminé · ${codes.length} pays, ${ok} photo${ok > 1 ? 's' : ''}`);
    } catch { toast('Échec de l\'import'); }
    finally { busy(false); }
  });

  /* ---------- Bouton retour Android ---------- */
  try {
    Plugins.App && Plugins.App.addListener('backButton', () => {
      if ($('#confirm').classList.contains('show')) return closeConfirm(false);
      if ($('#onboard').classList.contains('show')) { if (!onboardFirst) closeOnboard(); return; }
      if (viewer.open) return closeViewer();
      if (sheet.open) return closeSheet();
      if (current !== 'globe') return show('globe');
      Plugins.App.exitApp();
    });
  } catch {}

  /* ---------- Pays d'origine + animation de lancement ---------- */
  const tween = (dur, fn, ease = d3.easeCubicInOut) => new Promise(res => {
    const t0 = performance.now();
    const step = t => {
      const k = Math.max(0, Math.min(1, (performance.now() - t0) / dur));
      fn(ease(k));
      k < 1 ? requestAnimationFrame(step) : res();
    };
    requestAnimationFrame(step);
  });
  let introSkipped = false;
  async function playIntro() {
    const code = state.home;
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) { globe.focus(code, true); return; }
    introSkipped = false;
    document.body.classList.add('intro');
    await globe.intro(code);
    const flash = $('#flash'), burst = $('#flash-burst');
    if (!introSkipped) await tween(700, k => globe.setGlow(k));
    else globe.setGlow(1);
    const [x, y] = globe.screenPoint(code);
    burst.style.left = x + 'px'; burst.style.top = y + 'px';
    if (navigator.vibrate) navigator.vibrate(30);
    const fast = introSkipped ? 0.5 : 1;
    await Promise.all([
      burst.animate([{ transform: 'translate(-50%,-50%) scale(0)', opacity: 0 }, { transform: 'translate(-50%,-50%) scale(1)', opacity: 1 }],
        { duration: 520 * fast, easing: 'cubic-bezier(.3,0,.2,1)', fill: 'forwards' }).finished,
      flash.animate([{ opacity: 0 }, { opacity: 0, offset: 0.35 }, { opacity: 1 }],
        { duration: 560 * fast, easing: 'ease-in', fill: 'forwards' }).finished
    ]);
    globe.setGlow(0);
    globe.glowCode = null;
    globe.hold(4000);
    document.body.classList.remove('intro');
    await Promise.all([
      flash.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 900 * fast, easing: 'ease-out', fill: 'forwards' }).finished,
      burst.animate([{ opacity: 1, transform: 'translate(-50%,-50%) scale(1)' }, { opacity: 0, transform: 'translate(-50%,-50%) scale(1.2)' }],
        { duration: 900 * fast, easing: 'ease-out', fill: 'forwards' }).finished
    ]);
  }
  $('#intro-skip').addEventListener('click', () => { introSkipped = true; globe.intro.skip && globe.intro.skip(); });

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
  applyTheme();
  renderSettings();
  renderCounters();
  globe.resize();
  globe.start();
  if (state.home && byCode.has(state.home)) playIntro(); else openOnboard(true);
  window.addEventListener('resize', () => {
    globe.resize();
    if (current === 'map') map.init(); else map.ready && (map.ready = false);
    if (viewer.open) goTo(viewer.i, false);
  });
  PhotoDB.counts().then(c => { photoCounts = c; renderPhotoCounts(); }).catch(() => {});
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
})();

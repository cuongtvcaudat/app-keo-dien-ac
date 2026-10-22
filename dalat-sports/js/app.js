/* DALAT SPORTS HUB — SPA thuần JS, lưu localStorage, chạy offline (PWA).
   Giai đoạn 1: dữ liệu cục bộ. Giai đoạn 2: thay Store bằng API (Google Sheets/GAS hoặc Supabase). */
(() => {
  'use strict';
  const YEAR = 2026;
  const KEY = 'dalat-sports-hub-v1';
  const SPORT = Object.fromEntries(SPORTS.map(s => [s.code, s]));

  // ---------- Store ----------
  const Store = {
    db: null,
    load() {
      try { this.db = JSON.parse(localStorage.getItem(KEY)); } catch { this.db = null; }
      if (!this.db || !Array.isArray(this.db.people)) this.reset();
    },
    reset() {
      this.db = { people: structuredClone(SEED_PEOPLE), tournaments: [], results: [], updated: new Date().toISOString() };
      this.save();
    },
    save() {
      this.db.updated = new Date().toISOString();
      try { localStorage.setItem(KEY, JSON.stringify(this.db)); } catch { toast('Không lưu được vào bộ nhớ trình duyệt'); }
    },
    get people() { return this.db.people; },
    athletes() { return this.db.people.filter(p => p.role === 'VĐV'); },
    coaches() { return this.db.people.filter(p => p.role === 'HLV'); },
    person(id) { return this.db.people.find(p => p.id === id); },
    tournament(id) { return this.db.tournaments.find(t => t.id === id); },
    nextId(role, code) {
      const prefix = `${role === 'HLV' ? 'H' : 'V'}-${code}-`;
      const max = this.db.people.filter(p => p.id.startsWith(prefix)).reduce((m, p) => Math.max(m, +p.id.slice(prefix.length) || 0), 0);
      return prefix + String(max + 1).padStart(3, '0');
    },
    uid(prefix) { return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 6); },
  };

  // ---------- Helpers ----------
  const $ = (s, el = document) => el.querySelector(s);
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fold = s => String(s ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();
  const age = p => (p.year ? YEAR - p.year : null);
  const initials = n => n.trim().split(/\s+/).slice(-2).map(w => w[0]).join('').toUpperCase();
  const tierTag = t => `<span class="tag ${t === 'Tuyển' ? 'tuyen' : t === 'Trẻ' ? 'tre' : 'nk'}">${esc(t)}</span>`;
  const sico = (code, size = '') => {
    const s = SPORT[code] || { icon: '🏅', color: '#0B4D3B' };
    return `<span class="sico ${size}" style="background:${s.color}" aria-hidden="true">${s.icon}</span>`;
  };
  const avatar = (p, size = '') => `<span class="avatar ${p.gender === 'Nữ' ? 'f' : ''} ${size}">${esc(initials(p.name))}</span>`;
  const count = (arr, f) => arr.reduce((m, x) => { const k = f(x); m[k] = (m[k] || 0) + 1; return m; }, {});
  const medalOf = r => MEDALS.find(m => m.key === r.medal) || MEDALS[3];
  const medalBadge = r => { const m = medalOf(r); return m.cls ? `<span class="medal ${m.cls}" title="${m.label}">${m.label[0]}</span>` : '<span class="tag">KHC</span>'; };
  function toast(msg) {
    const t = document.createElement('div'); t.className = 'toast'; t.textContent = msg;
    document.body.appendChild(t); setTimeout(() => t.remove(), 2400);
  }
  function tally(results) {
    const t = { HCV: 0, HCB: 0, HCĐ: 0 };
    results.forEach(r => { if (r.medal in t) t[r.medal]++; });
    return t;
  }

  // ---------- Kiểm tra dữ liệu ----------
  function issues() {
    const list = [];
    const A = Store.athletes(), C = Store.coaches();
    A.filter(p => !p.year).forEach(p => list.push({ lvl: 'err', type: 'Thiếu năm sinh', p, msg: `${p.team} · ${p.tier}` }));
    A.filter(p => p.year && p.tier === 'Năng khiếu' && age(p) > 18).forEach(p => list.push({ lvl: 'warn', type: 'Tuổi lệch tuyến', p, msg: `${age(p)} tuổi nhưng ở tuyến Năng khiếu` }));
    A.filter(p => p.year && p.tier === 'Trẻ' && age(p) > 25).forEach(p => list.push({ lvl: 'err', type: 'Tuổi lệch tuyến', p, msg: `${age(p)} tuổi (sinh ${p.year}) nhưng ở tuyến Trẻ — nghi nhập sai năm sinh` }));
    A.filter(p => p.year && age(p) < 9).forEach(p => list.push({ lvl: 'warn', type: 'Tuổi quá nhỏ', p, msg: `${age(p)} tuổi (sinh ${p.year}) — xác minh lại` }));
    A.filter(p => p.year && p.tier === 'Tuyển' && age(p) > 45).forEach(p => list.push({ lvl: 'warn', type: 'Tuổi cao', p, msg: `${age(p)} tuổi ở đội Tuyển — xác minh` }));
    const byName = {};
    Store.people.forEach(p => (byName[fold(p.name)] ||= []).push(p));
    Object.values(byName).filter(g => g.length > 1).forEach(g => list.push({ lvl: 'info', type: 'Trùng họ tên', p: g[0], group: g, msg: g.map(x => `${x.role} ${x.team} (${x.year || '?'})`).join(' · ') }));
    // Thiếu HLV cho tuyến có VĐV
    SPORTS.forEach(s => TIERS.forEach(t => {
      const a = A.filter(p => p.code === s.code && p.tier === t).length;
      const c = C.filter(p => p.code === s.code && p.tier === t).length;
      if (a > 0 && c === 0) list.push({ lvl: 'warn', type: 'Thiếu HLV theo tuyến', sport: s, msg: `${s.name} – tuyến ${t}: ${a} VĐV nhưng 0 HLV` });
    }));
    SPORTS.forEach(s => {
      const a = A.filter(p => p.code === s.code).length, c = C.filter(p => p.code === s.code).length;
      if (c && a / c > 15) list.push({ lvl: 'warn', type: 'Tỉ lệ VĐV/HLV cao', sport: s, msg: `${s.name}: ${a} VĐV / ${c} HLV = ${(a / c).toFixed(1)} VĐV/HLV` });
    });
    const noContract = A.filter(p => !p.contract).length;
    if (noContract) list.push({ lvl: 'info', type: 'Chưa có hợp đồng', msg: `${noContract}/${A.length} VĐV để trống cột HỢP ĐỒNG` });
    return list;
  }

  // ---------- Router ----------
  const routes = [
    [/^#?\/?$/, viewDashboard, 'Tổng quan'],
    [/^#\/vdv$/, () => viewPeople('VĐV'), 'Vận động viên'],
    [/^#\/hlv$/, () => viewPeople('HLV'), 'Huấn luyện viên'],
    [/^#\/p\/(.+)$/, m => viewProfile(decodeURIComponent(m[1])), 'Hồ sơ'],
    [/^#\/mon$/, viewSports, 'Bộ môn'],
    [/^#\/mon\/(.+)$/, m => viewSport(decodeURIComponent(m[1])), 'Bộ môn'],
    [/^#\/giai$/, viewTournaments, 'Giải đấu & Thành tích'],
    [/^#\/giai\/(.+)$/, m => viewTournament(decodeURIComponent(m[1])), 'Giải đấu'],
    [/^#\/kiemtra$/, viewIssues, 'Kiểm tra dữ liệu'],
    [/^#\/dulieu$/, viewData, 'Nhập / Xuất dữ liệu'],
  ];
  const state = { filters: {}, page: 1, sort: null };

  function render() {
    const h = location.hash || '#/';
    for (const [re, fn, title] of routes) {
      const m = h.match(re);
      if (m) {
        $('#title').textContent = title;
        $('#content').innerHTML = fn(m) || '';
        window.scrollTo(0, 0);
        document.querySelectorAll('[data-nav]').forEach(a => {
          const nav = a.getAttribute('data-nav');
          a.classList.toggle('active', nav === '#/' ? h === '#/' || h === '' : h.startsWith(nav));
        });
        $('#nav-issues').textContent = issues().filter(i => i.lvl !== 'info').length;
        $('#nav-vdv').textContent = Store.athletes().length;
        $('#nav-hlv').textContent = Store.coaches().length;
        bind();
        return;
      }
    }
    location.hash = '#/';
  }

  // ---------- Views ----------
  function viewDashboard() {
    const A = Store.athletes(), C = Store.coaches();
    const g = count(A, p => p.gender), t = count(A, p => p.tier);
    const sportsUsed = new Set(Store.people.map(p => p.code)).size;
    const teams = new Set(Store.people.map(p => p.team)).size;
    const tl = tally(Store.db.results);
    const iss = issues();
    const bySport = SPORTS.map(s => {
      const list = A.filter(p => p.code === s.code);
      return { s, n: list.length, t: count(list, p => p.tier), c: C.filter(p => p.code === s.code).length };
    }).sort((a, b) => b.n - a.n);
    const max = bySport[0]?.n || 1;
    const ages = A.filter(p => p.year).map(age);
    const bands = [['≤12', a => a <= 12], ['13–15', a => a >= 13 && a <= 15], ['16–18', a => a >= 16 && a <= 18], ['19–23', a => a >= 19 && a <= 23], ['24–30', a => a >= 24 && a <= 30], ['>30', a => a > 30]]
      .map(([l, f]) => [l, ages.filter(f).length]);
    const bandMax = Math.max(...bands.map(b => b[1]), 1);

    return `
    <section class="hero">
      <h2>Thể thao Đà Lạt 2026 — Cao nguyên của những nhà vô địch</h2>
      <p>Dữ liệu từ "Bảng tổng hợp HLV, VĐV tháng 9/2026" · cập nhật ${new Date(Store.db.updated).toLocaleString('vi-VN')}</p>
      <div class="chips"><span class="chip">🏔️ ${sportsUsed} bộ môn</span><span class="chip">👥 ${teams} đội / nhóm</span><span class="chip">🏆 ${Store.db.tournaments.length} giải đấu</span></div>
    </section>
    <div class="grid g4">
      ${kpi('Vận động viên', A.length, `Nam ${g.Nam || 0} · Nữ ${g['Nữ'] || 0}`, '🏃', 'bg-jade', '#/vdv')}
      ${kpi('Huấn luyện viên', C.length, `Tỉ lệ ${(A.length / Math.max(C.length, 1)).toFixed(1)} VĐV/HLV`, '📋', 'bg-electric', '#/hlv')}
      ${kpi('Huy chương', tl.HCV + tl.HCB + tl.HCĐ, `🥇${tl.HCV} · 🥈${tl.HCB} · 🥉${tl.HCĐ}`, '🏅', 'bg-gold', '#/giai')}
      ${kpi('Cảnh báo dữ liệu', iss.filter(i => i.lvl !== 'info').length, `${iss.filter(i => i.lvl === 'err').length} lỗi cần sửa ngay`, '⚠️', 'bg-orange', '#/kiemtra')}
    </div>
    <div class="grid g-2-1" style="margin-top:16px">
      <div class="card">
        <h3>📊 VĐV theo bộ môn & tuyến <span class="muted legend"><span><i class="seg-tuyen"></i>Tuyển</span><span><i class="seg-tre"></i>Trẻ</span><span><i class="seg-nk"></i>Năng khiếu</span></span></h3>
        <div class="bars">
          ${bySport.map(r => `
          <a class="bar-row" href="#/mon/${encodeURIComponent(r.s.code)}" title="${esc(r.s.name)}: Tuyển ${r.t['Tuyển'] || 0}, Trẻ ${r.t['Trẻ'] || 0}, Năng khiếu ${r.t['Năng khiếu'] || 0} · ${r.c} HLV">
            ${sico(r.s.code, 'sm')}<span class="bar-name">${esc(r.s.name)}</span>
            <span class="bar-track">
              <span class="seg-tuyen" style="width:${(r.t['Tuyển'] || 0) / max * 100}%"></span>
              <span class="seg-tre" style="width:${(r.t['Trẻ'] || 0) / max * 100}%"></span>
              <span class="seg-nk" style="width:${(r.t['Năng khiếu'] || 0) / max * 100}%"></span>
            </span><span class="bar-val">${r.n}</span>
          </a>`).join('')}
        </div>
      </div>
      <div class="grid" style="align-content:start">
        <div class="card">
          <h3>⚖️ Cơ cấu giới tính VĐV</h3>
          ${donut([[g.Nam || 0, 'var(--pine-800)', 'Nam'], [g['Nữ'] || 0, 'var(--rose)', 'Nữ']], A.length, 'VĐV')}
        </div>
        <div class="card">
          <h3>🎯 Cơ cấu tuyến</h3>
          ${donut([[t['Tuyển'] || 0, 'var(--pine-800)', 'Tuyển'], [t['Trẻ'] || 0, 'var(--jade-500)', 'Trẻ'], [t['Năng khiếu'] || 0, 'var(--gold)', 'Năng khiếu']], A.length, 'VĐV')}
        </div>
        <div class="card">
          <h3>🎂 Độ tuổi VĐV <span class="muted">tính đến ${YEAR}</span></h3>
          <div class="bars">${bands.map(([l, n]) => `<div class="bar-row" style="grid-template-columns:52px 1fr 40px;cursor:default"><span class="bar-name">${l}</span><span class="bar-track"><span style="width:${n / bandMax * 100}%;background:var(--electric)"></span></span><span class="bar-val">${n}</span></div>`).join('')}</div>
        </div>
      </div>
    </div>
    <div class="grid g2" style="margin-top:16px">
      <div class="card">
        <h3>🏆 Bảng huy chương theo môn <a class="muted" href="#/giai">Xem tất cả →</a></h3>
        ${medalTable()}
      </div>
      <div class="card">
        <h3>⚠️ Cần xử lý <a class="muted" href="#/kiemtra">Xem tất cả →</a></h3>
        ${iss.filter(i => i.lvl !== 'info').slice(0, 6).map(issueRow).join('') || '<div class="empty">Dữ liệu sạch 👍</div>'}
      </div>
    </div>`;
  }

  function kpi(label, value, foot, ico, bg, href) {
    return `<a class="card kpi" href="${href}"><div class="label">${label}</div><div class="value">${value.toLocaleString('vi-VN')}</div><div class="foot">${foot}</div><span class="badge-ico ${bg}">${ico}</span></a>`;
  }

  function donut(parts, total, unit) {
    const R = 52, Cc = 2 * Math.PI * R;
    let off = 0;
    const arcs = parts.map(([v, color]) => {
      const len = total ? v / total * Cc : 0;
      const a = `<circle r="${R}" cx="70" cy="70" fill="none" stroke="${color}" stroke-width="18" stroke-dasharray="${len} ${Cc - len}" stroke-dashoffset="${-off}" transform="rotate(-90 70 70)"/>`;
      off += len; return a;
    }).join('');
    return `<div class="donut-wrap">
      <svg class="donut" viewBox="0 0 140 140" role="img" aria-label="${parts.map(p => `${p[2]} ${p[0]}`).join(', ')}">
        <circle r="${R}" cx="70" cy="70" fill="none" stroke="var(--mist-100)" stroke-width="18"/>${arcs}
        <text x="70" y="70" text-anchor="middle" font-size="30" font-weight="700" fill="var(--ink-900)">${total}</text>
        <text x="70" y="90" text-anchor="middle" font-size="13" fill="var(--ink-600)">${unit}</text>
      </svg>
      <div class="legend" style="flex-direction:column;gap:8px">${parts.map(([v, c, l]) => `<span><i style="background:${c}"></i><b style="color:var(--ink-900)">${l}</b> · ${v} (${total ? (v / total * 100).toFixed(1) : 0}%)</span>`).join('')}</div>
    </div>`;
  }

  function medalTable(filterCode) {
    const rows = SPORTS.filter(s => !filterCode || s.code === filterCode).map(s => {
      const res = Store.db.results.filter(r => Store.person(r.athleteId)?.code === s.code);
      return { s, t: tally(res) };
    }).filter(r => r.t.HCV + r.t.HCB + r.t.HCĐ > 0)
      .sort((a, b) => b.t.HCV - a.t.HCV || b.t.HCB - a.t.HCB || b.t.HCĐ - a.t.HCĐ);
    if (!rows.length) return `<div class="empty"><div class="big">🏅</div>Chưa có thành tích. File Excel gốc chỉ có danh sách nhân sự —<br>hãy tạo giải đấu và nhập kết quả ở mục <a href="#/giai"><b>Giải đấu</b></a>.</div>`;
    return `<div class="table-wrap"><table><thead><tr><th>#</th><th>Bộ môn</th><th class="num">🥇</th><th class="num">🥈</th><th class="num">🥉</th><th class="num">Tổng</th></tr></thead><tbody>
      ${rows.map((r, i) => `<tr onclick="location.hash='#/mon/${encodeURIComponent(r.s.code)}'"><td>${i + 1}</td><td><span class="person">${sico(r.s.code, 'sm')}${esc(r.s.name)}</span></td><td class="num">${r.t.HCV}</td><td class="num">${r.t.HCB}</td><td class="num">${r.t.HCĐ}</td><td class="num"><b>${r.t.HCV + r.t.HCB + r.t.HCĐ}</b></td></tr>`).join('')}
    </tbody></table></div>`;
  }

  function issueRow(i) {
    const lvl = i.lvl === 'err' ? '<span class="tag err">Lỗi</span>' : i.lvl === 'warn' ? '<span class="tag warn">Cảnh báo</span>' : '<span class="tag">Lưu ý</span>';
    const who = i.p ? `<a href="#/p/${encodeURIComponent(i.p.id)}"><b>${esc(i.p.name)}</b></a> · ` : i.sport ? `<a href="#/mon/${encodeURIComponent(i.sport.code)}"><b>${esc(i.sport.name)}</b></a> · ` : '';
    return `<div class="issue"><span class="lvl">${lvl}</span><div><div><b>${esc(i.type)}</b></div><div class="muted">${who}${esc(i.msg)}</div></div></div>`;
  }

  function filteredPeople(role) {
    const f = state.filters;
    const q = fold($('#q')?.value || '');
    let list = Store.people.filter(p => p.role === role);
    if (f.code) list = list.filter(p => p.code === f.code);
    if (f.team) list = list.filter(p => p.team === f.team);
    if (f.tier) list = list.filter(p => p.tier === f.tier);
    if (f.gender) list = list.filter(p => p.gender === f.gender);
    if (f.ageMin) list = list.filter(p => age(p) !== null && age(p) >= +f.ageMin);
    if (f.ageMax) list = list.filter(p => age(p) !== null && age(p) <= +f.ageMax);
    if (q) list = list.filter(p => fold(`${p.name} ${p.id} ${p.team} ${p.year}`).includes(q));
    if (state.sort) {
      const [k, dir] = state.sort;
      list = [...list].sort((a, b) => (a[k] ?? '') > (b[k] ?? '') ? dir : (a[k] ?? '') < (b[k] ?? '') ? -dir : 0);
    }
    return list;
  }

  function viewPeople(role) {
    state.role = role;
    const f = state.filters;
    const teams = [...new Set(Store.people.filter(p => p.role === role && (!f.code || p.code === f.code)).map(p => p.team))];
    const list = filteredPeople(role);
    const per = 50, pages = Math.max(1, Math.ceil(list.length / per));
    state.page = Math.min(state.page, pages);
    const slice = list.slice((state.page - 1) * per, state.page * per);
    const res = Store.db.results;
    const th = (k, l, cls = '') => `<th class="${cls}" data-sort="${k}">${l}${state.sort?.[0] === k ? (state.sort[1] > 0 ? ' ▲' : ' ▼') : ''}</th>`;
    return `
    <div class="toolbar">
      <select data-f="code"><option value="">Tất cả bộ môn</option>${SPORTS.map(s => `<option value="${esc(s.code)}" ${f.code === s.code ? 'selected' : ''}>${s.icon} ${esc(s.name)}</option>`).join('')}</select>
      ${teams.length > 1 ? `<select data-f="team"><option value="">Tất cả đội</option>${teams.map(t => `<option ${f.team === t ? 'selected' : ''}>${esc(t)}</option>`).join('')}</select>` : ''}
      <div class="seg" data-seg="tier">${['', ...TIERS].map(t => `<button class="${(f.tier || '') === t ? 'on' : ''}" data-v="${t}">${t || 'Mọi tuyến'}</button>`).join('')}</div>
      <div class="seg" data-seg="gender">${['', 'Nam', 'Nữ'].map(t => `<button class="${(f.gender || '') === t ? 'on' : ''}" data-v="${t}">${t || 'Nam + Nữ'}</button>`).join('')}</div>
      <input data-f="ageMin" type="number" placeholder="Tuổi từ" style="width:90px" value="${esc(f.ageMin || '')}">
      <input data-f="ageMax" type="number" placeholder="đến" style="width:80px" value="${esc(f.ageMax || '')}">
      <span class="muted"><b>${list.length}</b> / ${Store.people.filter(p => p.role === role).length} ${role}</span>
      <span style="margin-left:auto;display:flex;gap:8px">
        <button class="btn ghost sm" data-act="clear-filter">Xoá lọc</button>
        <button class="btn ghost sm" data-act="export-view">⬇ Xuất Excel</button>
        <button class="btn jade sm" data-act="add-person" data-role="${role}">＋ Thêm ${role}</button>
      </span>
    </div>
    <div class="table-wrap"><table>
      <thead><tr>${th('id', 'Mã', 'hide-m')}${th('name', 'Họ và tên')}${th('year', 'Năm sinh', 'num')}<th class="num hide-m">Tuổi</th>${th('gender', 'Giới tính', 'hide-m')}${th('team', 'Môn / Đội')}${th('tier', 'Tuyến')}${role === 'VĐV' ? '<th class="num hide-m">Huy chương</th>' : ''}</tr></thead>
      <tbody>${slice.map(p => {
        const t = tally(res.filter(r => r.athleteId === p.id));
        return `<tr data-href="#/p/${encodeURIComponent(p.id)}">
          <td class="hide-m muted">${esc(p.id)}</td>
          <td><span class="person">${avatar(p)}<span><b>${esc(p.name)}</b></span></span></td>
          <td class="num">${p.year ?? '<span class="tag err">Thiếu</span>'}</td><td class="num hide-m">${age(p) ?? '–'}</td>
          <td class="hide-m">${esc(p.gender)}</td>
          <td><span class="person">${sico(p.code, 'sm')}${esc(p.team)}</span></td>
          <td>${tierTag(p.tier)}</td>
          ${role === 'VĐV' ? `<td class="num hide-m">${t.HCV + t.HCB + t.HCĐ ? `🥇${t.HCV} 🥈${t.HCB} 🥉${t.HCĐ}` : '–'}</td>` : ''}
        </tr>`;
      }).join('') || `<tr><td colspan="8"><div class="empty">Không có kết quả phù hợp</div></td></tr>`}</tbody>
    </table></div>
    <div class="pager"><button class="btn ghost sm" data-act="page" data-v="-1" ${state.page <= 1 ? 'disabled' : ''}>‹ Trước</button><span class="muted">Trang ${state.page}/${pages}</span><button class="btn ghost sm" data-act="page" data-v="1" ${state.page >= pages ? 'disabled' : ''}>Sau ›</button></div>`;
  }

  function viewProfile(id) {
    const p = Store.person(id);
    if (!p) return `<div class="empty"><div class="big">🔍</div>Không tìm thấy hồ sơ ${esc(id)}</div>`;
    const s = SPORT[p.code] || {};
    const res = Store.db.results.filter(r => r.athleteId === p.id).map(r => ({ ...r, t: Store.tournament(r.tournamentId) })).sort((a, b) => (b.t?.date || '').localeCompare(a.t?.date || ''));
    const t = tally(res);
    const mates = Store.people.filter(x => x.code === p.code && x.id !== p.id && (p.role === 'VĐV' ? x.role === 'HLV' : x.role === 'VĐV' && x.tier === p.tier));
    const myIssues = issues().filter(i => i.p?.id === p.id || i.group?.some(x => x.id === p.id));
    return `
    <div class="profile-head">
      ${avatar(p, 'lg')}
      <div>
        <div class="muted" style="color:#bfe9d9">${esc(p.id)} · ${p.role === 'HLV' ? 'Huấn luyện viên' : 'Vận động viên'}</div>
        <h2>${esc(p.name)}</h2>
        <div class="meta"><span class="chip">${s.icon || '🏅'} ${esc(p.team)}</span><span class="chip">${esc(p.tier)}</span><span class="chip">${esc(p.gender)}</span><span class="chip">${p.year ? `${p.year} · ${age(p)} tuổi` : 'Chưa có năm sinh'}</span></div>
      </div>
      <div class="actions">
        ${p.role === 'VĐV' ? `<button class="btn gold" data-act="add-result" data-athlete="${esc(p.id)}">🏅 Nhập thành tích</button>` : ''}
        <button class="btn ghost" data-act="edit-person" data-id="${esc(p.id)}">✏️ Sửa</button>
      </div>
    </div>
    ${myIssues.length ? `<div class="card" style="margin-bottom:16px;border-left:4px solid var(--orange)">${myIssues.map(issueRow).join('')}</div>` : ''}
    <div class="grid g-2-1">
      <div class="card">
        <h3>🏆 Lịch sử thi đấu ${p.role === 'VĐV' ? `<span class="muted medal-tally" style="margin-left:auto"><span>🥇 ${t.HCV}</span><span>🥈 ${t.HCB}</span><span>🥉 ${t.HCĐ}</span></span>` : ''}</h3>
        ${p.role === 'HLV' ? coachResults(p) : res.length ? `<ul class="timeline">${res.map(r => `
          <li>${medalBadge(r)}<div style="flex:1"><b>${esc(r.t?.name || 'Giải đã xoá')}</b><div class="muted">${esc(r.event || '')} · ${esc(r.t?.level || '')} · ${r.t?.date ? new Date(r.t.date).toLocaleDateString('vi-VN') : ''}${r.rank ? ` · Hạng ${esc(r.rank)}` : ''}${r.record ? ` · ${esc(r.record)}` : ''}</div></div>
          <button class="btn danger sm" data-act="del-result" data-id="${esc(r.id)}">Xoá</button></li>`).join('')}</ul>`
          : `<div class="empty"><div class="big">🎽</div>Chưa có kết quả thi đấu.<br><button class="btn gold" style="margin-top:12px" data-act="add-result" data-athlete="${esc(p.id)}">🏅 Nhập thành tích đầu tiên</button></div>`}
      </div>
      <div class="grid" style="align-content:start">
        <div class="card"><h3>🪪 Thông tin</h3><dl class="kv">
          <dt>Mã</dt><dd>${esc(p.id)}</dd><dt>Mã môn</dt><dd>${esc(p.code)}</dd><dt>Bộ môn</dt><dd>${esc(s.name || p.code)}</dd><dt>Đội</dt><dd>${esc(p.team)}</dd>
          <dt>Tuyến</dt><dd>${tierTag(p.tier)}</dd><dt>Hợp đồng</dt><dd>${esc(p.contract) || '<span class="tag warn">Chưa có</span>'}</dd><dt>Ghi chú</dt><dd>${esc(p.note) || '–'}</dd></dl></div>
        <div class="card"><h3>${p.role === 'VĐV' ? '📋 HLV cùng bộ môn' : '👥 VĐV cùng tuyến'} <span class="muted">${mates.length}</span></h3>
          ${mates.slice(0, 12).map(m => `<a class="person" style="padding:6px 0" href="#/p/${encodeURIComponent(m.id)}">${avatar(m)}<span>${esc(m.name)}<div class="muted">${esc(m.team)} · ${esc(m.tier)}</div></span></a>`).join('') || '<div class="muted">Chưa có</div>'}
          ${mates.length > 12 ? `<div class="muted">… và ${mates.length - 12} người khác</div>` : ''}
        </div>
      </div>
    </div>`;
  }

  function coachResults(p) {
    const ids = new Set(Store.athletes().filter(a => a.code === p.code && a.tier === p.tier).map(a => a.id));
    const t = tally(Store.db.results.filter(r => ids.has(r.athleteId)));
    return `<div class="muted" style="margin-bottom:10px">Thành tích của VĐV ${esc(p.team)} – tuyến ${esc(p.tier)} (${ids.size} VĐV)</div>
      <div class="medal-tally"><div><b>${t.HCV}</b>🥇 Vàng</div><div><b>${t.HCB}</b>🥈 Bạc</div><div><b>${t.HCĐ}</b>🥉 Đồng</div></div>`;
  }

  function viewSports() {
    const A = Store.athletes(), C = Store.coaches();
    return `<div class="toolbar"><span class="muted">${SPORTS.length} bộ môn · bấm vào thẻ để xem chi tiết</span></div>
    <div class="grid g4">${SPORTS.map(s => {
      const a = A.filter(p => p.code === s.code), c = C.filter(p => p.code === s.code).length;
      const t = count(a, p => p.tier), tl = tally(Store.db.results.filter(r => Store.person(r.athleteId)?.code === s.code));
      const ratio = c ? a.length / c : Infinity;
      return `<a class="card sport-card" href="#/mon/${encodeURIComponent(s.code)}">
        <div class="top">${sico(s.code, 'lg')}<div><div class="name">${esc(s.name)}</div><div class="muted">${esc(s.code)} · ${esc(s.group)}</div></div></div>
        <div class="stats"><div><b>${t['Tuyển'] || 0}</b><span>Tuyển</span></div><div><b>${t['Trẻ'] || 0}</b><span>Trẻ</span></div><div><b>${t['Năng khiếu'] || 0}</b><span>N.khiếu</span></div></div>
        <div class="ratio"><span>👥 ${a.length} VĐV · 📋 ${c} HLV</span><span class="tag ${ratio > 15 ? 'warn' : 'tuyen'}">${c ? ratio.toFixed(1) : '∞'} VĐV/HLV</span></div>
        <div class="ratio"><span>🥇${tl.HCV} 🥈${tl.HCB} 🥉${tl.HCĐ}</span></div>
      </a>`;
    }).join('')}</div>`;
  }

  function viewSport(code) {
    const s = SPORT[code];
    if (!s) return `<div class="empty">Không có bộ môn ${esc(code)}</div>`;
    const A = Store.athletes().filter(p => p.code === code), C = Store.coaches().filter(p => p.code === code);
    const teams = [...new Set([...A, ...C].map(p => p.team))];
    const cell = (list, team, tier, g) => list.filter(p => p.team === team && p.tier === tier && (!g || p.gender === g)).length;
    return `
    <div class="profile-head" style="background:linear-gradient(120deg, var(--pine-900), ${s.color})">
      ${sico(code, 'lg')}<div><h2>${esc(s.name)}</h2><div class="meta"><span class="chip">${A.length} VĐV</span><span class="chip">${C.length} HLV</span><span class="chip">${teams.length} đội</span></div></div>
      <div class="actions"><button class="btn ghost" data-act="goto-filter" data-code="${esc(code)}">Xem danh sách VĐV →</button></div>
    </div>
    <div class="card" style="margin-bottom:16px"><h3>🧮 Bảng chi tiết theo đội × tuyến × giới tính <span class="muted">(tương đương bảng COUNTIFS trong Excel)</span></h3>
      <div class="table-wrap"><table><thead><tr><th>Đội</th>${TIERS.map(t => `<th class="num">${t} Nam</th><th class="num">${t} Nữ</th>`).join('')}<th class="num">Tổng VĐV</th><th class="num">HLV</th></tr></thead><tbody>
      ${teams.map(team => `<tr style="cursor:default"><td><b>${esc(team)}</b></td>${TIERS.map(t => `<td class="num">${cell(A, team, t, 'Nam')}</td><td class="num">${cell(A, team, t, 'Nữ')}</td>`).join('')}<td class="num"><b>${A.filter(p => p.team === team).length}</b></td><td class="num">${C.filter(p => p.team === team).length}</td></tr>`).join('')}
      </tbody></table></div></div>
    <div class="grid g2">
      <div class="card"><h3>📋 Huấn luyện viên</h3>${C.map(m => `<a class="person" style="padding:6px 0" href="#/p/${encodeURIComponent(m.id)}">${avatar(m)}<span>${esc(m.name)}<div class="muted">${esc(m.team)} · ${esc(m.tier)} · ${m.year}</div></span></a>`).join('') || '<div class="muted">Chưa có HLV</div>'}</div>
      <div class="card"><h3>🏆 Thành tích</h3>${medalTable(code)}</div>
    </div>`;
  }

  function viewTournaments() {
    const T = [...Store.db.tournaments].sort((a, b) => (b.date || '').localeCompare(a.date || ''));
    const tl = tally(Store.db.results);
    const top = Object.entries(count(Store.db.results.filter(r => r.medal !== 'KHC'), r => r.athleteId))
      .map(([id, n]) => ({ p: Store.person(id), n, t: tally(Store.db.results.filter(r => r.athleteId === id)) }))
      .filter(x => x.p).sort((a, b) => b.t.HCV - a.t.HCV || b.n - a.n).slice(0, 10);
    return `
    <div class="toolbar">
      <button class="btn jade" data-act="add-tournament">＋ Tạo giải đấu</button>
      <button class="btn gold" data-act="add-result">🏅 Nhập thành tích nhanh</button>
      <span class="muted" style="margin-left:auto">${T.length} giải · ${Store.db.results.length} kết quả</span>
    </div>
    <div class="grid g4" style="margin-bottom:16px">
      ${kpi('Huy chương Vàng', tl.HCV, 'HCV', '🥇', 'bg-gold', '#/giai')}
      ${kpi('Huy chương Bạc', tl.HCB, 'HCB', '🥈', 'bg-electric', '#/giai')}
      ${kpi('Huy chương Đồng', tl.HCĐ, 'HCĐ', '🥉', 'bg-orange', '#/giai')}
      ${kpi('Giải đấu', T.length, LEVELS.map(l => `${l}: ${T.filter(t => t.level === l).length}`).slice(0, 3).join(' · '), '🏟️', 'bg-jade', '#/giai')}
    </div>
    <div class="grid g-2-1">
      <div class="card"><h3>🏟️ Danh sách giải đấu</h3>
        ${T.length ? `<div class="table-wrap"><table><thead><tr><th>Giải</th><th class="hide-m">Cấp</th><th>Ngày</th><th class="hide-m">Địa điểm</th><th class="num">🥇</th><th class="num">🥈</th><th class="num">🥉</th></tr></thead><tbody>
          ${T.map(t => { const m = tally(Store.db.results.filter(r => r.tournamentId === t.id)); return `<tr data-href="#/giai/${encodeURIComponent(t.id)}"><td><b>${esc(t.name)}</b></td><td class="hide-m"><span class="tag hlv">${esc(t.level)}</span></td><td>${t.date ? new Date(t.date).toLocaleDateString('vi-VN') : ''}</td><td class="hide-m">${esc(t.place)}</td><td class="num">${m.HCV}</td><td class="num">${m.HCB}</td><td class="num">${m.HCĐ}</td></tr>`; }).join('')}
        </tbody></table></div>` : `<div class="empty"><div class="big">🏟️</div>Chưa có giải đấu nào.<br>File Excel tháng 9/2026 chỉ có danh sách HLV/VĐV, chưa có dữ liệu thành tích.<br><button class="btn jade" style="margin-top:12px" data-act="add-tournament">＋ Tạo giải đầu tiên</button></div>`}
      </div>
      <div class="grid" style="align-content:start">
        <div class="card"><h3>⭐ Top VĐV</h3>${top.map((x, i) => `<a class="person" style="padding:6px 0" href="#/p/${encodeURIComponent(x.p.id)}"><b style="width:18px">${i + 1}</b>${avatar(x.p)}<span style="flex:1">${esc(x.p.name)}<div class="muted">${esc(x.p.team)}</div></span><span>🥇${x.t.HCV} 🥈${x.t.HCB} 🥉${x.t.HCĐ}</span></a>`).join('') || '<div class="muted">Chưa có dữ liệu</div>'}</div>
        <div class="card"><h3>🏆 Theo bộ môn</h3>${medalTable()}</div>
      </div>
    </div>`;
  }

  function viewTournament(id) {
    const t = Store.tournament(id);
    if (!t) return `<div class="empty">Không tìm thấy giải</div>`;
    const res = Store.db.results.filter(r => r.tournamentId === id).map(r => ({ ...r, p: Store.person(r.athleteId) }));
    const order = { HCV: 0, HCB: 1, HCĐ: 2, KHC: 3 };
    res.sort((a, b) => order[a.medal] - order[b.medal]);
    const m = tally(res);
    return `
    <div class="profile-head">
      <span class="sico lg bg-gold">🏆</span>
      <div><h2>${esc(t.name)}</h2><div class="meta"><span class="chip">${esc(t.level)}</span><span class="chip">📅 ${t.date ? new Date(t.date).toLocaleDateString('vi-VN') : ''}${t.dateEnd ? ' – ' + new Date(t.dateEnd).toLocaleDateString('vi-VN') : ''}</span><span class="chip">📍 ${esc(t.place)}</span><span class="chip">🥇${m.HCV} 🥈${m.HCB} 🥉${m.HCĐ}</span></div></div>
      <div class="actions"><button class="btn gold" data-act="add-result" data-tournament="${esc(t.id)}">🏅 Nhập kết quả</button><button class="btn ghost" data-act="edit-tournament" data-id="${esc(t.id)}">✏️ Sửa</button><button class="btn danger" data-act="del-tournament" data-id="${esc(t.id)}">Xoá</button></div>
    </div>
    <div class="card"><h3>📋 Kết quả (${res.length})</h3>
      ${res.length ? `<div class="table-wrap"><table><thead><tr><th>HC</th><th>VĐV</th><th>Môn</th><th>Nội dung</th><th class="hide-m">Hạng</th><th class="hide-m">Thành tích</th><th></th></tr></thead><tbody>
      ${res.map(r => `<tr><td>${medalBadge(r)}</td><td>${r.p ? `<a class="person" href="#/p/${encodeURIComponent(r.p.id)}">${avatar(r.p)}<b>${esc(r.p.name)}</b></a>` : '<span class="muted">VĐV đã xoá</span>'}</td><td>${r.p ? sico(r.p.code, 'sm') : ''}</td><td>${esc(r.event)}</td><td class="hide-m">${esc(r.rank)}</td><td class="hide-m">${esc(r.record)}</td><td><button class="btn danger sm" data-act="del-result" data-id="${esc(r.id)}">Xoá</button></td></tr>`).join('')}
      </tbody></table></div>` : '<div class="empty">Chưa có kết quả</div>'}
    </div>`;
  }

  function viewIssues() {
    const iss = issues();
    const groups = count(iss, i => i.type);
    return `<div class="toolbar">${Object.entries(groups).map(([k, n]) => `<span class="tag ${iss.find(i => i.type === k).lvl}">${esc(k)}: ${n}</span>`).join('')}</div>
      <div class="card">${iss.map(issueRow).join('') || '<div class="empty">Không phát hiện vấn đề</div>'}</div>`;
  }

  function viewData() {
    return `<div class="grid g2">
      <div class="card"><h3>⬆️ Nhập từ Excel</h3>
        <p class="muted">Chấp nhận file cùng cấu trúc với "bang_tong_hop_HLV_V_V_Thang_*.xlsx": sheet <b>HLV</b> và <b>VĐV</b>, dòng tiêu đề có các cột CODE MÔN, HỌ VÀ TÊN, NĂM SINH, GIỚI TÍNH, NHIỆM VỤ, MÔN THỂ THAO, ĐỘI TUYẾN (HỢP ĐỒNG, GHI CHÚ tuỳ chọn). Các dòng bảng tổng hợp COUNTIFS bên dưới sẽ tự bỏ qua.</p>
        <input type="file" id="file-in" accept=".xlsx,.xls" style="margin:10px 0">
        <div class="seg" data-seg="importMode"><button class="${state.importMode !== 'merge' ? 'on' : ''}" data-v="replace">Thay toàn bộ danh sách</button><button class="${state.importMode === 'merge' ? 'on' : ''}" data-v="merge">Chỉ thêm người mới</button></div>
        <p class="muted">⚠ "Thay toàn bộ" giữ nguyên giải đấu, nhưng kết quả của VĐV không còn trong file mới sẽ mất liên kết.</p>
      </div>
      <div class="card"><h3>⬇️ Xuất dữ liệu</h3>
        <p class="muted">Xuất file Excel gồm: DS_HLV, DS_VĐV, Tổng hợp theo môn × tuyến × giới tính (giống bảng COUNTIFS gốc), Giải đấu, Thành tích.</p>
        <button class="btn jade" data-act="export-all">⬇ Xuất Excel đầy đủ</button>
        <button class="btn ghost" data-act="backup">💾 Sao lưu JSON</button>
        <label class="btn ghost">♻️ Khôi phục JSON<input type="file" id="json-in" accept=".json" hidden></label>
      </div>
      <div class="card"><h3>🧪 Dữ liệu demo</h3>
        <p class="muted">Sinh ngẫu nhiên 4 giải đấu + kết quả để xem thử bảng huy chương. <b>Không phải dữ liệu thật</b> — xoá trước khi dùng chính thức.</p>
        <button class="btn gold" data-act="demo">Tạo thành tích demo</button>
        <button class="btn danger" data-act="clear-results">Xoá toàn bộ giải & thành tích</button>
      </div>
      <div class="card"><h3>🔄 Khôi phục gốc</h3>
        <p class="muted">Đưa danh sách về đúng 79 HLV + 662 VĐV từ file Excel tháng 9/2026, xoá mọi giải đấu/thành tích.</p>
        <button class="btn danger" data-act="reset">Khôi phục dữ liệu gốc</button>
      </div>
    </div>`;
  }

  // ---------- Modals ----------
  function modal(html, onSubmit) {
    const bg = document.createElement('div');
    bg.className = 'modal-bg';
    bg.innerHTML = `<form class="modal">${html}<div class="actions"><button type="button" class="btn ghost" data-close>Huỷ</button><button class="btn jade">Lưu</button></div></form>`;
    document.body.appendChild(bg);
    const close = () => bg.remove();
    bg.addEventListener('click', e => { if (e.target === bg || e.target.hasAttribute('data-close')) close(); });
    bg.querySelector('form').addEventListener('submit', e => {
      e.preventDefault();
      const data = Object.fromEntries(new FormData(e.target));
      if (onSubmit(data, bg) !== false) close();
    });
    bg.querySelector('input,select')?.focus();
    return bg;
  }

  function personForm(p, role) {
    const isNew = !p;
    p = p || { role, code: state.filters.code || SPORTS[0].code, tier: 'Tuyển', gender: 'Nam', name: '', year: '', team: '', contract: '', note: '' };
    const teams = [...new Set(Store.people.map(x => x.team))];
    modal(`<h3>${isNew ? 'Thêm' : 'Sửa'} ${p.role}</h3>
      <div class="form">
        <div class="field full"><label>Họ và tên *</label><input name="name" required value="${esc(p.name)}"></div>
        <div class="field"><label>Năm sinh</label><input name="year" type="number" min="1940" max="${YEAR}" value="${esc(p.year ?? '')}"></div>
        <div class="field"><label>Giới tính</label><select name="gender">${['Nam', 'Nữ'].map(g => `<option ${p.gender === g ? 'selected' : ''}>${g}</option>`).join('')}</select></div>
        <div class="field"><label>Bộ môn (CODE MÔN)</label><select name="code">${SPORTS.map(s => `<option value="${esc(s.code)}" ${p.code === s.code ? 'selected' : ''}>${s.icon} ${esc(s.name)}</option>`).join('')}</select></div>
        <div class="field"><label>Đội (MÔN THỂ THAO)</label><input name="team" list="teams" value="${esc(p.team)}" placeholder="vd. U17, Bóng đá Trẻ, Judo"><datalist id="teams">${teams.map(t => `<option>${esc(t)}</option>`).join('')}</datalist></div>
        <div class="field"><label>Tuyến (ĐỘI TUYẾN)</label><select name="tier">${TIERS.map(t => `<option ${p.tier === t ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
        <div class="field"><label>Hợp đồng</label><input name="contract" value="${esc(p.contract)}" placeholder="Số HĐ / thời hạn"></div>
        <div class="field full"><label>Ghi chú</label><textarea name="note" rows="2">${esc(p.note)}</textarea></div>
        ${isNew ? '' : `<div class="field full"><button type="button" class="btn danger" data-del="${esc(p.id)}">🗑 Xoá ${esc(p.role)} này</button></div>`}
      </div>`, d => {
      d.name = d.name.trim().replace(/\s+/g, ' ');
      d.year = d.year ? +d.year : null;
      d.team = d.team.trim() || SPORT[d.code].name;
      if (isNew) {
        const np = { ...d, role: p.role, id: Store.nextId(p.role, d.code) };
        Store.db.people.push(np); Store.save(); toast(`Đã thêm ${np.name}`); location.hash = `#/p/${encodeURIComponent(np.id)}`;
      } else {
        Object.assign(p, d); Store.save(); toast('Đã lưu'); render();
      }
    });
    document.querySelector('[data-del]')?.addEventListener('click', e => {
      const id = e.target.dataset.del;
      const n = Store.db.results.filter(r => r.athleteId === id).length;
      if (!confirm(`Xoá ${p.name}${n ? ` và ${n} kết quả thi đấu` : ''}?`)) return;
      Store.db.people = Store.db.people.filter(x => x.id !== id);
      Store.db.results = Store.db.results.filter(r => r.athleteId !== id);
      Store.save(); document.querySelector('.modal-bg')?.remove(); toast('Đã xoá'); location.hash = p.role === 'HLV' ? '#/hlv' : '#/vdv';
    });
  }

  function tournamentForm(t) {
    const isNew = !t;
    t = t || { name: '', level: 'Tỉnh', date: new Date().toISOString().slice(0, 10), dateEnd: '', place: 'Đà Lạt' };
    modal(`<h3>${isNew ? 'Tạo' : 'Sửa'} giải đấu</h3>
      <div class="form">
        <div class="field full"><label>Tên giải *</label><input name="name" required value="${esc(t.name)}" placeholder="vd. Giải vô địch Taekwondo toàn quốc 2026"></div>
        <div class="field"><label>Cấp giải</label><select name="level">${LEVELS.map(l => `<option ${t.level === l ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
        <div class="field"><label>Địa điểm</label><input name="place" value="${esc(t.place)}"></div>
        <div class="field"><label>Từ ngày</label><input name="date" type="date" required value="${esc(t.date)}"></div>
        <div class="field"><label>Đến ngày</label><input name="dateEnd" type="date" value="${esc(t.dateEnd)}"></div>
      </div>`, d => {
      if (isNew) { const nt = { ...d, id: Store.uid('T') }; Store.db.tournaments.push(nt); Store.save(); location.hash = `#/giai/${nt.id}`; }
      else { Object.assign(t, d); Store.save(); render(); }
      toast('Đã lưu giải đấu');
    });
  }

  function resultForm({ athlete, tournament } = {}) {
    if (!Store.db.tournaments.length) { toast('Hãy tạo giải đấu trước'); return tournamentForm(); }
    const A = Store.athletes();
    const T = [...Store.db.tournaments].sort((a, b) => (b.date || '').localeCompare(a.date || ''));
    const pre = athlete ? Store.person(athlete) : null;
    const bg = modal(`<h3>🏅 Nhập thành tích</h3>
      <div class="form">
        <div class="field full"><label>Giải đấu *</label><select name="tournamentId" required>${T.map(t => `<option value="${esc(t.id)}" ${t.id === tournament ? 'selected' : ''}>${esc(t.name)} (${esc(t.level)})</option>`).join('')}</select></div>
        <div class="field full"><label>Vận động viên * <span class="muted">(gõ tên không dấu cũng được)</span></label>
          <input name="athleteQ" list="ath" autocomplete="off" required value="${pre ? esc(`${pre.name} · ${pre.team} · ${pre.id}`) : ''}" placeholder="Gõ tên VĐV…">
          <datalist id="ath">${A.map(p => `<option value="${esc(`${p.name} · ${p.team} · ${p.id}`)}"></option>`).join('')}</datalist></div>
        <div class="field full"><label>Nội dung thi đấu</label><input name="event" placeholder="vd. 100m nam, Kumite -60kg, Đơn nữ"></div>
        <div class="field full"><label>Huy chương</label><div class="quick-medal">${MEDALS.map((m, i) => `<button type="button" data-medal="${m.key}" class="${i === 0 ? 'on' : ''}">${m.cls ? `<span class="medal ${m.cls}">${m.label[0]}</span><br>` : '–<br>'}${m.label}</button>`).join('')}</div><input type="hidden" name="medal" value="HCV"></div>
        <div class="field"><label>Thứ hạng</label><input name="rank" placeholder="1, 2, 3, Tứ kết…"></div>
        <div class="field"><label>Thành tích / thông số</label><input name="record" placeholder="10.52s, 120kg…"></div>
      </div>
      <label class="muted" style="display:flex;gap:6px;margin-top:10px"><input type="checkbox" name="again" checked> Lưu xong nhập tiếp VĐV khác cùng giải</label>`, (d, el) => {
      const id = d.athleteQ.split(' · ').pop().trim();
      const p = Store.person(id);
      if (!p || p.role !== 'VĐV') { alert('Chọn VĐV từ danh sách gợi ý'); return false; }
      Store.db.results.push({ id: Store.uid('R'), tournamentId: d.tournamentId, athleteId: id, event: d.event.trim(), medal: d.medal, rank: d.rank.trim(), record: d.record.trim() });
      Store.save(); toast(`Đã lưu: ${p.name} – ${d.medal}`);
      if (d.again) { el.querySelector('[name=athleteQ]').value = ''; el.querySelector('[name=athleteQ]').focus(); el.querySelector('[name=record]').value = ''; return false; }
      render();
    });
    bg.querySelectorAll('[data-medal]').forEach(b => b.addEventListener('click', () => {
      bg.querySelectorAll('[data-medal]').forEach(x => x.classList.remove('on')); b.classList.add('on');
      bg.querySelector('[name=medal]').value = b.dataset.medal;
    }));
    const obs = new MutationObserver(() => { if (!document.body.contains(bg)) { obs.disconnect(); render(); } });
    obs.observe(document.body, { childList: true });
  }

  // ---------- Excel ----------
  function needXLSX() {
    if (window.XLSX) return true;
    toast('Chưa tải được thư viện Excel (cần mạng lần đầu)'); return false;
  }
  function parseWorkbook(wb) {
    const out = [];
    const seen = {};
    wb.SheetNames.forEach(sheet => {
      const rows = XLSX.utils.sheet_to_json(wb.Sheets[sheet], { header: 1, defval: '' });
      const hi = rows.findIndex(r => r.some(c => String(c).trim().toUpperCase() === 'HỌ VÀ TÊN'));
      if (hi < 0) return;
      const H = rows[hi].map(c => String(c).trim().toUpperCase());
      const col = k => H.indexOf(k);
      for (const r of rows.slice(hi + 1)) {
        const name = String(r[col('HỌ VÀ TÊN')] || '').trim().replace(/\s+/g, ' ');
        const code = String(r[col('CODE MÔN')] || '').trim();
        const duty = String(r[col('NHIỆM VỤ')] || '').toLowerCase();
        // Bỏ dòng trống và các bảng tổng hợp COUNTIFS phía dưới (không có cột NHIỆM VỤ hợp lệ)
        if (!name || !code || /^\d+$/.test(name) || !/huấn luyện|vận động/.test(duty)) continue;
        const role = duty.includes('huấn luyện') ? 'HLV' : 'VĐV';
        const key = role + code; seen[key] = (seen[key] || 0) + 1;
        let team = String(r[col('MÔN THỂ THAO')] || '').trim().replace(/\s+/g, ' ');
        if (fold(team) === fold('Khiêu vũ thể thao')) team = 'Khiêu vũ thể thao';
        const y = parseInt(r[col('NĂM SINH')], 10);
        out.push({ id: `${role === 'HLV' ? 'H' : 'V'}-${code}-${String(seen[key]).padStart(3, '0')}`, name, year: Number.isFinite(y) ? y : null, gender: String(r[col('GIỚI TÍNH')] || '').trim(), role, code, team, tier: String(r[col('ĐỘI TUYẾN')] || '').trim(), contract: col('HỢP ĐỒNG') >= 0 ? String(r[col('HỢP ĐỒNG')] || '').trim() : '', note: col('GHI CHÚ') >= 0 ? String(r[col('GHI CHÚ')] || '').trim() : '' });
      }
    });
    return out;
  }
  function importExcel(file) {
    if (!needXLSX()) return;
    file.arrayBuffer().then(buf => {
      const list = parseWorkbook(XLSX.read(buf));
      if (!list.length) return toast('Không tìm thấy dòng dữ liệu hợp lệ');
      const h = list.filter(p => p.role === 'HLV').length, v = list.length - h;
      if (state.importMode === 'merge') {
        const keys = new Set(Store.people.map(p => `${fold(p.name)}|${p.year}|${p.code}`));
        const add = list.filter(p => !keys.has(`${fold(p.name)}|${p.year}|${p.code}`));
        add.forEach(p => { p.id = Store.nextId(p.role, p.code); Store.db.people.push(p); });
        Store.save(); toast(`Đã thêm ${add.length} người mới (bỏ qua ${list.length - add.length} trùng)`);
      } else {
        if (!confirm(`Thay danh sách hiện tại bằng ${h} HLV + ${v} VĐV từ file?`)) return;
        Store.db.people = list; Store.save(); toast(`Đã nhập ${h} HLV + ${v} VĐV`);
      }
      render();
    }).catch(e => toast('Lỗi đọc file: ' + e.message));
  }
  const personRow = p => ({ 'MÃ': p.id, 'CODE MÔN': p.code, 'HỌ VÀ TÊN': p.name, 'NĂM SINH': p.year, 'TUỔI': age(p), 'GIỚI TÍNH': p.gender, 'NHIỆM VỤ': p.role === 'HLV' ? 'Huấn luyện viên' : 'Vận động viên', 'MÔN THỂ THAO': p.team, 'ĐỘI TUYẾN': p.tier, 'HỢP ĐỒNG': p.contract, 'GHI CHÚ': p.note });
  function exportAll() {
    if (!needXLSX()) return;
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(Store.coaches().map(personRow)), 'DS_HLV');
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(Store.athletes().map(personRow)), 'DS_VĐV');
    const teams = [...new Set(Store.people.map(p => p.team))];
    const sum = teams.map(team => {
      const row = { 'MÔN THỂ THAO': team };
      ['VĐV', 'HLV'].forEach(role => TIERS.forEach(t => ['Nam', 'Nữ'].forEach(g => {
        row[`${role} ${t} ${g}`] = Store.people.filter(p => p.role === role && p.team === team && p.tier === t && p.gender === g).length;
      })));
      row['TỔNG VĐV'] = Store.athletes().filter(p => p.team === team).length;
      row['TỔNG HLV'] = Store.coaches().filter(p => p.team === team).length;
      return row;
    });
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(sum), 'TONG_HOP');
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(Store.db.tournaments.map(t => ({ 'MÃ GIẢI': t.id, 'TÊN GIẢI': t.name, 'CẤP': t.level, 'TỪ NGÀY': t.date, 'ĐẾN NGÀY': t.dateEnd, 'ĐỊA ĐIỂM': t.place }))), 'GIAI_DAU');
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(Store.db.results.map(r => { const p = Store.person(r.athleteId) || {}; const t = Store.tournament(r.tournamentId) || {}; return { 'GIẢI': t.name, 'CẤP': t.level, 'NGÀY': t.date, 'MÃ VĐV': r.athleteId, 'HỌ VÀ TÊN': p.name, 'MÔN': p.team, 'TUYẾN': p.tier, 'NỘI DUNG': r.event, 'HUY CHƯƠNG': r.medal, 'HẠNG': r.rank, 'THÀNH TÍCH': r.record }; })), 'THANH_TICH');
    XLSX.writeFile(wb, `DalatSportsHub_${new Date().toISOString().slice(0, 10)}.xlsx`);
  }
  function exportView() {
    if (!needXLSX()) return;
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(filteredPeople(state.role).map(personRow)), state.role === 'HLV' ? 'HLV' : 'VDV');
    XLSX.writeFile(wb, `${state.role === 'HLV' ? 'HLV' : 'VDV'}_loc_${new Date().toISOString().slice(0, 10)}.xlsx`);
  }

  function makeDemo() {
    const names = [['Giải vô địch trẻ toàn quốc 2026', 'Quốc gia', '2026-06-12', 'Hà Nội'], ['Đại hội TDTT tỉnh Lâm Đồng 2026', 'Tỉnh', '2026-04-20', 'Đà Lạt'], ['Giải các CLB Tây Nguyên mở rộng', 'Khu vực', '2026-08-02', 'Buôn Ma Thuột'], ['Giải vô địch Đông Nam Á mở rộng', 'Quốc tế', '2026-09-15', 'Bangkok']];
    const T = names.map(([name, level, date, place]) => ({ id: Store.uid('T'), name: `[DEMO] ${name}`, level, date, dateEnd: '', place }));
    Store.db.tournaments.push(...T);
    const A = Store.athletes().filter(p => p.tier !== 'Năng khiếu');
    const meds = ['HCV', 'HCB', 'HCĐ', 'HCĐ', 'KHC', 'KHC'];
    for (let i = 0; i < 120; i++) {
      const p = A[Math.floor(Math.random() * A.length)], t = T[Math.floor(Math.random() * T.length)];
      Store.db.results.push({ id: Store.uid('R'), tournamentId: t.id, athleteId: p.id, event: p.team, medal: meds[Math.floor(Math.random() * meds.length)], rank: '', record: '' });
    }
    Store.save(); toast('Đã tạo 4 giải + 120 kết quả DEMO'); location.hash = '#/giai';
  }

  // ---------- Bind events ----------
  function bind() {
    const c = $('#content');
    c.querySelectorAll('[data-f]').forEach(el => el.addEventListener('change', () => {
      state.filters[el.dataset.f] = el.value; if (el.dataset.f === 'code') state.filters.team = ''; state.page = 1; render();
    }));
    c.querySelectorAll('[data-seg]').forEach(seg => seg.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
      if (seg.dataset.seg === 'importMode') { state.importMode = b.dataset.v; return render(); }
      state.filters[seg.dataset.seg] = b.dataset.v; state.page = 1; render();
    })));
    c.querySelectorAll('th[data-sort]').forEach(th => th.addEventListener('click', () => {
      const k = th.dataset.sort; state.sort = state.sort?.[0] === k ? [k, -state.sort[1]] : [k, 1]; render();
    }));
    c.querySelectorAll('tr[data-href]').forEach(tr => tr.addEventListener('click', () => { location.hash = tr.dataset.href; }));
    $('#file-in')?.addEventListener('change', e => e.target.files[0] && importExcel(e.target.files[0]));
    $('#json-in')?.addEventListener('change', e => e.target.files[0]?.text().then(txt => {
      try { const d = JSON.parse(txt); if (!Array.isArray(d.people)) throw new Error('sai cấu trúc'); Store.db = d; Store.save(); toast('Đã khôi phục'); render(); } catch (err) { toast('File không hợp lệ: ' + err.message); }
    }));
  }

  document.addEventListener('click', e => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const a = b.dataset.act;
    if (a === 'add-person') personForm(null, b.dataset.role);
    else if (a === 'edit-person') personForm(Store.person(b.dataset.id));
    else if (a === 'add-tournament') tournamentForm();
    else if (a === 'edit-tournament') tournamentForm(Store.tournament(b.dataset.id));
    else if (a === 'del-tournament') {
      const n = Store.db.results.filter(r => r.tournamentId === b.dataset.id).length;
      if (confirm(`Xoá giải này và ${n} kết quả?`)) { Store.db.tournaments = Store.db.tournaments.filter(t => t.id !== b.dataset.id); Store.db.results = Store.db.results.filter(r => r.tournamentId !== b.dataset.id); Store.save(); location.hash = '#/giai'; }
    }
    else if (a === 'add-result') resultForm({ athlete: b.dataset.athlete, tournament: b.dataset.tournament });
    else if (a === 'del-result') { if (confirm('Xoá kết quả này?')) { Store.db.results = Store.db.results.filter(r => r.id !== b.dataset.id); Store.save(); render(); } }
    else if (a === 'page') { state.page += +b.dataset.v; render(); }
    else if (a === 'clear-filter') { state.filters = {}; state.sort = null; $('#q').value = ''; render(); }
    else if (a === 'goto-filter') { state.filters = { code: b.dataset.code }; location.hash = '#/vdv'; }
    else if (a === 'export-all') exportAll();
    else if (a === 'export-view') exportView();
    else if (a === 'backup') {
      const url = URL.createObjectURL(new Blob([JSON.stringify(Store.db)], { type: 'application/json' }));
      Object.assign(document.createElement('a'), { href: url, download: `dalat-sports-backup-${new Date().toISOString().slice(0, 10)}.json` }).click();
    }
    else if (a === 'demo') makeDemo();
    else if (a === 'clear-results') { if (confirm('Xoá toàn bộ giải đấu và thành tích?')) { Store.db.tournaments = []; Store.db.results = []; Store.save(); render(); } }
    else if (a === 'reset') { if (confirm('Khôi phục 79 HLV + 662 VĐV gốc và xoá mọi thành tích?')) { Store.reset(); render(); } }
    else if (a === 'fab') resultForm();
  });

  let qTimer;
  $('#q').addEventListener('input', () => {
    clearTimeout(qTimer);
    qTimer = setTimeout(() => {
      if (!/^#\/(vdv|hlv)$/.test(location.hash)) { location.hash = '#/vdv'; return; }
      state.page = 1; render(); $('#q').focus();
    }, 200);
  });
  window.addEventListener('hashchange', render);
  Store.load();
  render();
  if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('./sw.js').catch(() => {});
})();

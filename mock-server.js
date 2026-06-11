// mock-server.js — Mô phỏng GAS API để test local
// Chạy: node mock-server.js
// Endpoint: http://localhost:3000

const http = require('http');
const fs   = require('fs');
const path = require('path');

const PORT    = 3000;
const DB_FILE = path.join(__dirname, 'data', 'mock-db.json');

// ── Khởi tạo DB từ CSV nếu chưa có ──────────────────────────
function initDB() {
  if (fs.existsSync(DB_FILE)) return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));

  // Parse CSV helper
  const parseCSV = (file) => {
    const lines = fs.readFileSync(path.join(__dirname, 'data', 'template', file), 'utf8')
      .trim().split('\n');
    const headers = lines[0].split(',');
    return lines.slice(1).map(line => {
      const vals = line.split(',');
      const obj  = {};
      headers.forEach((h, i) => obj[h.trim()] = (vals[i] || '').trim());
      return obj;
    });
  };

  const db = {
    danh_muc_vat_tu : parseCSV('DanhMuc_VatTu.csv'),
    ke_hoach_kpi    : parseCSV('KeHoach_KPI.csv'),
    du_lieu_hien_truong: []
  };
  fs.mkdirSync(path.dirname(DB_FILE), { recursive: true });
  fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
  console.log('[mock] DB khởi tạo từ CSV');
  return db;
}

function saveDB(db) {
  fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
}

// ── CORS headers ──────────────────────────────────────────────
function cors(res) {
  res.setHeader('Access-Control-Allow-Origin',  '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function json(res, data, status = 200) {
  cors(res);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data));
}

// ── Server ────────────────────────────────────────────────────
const db = initDB();

const server = http.createServer((req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  const url    = new URL(req.url, `http://localhost:${PORT}`);
  const type   = url.searchParams.get('type');

  // GET — danh mục vật tư
  if (req.method === 'GET' && type === 'vattu') {
    return json(res, { ok: true, data: db.danh_muc_vat_tu });
  }

  // GET — danh sách công trình
  if (req.method === 'GET' && type === 'congtrinh') {
    return json(res, { ok: true, data: db.ke_hoach_kpi });
  }

  // GET — dữ liệu hiện trường (theo mã trạm)
  if (req.method === 'GET' && type === 'hientruong') {
    const maTram = url.searchParams.get('ma_tram') || '';
    const data   = maTram
      ? db.du_lieu_hien_truong.filter(r => r.ma_tram === maTram)
      : db.du_lieu_hien_truong;
    return json(res, { ok: true, data });
  }

  // POST — ghi điểm (append-only, không ghi đè)
  if (req.method === 'POST') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const payload = JSON.parse(body);
        const records = Array.isArray(payload) ? payload : [payload];
        records.forEach(r => {
          db.du_lieu_hien_truong.push({
            ...r,
            thoi_gian_dong_bo: new Date().toISOString()
          });
        });
        saveDB(db);
        console.log(`[mock] POST ${records.length} điểm — tổng: ${db.du_lieu_hien_truong.length}`);
        json(res, { ok: true, count: records.length });
      } catch (e) {
        json(res, { ok: false, error: e.message }, 400);
      }
    });
    return;
  }

  // 404
  json(res, { ok: false, error: 'Not found' }, 404);
});

server.listen(PORT, () => {
  console.log(`\n[mock-server] đang chạy tại http://localhost:${PORT}`);
  console.log('  GET  ?type=vattu       → danh mục vật tư');
  console.log('  GET  ?type=congtrinh   → danh sách công trình');
  console.log('  GET  ?type=hientruong  → dữ liệu đã đồng bộ');
  console.log('  POST (JSON)            → ghi điểm mới\n');
});

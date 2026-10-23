// ============================================================
//  NhacViec.js — Bot nhắc việc buổi sáng vào nhóm Zalo
//
//  Nguồn việc:
//    1. Sheet "NhacViec"         (anh tự nhập việc, hạn, lặp lại)
//    2. Sheet "KeHoach_CongTrinh" (công trình chưa hoàn thành)
//  Gửi qua: Zalo Bot API (https://bot.zapps.me) — gọi sendMessage
//
//  CÀI ĐẶT (làm 1 lần, xem HUONGDAN_NHACVIEC.md):
//    1. Tạo bot trên app Zalo → lấy BOT TOKEN
//    2. Project Settings → Script Properties: ZALO_BOT_TOKEN = <token>
//    3. Thêm bot vào nhóm Zalo, nhắn 1 tin bất kỳ trong nhóm
//    4. Chạy nv_layChatId()  → copy chat_id của nhóm vào ZALO_CHAT_ID
//    5. Chạy nv_guiThu()     → kiểm tra tin nhắn thử trong nhóm
//    6. Chạy nv_caiDat()     → tạo sheet + bật lịch 8h sáng
// ============================================================

const NV_SHEET        = 'NhacViec';
const NV_GIO_GUI      = 8;      // 8h sáng giờ VN (appsscript.json: Asia/Ho_Chi_Minh)
const NV_GIO_HET_HAN  = 10;     // quá 10h mà chưa gửi được thì bỏ, không gửi muộn
const NV_SAP_DEN_HAN  = 3;      // báo trước bao nhiêu ngày
const NV_MAX_KY_TU    = 1800;   // cắt tin dài thành nhiều tin
const NV_API_MAC_DINH = 'https://bot-api.zapps.me';
const NV_THU = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
const NV_THU_DAY_DU = ['Chủ nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];

// ── Hàm trigger chạy mỗi 5 phút, chỉ gửi 1 lần/ngày lúc ≥ 8h ──
// Dùng trigger 5 phút thay vì atHour(8) vì atHour() của GAS lệch tới ±1 giờ.
function nv_kiemTraVaGui() {
  const now   = new Date();
  const gio   = Number(_nvFmt(now, 'H'));
  const homNay = _nvFmt(now, 'yyyy-MM-dd');
  const props = PropertiesService.getScriptProperties();

  if (gio < NV_GIO_GUI || gio >= NV_GIO_HET_HAN) return;
  if (props.getProperty('NV_DA_GUI') === homNay) return;
  if (!_nvNgayDuocGui(now, props)) return;

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) return;
  try {
    if (props.getProperty('NV_DA_GUI') === homNay) return;
    nv_guiNhacViec(now);
    props.setProperty('NV_DA_GUI', homNay);
  } finally {
    lock.releaseLock();
  }
}

// ── Gửi bản tin nhắc việc ngay (dùng cho trigger + chạy tay) ──
function nv_guiNhacViec(now) {
  now = _nvLaNgay(now) ? now : new Date();   // trigger truyền event object, không phải Date
  const noiDung = nv_taoNoiDung(now);
  _nvGuiZalo(noiDung);
  Logger.log('✅ Đã gửi nhắc việc:\n' + noiDung);
  return noiDung;
}

// ── Gửi thử: có tiêu đề [THỬ NGHIỆM], không đánh dấu "đã gửi hôm nay" ──
function nv_guiThu() {
  const noiDung = '🧪 [THỬ NGHIỆM] Bot nhắc việc đã kết nối nhóm thành công.\n\n' +
                  nv_taoNoiDung(new Date());
  _nvGuiZalo(noiDung);
  Logger.log('✅ Đã gửi tin thử:\n' + noiDung);
}

// ── Xem trước nội dung trong Logger, KHÔNG gửi Zalo ──
function nv_xemTruoc() {
  const noiDung = nv_taoNoiDung(new Date());
  Logger.log(noiDung);
  return noiDung;
}

// ── Lấy chat_id: thêm bot vào nhóm, nhắn 1 tin, rồi chạy hàm này ──
function nv_layChatId() {
  const res = _nvGoiApi('getUpdates', { timeout: 5 });
  const ds  = [].concat(res.result || []);
  if (!ds.length) {
    Logger.log('⚠️ Chưa có tin nào. Hãy nhắn 1 tin trong nhóm có bot (hoặc @tag bot) rồi chạy lại trong vòng vài phút.');
    return [];
  }
  const out = ds.map(u => {
    const m = u.message || {};
    const c = m.chat || {};
    return { chat_id: c.id, loai: c.chat_type, nguoi_gui: (m.from || {}).display_name, tin: m.text };
  });
  out.forEach(o => Logger.log(`chat_id=${o.chat_id} | loại=${o.loai} | từ=${o.nguoi_gui} | "${o.tin}"`));
  Logger.log('👉 Copy chat_id có loại GROUP vào Script Properties → ZALO_CHAT_ID');
  return out;
}

// ── Cài đặt: tạo sheet mẫu + trigger 5 phút ──
function nv_caiDat() {
  _nvTaoSheet();
  nv_tatLich();
  ScriptApp.newTrigger('nv_kiemTraVaGui').timeBased().everyMinutes(5).create();
  Logger.log(`✅ Đã bật lịch: gửi mỗi sáng từ ${NV_GIO_GUI}:00 (trễ tối đa ~5 phút).`);
}

// ── Tắt lịch nhắc việc ──
function nv_tatLich() {
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === 'nv_kiemTraVaGui')
    .forEach(t => ScriptApp.deleteTrigger(t));
  Logger.log('⏹ Đã tắt lịch nhắc việc.');
}

// ============================================================
//  TẠO NỘI DUNG
// ============================================================
function nv_taoNoiDung(now) {
  const homNay = _nvDauNgay(now);
  const viec   = _nvDocViec();

  const quaHan = [], hanHomNay = [], sapDen = [], dinhKy = [];
  viec.forEach(v => {
    if (v.lap) {
      if (_nvLapHomNay(v.lap, homNay)) dinhKy.push(v);
      return;
    }
    if (!v.han) { hanHomNay.push(v); return; }
    const d = Math.round((_nvDauNgay(v.han) - homNay) / 86400000);
    if (d < 0)                   quaHan.push(Object.assign({ tre: -d }, v));
    else if (d === 0)            hanHomNay.push(v);
    else if (d <= NV_SAP_DEN_HAN) sapDen.push(Object.assign({ con: d }, v));
  });
  quaHan.sort((a, b) => b.tre - a.tre);
  sapDen.sort((a, b) => a.con - b.con);

  const L = [];
  L.push(`☀️ NHẮC VIỆC ${NV_THU_DAY_DU[homNay.getDay()].toUpperCase()} ${_nvFmt(now, 'dd/MM/yyyy')}`);
  L.push('━━━━━━━━━━━━━━━');

  if (quaHan.length) {
    L.push('', `🔴 QUÁ HẠN (${quaHan.length})`);
    quaHan.forEach((v, i) => L.push(`${i + 1}. ${v.viec}${_nvAi(v)} — trễ ${v.tre} ngày (hạn ${_nvFmt(v.han, 'dd/MM')})`));
  }
  if (hanHomNay.length) {
    L.push('', `🟠 HẠN HÔM NAY (${hanHomNay.length})`);
    hanHomNay.forEach((v, i) => L.push(`${i + 1}. ${v.viec}${_nvAi(v)}`));
  }
  if (dinhKy.length) {
    L.push('', `🔁 VIỆC ĐỊNH KỲ (${dinhKy.length})`);
    dinhKy.forEach((v, i) => L.push(`${i + 1}. ${v.viec}${_nvAi(v)}`));
  }
  if (sapDen.length) {
    L.push('', `🟡 SẮP ĐẾN HẠN ≤${NV_SAP_DEN_HAN} NGÀY (${sapDen.length})`);
    sapDen.forEach((v, i) => L.push(`${i + 1}. ${v.viec}${_nvAi(v)} — còn ${v.con} ngày (${_nvFmt(v.han, 'dd/MM')})`));
  }

  const ct = _nvDocCongTrinh();
  if (ct.length) {
    L.push('', `🏗 CÔNG TRÌNH CHƯA XONG (${ct.length})`);
    ct.slice(0, 15).forEach((c, i) => L.push(`${i + 1}. ${c.ma_tram} – ${c.trang_thai}${c.ghi_chu ? ' – ' + c.ghi_chu : ''}`));
    if (ct.length > 15) L.push(`… và ${ct.length - 15} công trình khác (xem sheet ${SHEET_KE_HOACH})`);
  }

  if (!quaHan.length && !hanHomNay.length && !dinhKy.length && !sapDen.length && !ct.length) {
    L.push('', '✅ Không có việc tồn. Chúc anh em ngày làm việc hiệu quả!');
  }

  L.push('', `📋 Cập nhật "Xong" tại sheet ${NV_SHEET} để bot không nhắc lại.`);
  return L.join('\n');
}

// ============================================================
//  ĐỌC DỮ LIỆU
// ============================================================
// Cột sheet NhacViec: A Việc | B Người phụ trách | C Hạn | D Lặp lại | E Trạng thái | F Ghi chú
function _nvDocViec() {
  const sh = SpreadsheetApp.openById(SPREADSHEET_ID).getSheetByName(NV_SHEET);
  if (!sh) return [];
  const rows = sh.getDataRange().getValues().slice(1);
  return rows
    .filter(r => String(r[0]).trim() && !_nvDaXong(r[4]))
    .map(r => ({
      viec : String(r[0]).trim(),
      ai   : String(r[1] || '').trim(),
      han  : _nvNgay(r[2]),
      lap  : String(r[3] || '').trim()
    }));
}

function _nvDocCongTrinh() {
  const sh = SpreadsheetApp.openById(SPREADSHEET_ID).getSheetByName(SHEET_KE_HOACH);
  if (!sh) return [];
  return sh.getDataRange().getValues().slice(1)
    .filter(r => String(r[0]).trim() && !_nvDaXong(r[3]))
    .map(r => ({ ma_tram: String(r[0]), trang_thai: String(r[3] || 'Chưa rõ'), ghi_chu: String(r[4] || '') }));
}

function _nvDaXong(s) {
  return /^(xong|đã xong|hoàn thành|done|x|✓|✔)$/i.test(String(s || '').trim());
}

// "Lặp lại" chấp nhận: Hằng ngày | T2,T4,T6 | Ngày 5 | Ngày 5,20 | Cuối tháng
function _nvLapHomNay(lap, d) {
  const s = lap.toLowerCase().replace(/\s+/g, ' ').trim();
  if (/^(hằng|hàng|mỗi) ngày$/.test(s)) return true;
  if (s === 'cuối tháng') return new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate() === d.getDate();
  const ngay = s.match(/^ngày ([\d ,]+)$/);
  if (ngay) return ngay[1].split(',').map(x => Number(x.trim())).includes(d.getDate());
  const thu = s.toUpperCase().split(/[ ,]+/);
  return thu.includes(NV_THU[d.getDay()]);
}

// ============================================================
//  GỬI ZALO
// ============================================================
function _nvGuiZalo(text) {
  const chatId = PropertiesService.getScriptProperties().getProperty('ZALO_CHAT_ID');
  if (!chatId) throw new Error('Chưa có ZALO_CHAT_ID trong Script Properties — chạy nv_layChatId() trước.');
  _nvCatTin(text, NV_MAX_KY_TU).forEach(phan => _nvGoiApi('sendMessage', { chat_id: chatId, text: phan }));
}

function _nvGoiApi(method, payload) {
  const props = PropertiesService.getScriptProperties();
  const token = props.getProperty('ZALO_BOT_TOKEN');
  if (!token) throw new Error('Chưa có ZALO_BOT_TOKEN trong Script Properties.');
  const base  = (props.getProperty('ZALO_API_BASE') || NV_API_MAC_DINH).replace(/\/+$/, '');

  let lastErr;
  for (let lan = 1; lan <= 3; lan++) {
    try {
      const res  = UrlFetchApp.fetch(`${base}/bot${token}/${method}`, {
        method: 'post',
        contentType: 'application/json',
        payload: JSON.stringify(payload),
        muteHttpExceptions: true
      });
      const code = res.getResponseCode();
      const body = _safeJson(res.getContentText(), {});
      if (code === 200 && body.ok !== false) return body;
      lastErr = new Error(`Zalo ${method} lỗi HTTP ${code}: ${res.getContentText().slice(0, 300)}`);
      if (code >= 400 && code < 500) break;   // lỗi token/chat_id → thử lại vô ích
    } catch (e) {
      lastErr = e;
    }
    Utilities.sleep(2000 * lan);
  }
  throw lastErr;
}

// Cắt theo dòng để không vỡ giữa câu
function _nvCatTin(text, max) {
  const out = [];
  let cur = '';
  text.split('\n').forEach(line => {
    if (cur && (cur.length + 1 + line.length) > max) { out.push(cur); cur = ''; }
    cur = cur ? cur + '\n' + line : line;
    while (cur.length > max) { out.push(cur.slice(0, max)); cur = cur.slice(max); }
  });
  if (cur) out.push(cur);
  return out;
}

// ============================================================
//  TIỆN ÍCH
// ============================================================
// Ngày gửi mặc định T2–T7; đổi bằng Script Property NV_NGAY_GUI, VD "T2,T3,T4,T5,T6"
function _nvNgayDuocGui(now, props) {
  const cfg = (props.getProperty('NV_NGAY_GUI') || 'T2,T3,T4,T5,T6,T7').toUpperCase().split(/[ ,]+/);
  return cfg.includes(NV_THU[Number(_nvFmt(now, 'u')) % 7]);
}

function _nvTaoSheet() {
  const ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  if (ss.getSheetByName(NV_SHEET)) { Logger.log(`Sheet ${NV_SHEET} đã có, giữ nguyên.`); return; }
  const sh = ss.insertSheet(NV_SHEET);
  const hom = _nvDauNgay(new Date());
  const cong = n => new Date(hom.getTime() + n * 86400000);
  sh.getRange(1, 1, 1, 6).setValues([['Việc', 'Người phụ trách', 'Hạn (dd/mm/yyyy)', 'Lặp lại', 'Trạng thái', 'Ghi chú']])
    .setFontWeight('bold').setBackground('#fde68a');
  sh.getRange(2, 1, 6, 6).setValues([
    ['Họp giao ban đầu tuần',                         'Cả phòng', '',       'T2',         '', 'Ví dụ việc lặp theo thứ'],
    ['Chốt khối lượng thi công tháng trước',          'XLVT',     '',       'Ngày 3',     '', 'KPI: hoàn công 80% tháng N+1'],
    ['Rà soát hồ sơ hoàn công đạt 80% tháng N-1',     'XLVT',     '',       'Ngày 20,25', '', 'KPI hoàn công'],
    ['Tổng hợp ảnh bảo dưỡng FT gửi VTNET',           'HTCT',     cong(2),  '',           '', 'Ví dụ việc có hạn'],
    ['Đối soát chứng từ 4A/4B hợp đồng mẫu',          'Quyết toán', cong(-1), '',         '', 'Ví dụ việc quá hạn'],
    ['Nộp báo cáo tuần',                              'Cường',    '',       'T6',         '', '']
  ]);
  sh.getRange('C2:C').setNumberFormat('dd/mm/yyyy');
  sh.getRange('E2:E').setDataValidation(
    SpreadsheetApp.newDataValidation().requireValueInList(['', 'Xong'], true).setAllowInvalid(true).build());
  sh.setFrozenRows(1);
  sh.autoResizeColumns(1, 6);
  Logger.log(`✅ Tạo sheet ${NV_SHEET} với 6 dòng mẫu (xóa/sửa tùy ý).`);
}

function _nvAi(v)       { return v.ai ? ` [${v.ai}]` : ''; }
function _nvDauNgay(d)  { return new Date(d.getFullYear(), d.getMonth(), d.getDate()); }
function _nvFmt(d, f)   { return Utilities.formatDate(d, 'Asia/Ho_Chi_Minh', f); }

function _nvLaNgay(v) { return Object.prototype.toString.call(v) === '[object Date]' && !isNaN(v); }

function _nvNgay(v) {
  if (_nvLaNgay(v)) return v;
  const m = String(v || '').trim().match(/^(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4})$/);
  return m ? new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1])) : null;
}

// ── API cho máy tính cơ quan lấy nội dung: doGet?type=nhacviec&key=<NV_API_KEY> ──
// Web App để "Anyone" nên bắt buộc có key, tránh lộ danh sách việc ra ngoài.
function nv_apiNoiDung(key) {
  const dung = PropertiesService.getScriptProperties().getProperty('NV_API_KEY');
  if (!dung || key !== dung) return { ok: false, error: 'Sai hoặc thiếu key' };
  const now = new Date();
  return { ok: true, ngay: _nvFmt(now, 'yyyy-MM-dd'), gui_hom_nay: _nvNgayDuocGui(now, PropertiesService.getScriptProperties()),
           text: nv_taoNoiDung(now) };
}

// ── Tạo key ngẫu nhiên cho NV_API_KEY (chạy 1 lần, copy key vào config.json trên PC) ──
function nv_taoKey() {
  const key = Utilities.getUuid().replace(/-/g, '');
  PropertiesService.getScriptProperties().setProperty('NV_API_KEY', key);
  Logger.log('🔑 NV_API_KEY = ' + key + '  → dán vào zalo-pc/config.json');
  return key;
}

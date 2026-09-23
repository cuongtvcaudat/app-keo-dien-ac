// Test offline cho gas/NhacViec.js — giả lập Google Sheets + Zalo Bot API.
// Chạy: TZ=Asia/Ho_Chi_Minh node tests/nhacviec.test.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

function taoMoiTruong({ viec, congTrinh, props, zaloTraVe }) {
  const calls = [];
  const logs = [];
  const store = Object.assign({}, props);
  const sheet = rows => ({ getDataRange: () => ({ getValues: () => rows }) });
  const sheets = { NhacViec: sheet(viec), KeHoach_CongTrinh: sheet(congTrinh) };

  const ctx = {
    console,
    Logger: { log: s => logs.push(String(s)) },
    SpreadsheetApp: { openById: () => ({ getSheetByName: n => sheets[n] || null }) },
    PropertiesService: { getScriptProperties: () => ({
      getProperty: k => (k in store ? store[k] : null),
      setProperty: (k, v) => { store[k] = v; }
    }) },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock: () => {} }) },
    UrlFetchApp: { fetch: (url, opt) => {
      calls.push({ url, body: JSON.parse(opt.payload) });
      const [code, body] = zaloTraVe ? zaloTraVe(calls.length) : [200, { ok: true, result: { message_id: 'm' + calls.length } }];
      return { getResponseCode: () => code, getContentText: () => JSON.stringify(body) };
    } },
    Utilities: {
      sleep: () => {},
      formatDate: (d, tz, f) => {
        const p = Object.fromEntries(new Intl.DateTimeFormat('en-GB', { timeZone: tz, year: 'numeric', month: '2-digit',
          day: '2-digit', hour: '2-digit', minute: '2-digit', weekday: 'short', hourCycle: 'h23' })
          .formatToParts(d).map(x => [x.type, x.value]));
        const u = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 }[p.weekday];
        return f.replace('yyyy', p.year).replace('MM', p.month).replace('dd', p.day)
                .replace(/^H$/, String(Number(p.hour))).replace(/^u$/, String(u));
      }
    }
  };
  vm.createContext(ctx);
  // Code.js cung cấp SPREADSHEET_ID, SHEET_KE_HOACH, _safeJson (cùng project GAS)
  for (const f of ['Code.js', 'NhacViec.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'gas', f), 'utf8'), ctx, { filename: f });
  }
  return { ctx, calls, logs, store };
}

const D = (y, m, d) => new Date(y, m - 1, d);
const HEADER = ['Việc', 'Người', 'Hạn', 'Lặp', 'Trạng thái', 'Ghi chú'];
const VIEC = [HEADER,
  ['Đối soát 4A/4B HĐ-2026-001', 'Quyết toán', D(2026, 9, 20), '', '', ''],
  ['Nộp hồ sơ bảo dưỡng VTNET', 'HTCT', D(2026, 9, 23), '', '', ''],
  ['Hoàn công 80% tháng 8', 'XLVT', '25/09/2026', '', '', ''],
  ['Việc còn xa', '', D(2026, 10, 30), '', '', ''],
  ['Việc đã xong', '', D(2026, 9, 1), '', 'Xong', ''],
  ['Họp giao ban', 'Cả phòng', '', 'T4', '', ''],
  ['Chốt KL', '', '', 'Ngày 5,23', '', ''],
  ['Họp thứ Hai', '', '', 'T2', '', '']
];
const CT = [['ma_tram', 'hd', 'ten', 'trang_thai', 'ghi_chu'],
  ['LDG_TA_NUNG', 'HD-1', 'x', 'Đang thi công', 'QL27C'],
  ['LDG_DA_LOAN', 'HD-2', 'x', 'Hoàn thành', ''],
];
const PROPS = { ZALO_BOT_TOKEN: 'TEST_TOKEN', ZALO_CHAT_ID: 'GROUP_123' };

let pass = 0;
const test = (ten, fn) => { fn(); pass++; console.log('✔', ten); };

// Thứ Tư 23/09/2026 08:02 giờ VN
const SANG_T4 = new Date('2026-09-23T08:02:00+07:00');

test('Nội dung phân loại đúng quá hạn / hôm nay / định kỳ / sắp đến hạn / công trình', () => {
  const { ctx } = taoMoiTruong({ viec: VIEC, congTrinh: CT, props: PROPS });
  const s = ctx.nv_taoNoiDung(SANG_T4);
  console.log('\n----- TIN NHẮN MẪU -----\n' + s + '\n------------------------\n');
  assert.match(s, /NHẮC VIỆC THỨ TƯ 23\/09\/2026/);
  assert.match(s, /QUÁ HẠN \(1\)[\s\S]*Đối soát 4A\/4B.*trễ 3 ngày/);
  assert.match(s, /HẠN HÔM NAY \(1\)[\s\S]*Nộp hồ sơ bảo dưỡng/);
  assert.match(s, /ĐỊNH KỲ \(2\)[\s\S]*Họp giao ban[\s\S]*Chốt KL/);
  assert.match(s, /SẮP ĐẾN HẠN.*\(1\)[\s\S]*Hoàn công 80%.*còn 2 ngày/);
  assert.match(s, /CÔNG TRÌNH CHƯA XONG \(1\)[\s\S]*LDG_TA_NUNG/);
  assert.doesNotMatch(s, /Việc đã xong|Việc còn xa|Họp thứ Hai|LDG_DA_LOAN/);
});

test('Trigger 8h02 gửi đúng 1 lần, gọi đúng endpoint + chat_id', () => {
  const env = taoMoiTruong({ viec: VIEC, congTrinh: CT, props: PROPS });
  const RealDate = Date;
  env.ctx.Date = class extends RealDate { constructor(...a) { super(...(a.length ? a : [SANG_T4])); } };
  vm.runInContext('Date = this.Date', env.ctx);
  env.ctx.nv_kiemTraVaGui();
  env.ctx.nv_kiemTraVaGui();              // lần 2 trong cùng ngày → không gửi lại
  assert.strictEqual(env.calls.length, 1);
  assert.strictEqual(env.calls[0].url, 'https://bot-api.zapps.me/botTEST_TOKEN/sendMessage');
  assert.strictEqual(env.calls[0].body.chat_id, 'GROUP_123');
  assert.strictEqual(env.store.NV_DA_GUI, '2026-09-23');
});

test('Trước 8h, sau 10h và Chủ nhật thì không gửi', () => {
  for (const t of ['2026-09-23T07:55:00+07:00', '2026-09-23T10:05:00+07:00', '2026-09-27T08:05:00+07:00']) {
    const env = taoMoiTruong({ viec: VIEC, congTrinh: CT, props: PROPS });
    const RealDate = Date, T = new RealDate(t);
    env.ctx.Date = class extends RealDate { constructor(...a) { super(...(a.length ? a : [T])); } };
    vm.runInContext('Date = this.Date', env.ctx);
    env.ctx.nv_kiemTraVaGui();
    assert.strictEqual(env.calls.length, 0, 'không được gửi lúc ' + t);
  }
});

test('Tin dài được cắt thành nhiều tin ≤ 1800 ký tự', () => {
  const nhieu = [HEADER, ...Array.from({ length: 80 }, (_, i) => [`Việc số ${i} ` + 'x'.repeat(40), 'A', D(2026, 9, 23), '', '', ''])];
  const env = taoMoiTruong({ viec: nhieu, congTrinh: CT, props: PROPS });
  env.ctx.nv_guiNhacViec(SANG_T4);
  assert.ok(env.calls.length > 1);
  env.calls.forEach(c => assert.ok(c.body.text.length <= 1800));
});

test('Token sai (HTTP 401) → báo lỗi rõ ràng, không thử lại', () => {
  const env = taoMoiTruong({ viec: VIEC, congTrinh: CT, props: PROPS,
    zaloTraVe: () => [401, { ok: false, description: 'Unauthorized' }] });
  assert.throws(() => env.ctx.nv_guiNhacViec(SANG_T4), /HTTP 401/);
  assert.strictEqual(env.calls.length, 1);
});

test('Thiếu ZALO_CHAT_ID → báo hướng dẫn chạy nv_layChatId()', () => {
  const env = taoMoiTruong({ viec: VIEC, congTrinh: CT, props: { ZALO_BOT_TOKEN: 'x' } });
  assert.throws(() => env.ctx.nv_guiNhacViec(SANG_T4), /nv_layChatId/);
});

test('nv_layChatId đọc được chat_id nhóm từ getUpdates', () => {
  const env = taoMoiTruong({ viec: VIEC, congTrinh: CT, props: { ZALO_BOT_TOKEN: 'x' },
    zaloTraVe: () => [200, { ok: true, result: { message: { text: 'hi', from: { display_name: 'Cường' },
      chat: { id: 'G999', chat_type: 'GROUP' } } } }] });
  const out = env.ctx.nv_layChatId();
  assert.strictEqual(out[0].chat_id, 'G999');
  assert.ok(env.calls[0].url.endsWith('/getUpdates'));
});

console.log(`\n${pass} test đạt.`);

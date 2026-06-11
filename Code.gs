// ============================================================
//  Code.gs — App Kéo Điện AC
//  Deploy: Extensions → Apps Script → Deploy → Web App
//         Execute as: Me | Who has access: Anyone
//
//  Luồng dữ liệu:
//    doGet?type=congtrinh  → app đọc danh sách công trình
//    doGet?type=vattu      → app đọc danh mục vật tư
//    doGet?type=tuyen&ma_tram=X&ma_doi=Y → app tải dữ liệu tuyến
//    doPost {action:save_tuyen,...}       → app lưu toàn bộ tuyến
// ============================================================

const SHEET_DANH_MUC = 'DanhMuc_VatTu';
const SHEET_KE_HOACH = 'KeHoach_CongTrinh';
const SHEET_TUYEN    = 'TuyenDien_Data';

// ── doGet ───────────────────────────────────────────────────
function doGet(e) {
  const type = e?.parameter?.type || '';
  try {
    if (type === 'vattu')     return _json({ ok:true, data: _getVatTu() });
    if (type === 'congtrinh') return _json({ ok:true, data: _getCongTrinh() });
    if (type === 'tuyen')     return _json({ ok:true, data: _getTuyen(e.parameter.ma_tram, e.parameter.ma_doi) });
    return _json({ ok:false, error: 'Unknown type: ' + type });
  } catch(err) {
    return _json({ ok:false, error: err.message });
  }
}

// ── doPost ──────────────────────────────────────────────────
function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    if (body.action === 'save_tuyen') return _saveTuyen(body);
    return _json({ ok:false, error: 'Unknown action: ' + body.action });
  } catch(err) {
    return _json({ ok:false, error: err.message });
  }
}

// ── Đọc danh mục vật tư ─────────────────────────────────────
function _getVatTu() {
  const sh   = _getOrCreate(SHEET_DANH_MUC);
  const rows = sh.getDataRange().getValues();
  if (rows.length < 2) return [];
  const [, ...data] = rows;
  return data
    .filter(r => r[0])
    .map(r => ({
      ma_vt      : String(r[0]),
      ten_vt     : String(r[1]),
      don_vi     : String(r[2]),
      nhom       : String(r[3]),
      mac_dinh   : String(r[4]),
      sl_mac_dinh: String(r[5])
    }));
}

// ── Đọc danh sách công trình ─────────────────────────────────
function _getCongTrinh() {
  const sh   = _getOrCreate(SHEET_KE_HOACH);
  const rows = sh.getDataRange().getValues();
  if (rows.length < 2) return [];
  const [, ...data] = rows;
  return data
    .filter(r => r[0])
    .map(r => ({
      ma_tram        : String(r[0]),
      ma_hop_dong    : String(r[1]),
      ten_cong_trinh : String(r[2]),
      trang_thai     : String(r[3]),
      ghi_chu        : String(r[4])
    }));
}

// ── Đọc tuyến theo mã trạm + mã đội ─────────────────────────
function _getTuyen(maTram, maDoi) {
  if (!maTram || !maDoi) return null;
  const sh = _getOrCreate(SHEET_TUYEN);
  if (sh.getLastRow() < 2) return null;
  const rows = sh.getDataRange().getValues();
  const [, ...data] = rows;
  const row = data.find(r => String(r[0]) === maTram && String(r[1]) === maDoi);
  if (!row) return null;
  return {
    ma_tram            : String(row[0]),
    ma_doi             : String(row[1]),
    ten_doi            : String(row[2]),
    loai_tuyen         : String(row[3]),
    loai_day           : String(row[4]),
    diem               : _safeJson(row[5], []),
    thoi_gian_cap_nhat : String(row[6])
  };
}

// ── Lưu / cập nhật tuyến (upsert) ───────────────────────────
function _saveTuyen(body) {
  const sh = _getOrCreate(SHEET_TUYEN);
  if (sh.getLastRow() === 0) {
    sh.appendRow(['ma_tram','ma_doi','ten_doi','loai_tuyen','loai_day',
                  'diem_json','thoi_gian_cap_nhat','so_diem']);
    sh.getRange(1, 1, 1, 8).setFontWeight('bold');
  }

  const maTram = String(body.ma_tram || '');
  const maDoi  = String(body.ma_doi  || '');
  if (!maTram || !maDoi) return _json({ ok:false, error: 'Thiếu ma_tram hoặc ma_doi' });

  const newRow = [
    maTram,
    maDoi,
    body.ten_doi  || '',
    body.loai_tuyen || '',
    body.loai_day   || '',
    JSON.stringify(body.diem || []),
    body.thoi_gian_cap_nhat || new Date().toISOString(),
    (body.diem || []).length
  ];

  // Tìm dòng cũ để update
  if (sh.getLastRow() >= 2) {
    const rows = sh.getDataRange().getValues();
    for (let i = 1; i < rows.length; i++) {
      if (String(rows[i][0]) === maTram && String(rows[i][1]) === maDoi) {
        sh.getRange(i + 1, 1, 1, newRow.length).setValues([newRow]);
        SpreadsheetApp.flush();
        return _json({ ok:true, action:'updated', so_diem: newRow[7] });
      }
    }
  }

  sh.appendRow(newRow);
  SpreadsheetApp.flush();
  return _json({ ok:true, action:'inserted', so_diem: newRow[7] });
}

// ── Helpers ─────────────────────────────────────────────────
function _getOrCreate(name) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(name) || ss.insertSheet(name);
}

function _json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function _safeJson(str, fallback) {
  try { return JSON.parse(str); } catch { return fallback; }
}

// ── setupSheets() — Admin chạy 1 lần sau khi tạo file GS ────
// Extensions → Apps Script → Run → setupSheets
function setupSheets() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();

  // 1. DanhMuc_VatTu
  const dm = _getOrCreate(SHEET_DANH_MUC);
  if (dm.getLastRow() === 0) {
    dm.appendRow(['ma_vt','ten_vt','don_vi','nhom','mac_dinh','sl_mac_dinh']);
    dm.getRange(1, 1, 1, 6).setFontWeight('bold');
    [
      ['DAY_MULLER_2x16','Dây Muller 2x16','m','Dây dẫn','',0],
      ['DAY_MULLER_4x16','Dây Muller 4x16','m','Dây dẫn','',0],
      ['KEP_NGUNG','Kẹp ngừng','cái','Phụ kiện','',1],
      ['GHI_NHOM','Ghíp nhôm','cái','Phụ kiện','x',2],
      ['SU_ONG_CHI','Sứ ống chỉ','cái','Phụ kiện','',0],
      ['XA_SAT','Xà sắt','cái','Phụ kiện','',0],
      ['NEO_TANG_DO','Dây néo + tăng đơ','bộ','Néo chằng','',0],
      ['MONG_NEO','Móng néo','cái','Néo chằng','',0],
      ['COC_TIEP_DIA','Cọc tiếp địa V63','cái','Tiếp địa','',0],
      ['DAY_TIEP_DIA','Dây tiếp địa','m','Tiếp địa','',0],
      ['BANG_KEO','Băng keo điện','cuộn','Phụ kiện','x',1],
      ['ONG_NHUA','Ống nhựa luồn cáp','m','Phụ kiện','',0],
      ['APTMT_HOP','Aptomat + hộp công tơ','bộ','Công tơ','',1],
    ].forEach(r => dm.appendRow(r));
    Logger.log('✅ Tạo sheet DanhMuc_VatTu xong');
  }

  // 2. KeHoach_CongTrinh
  const kh = _getOrCreate(SHEET_KE_HOACH);
  if (kh.getLastRow() === 0) {
    kh.appendRow(['ma_tram','ma_hop_dong','ten_cong_trinh','trang_thai','ghi_chu']);
    kh.getRange(1, 1, 1, 5).setFontWeight('bold');
    // Xóa dòng mẫu khi dùng thật
    kh.appendRow(['LDG_TEST01','HD-2026-001','Kéo AC trạm Tà Nung','Đang thi công','Mẫu — xóa đi khi dùng thật']);
    Logger.log('✅ Tạo sheet KeHoach_CongTrinh xong');
  }

  // 3. TuyenDien_Data — app tự ghi, admin chỉ đọc
  const td = _getOrCreate(SHEET_TUYEN);
  if (td.getLastRow() === 0) {
    td.appendRow(['ma_tram','ma_doi','ten_doi','loai_tuyen','loai_day',
                  'diem_json','thoi_gian_cap_nhat','so_diem']);
    td.getRange(1, 1, 1, 8).setFontWeight('bold');
    Logger.log('✅ Tạo sheet TuyenDien_Data xong');
  }

  Logger.log('🎉 setupSheets() hoàn tất. Deploy Web App rồi mới dùng app.');
}

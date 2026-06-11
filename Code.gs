// ============================================================
//  Code.gs — App Kéo Điện AC
//  Deploy: Extensions → Apps Script → Deploy → Web App
//         Execute as: Me | Who has access: Anyone
// ============================================================

const SHEET_DANH_MUC  = 'DanhMuc_VatTu';
const SHEET_KE_HOACH  = 'KeHoach_KPI';
const SHEET_HT        = 'DuLieu_HienTruong';

// ── doGet — trả JSON danh mục / công trình ─────────────────
function doGet(e) {
  const type = e?.parameter?.type || '';
  try {
    if (type === 'vattu')      return _json({ ok:true, data: _getVatTu() });
    if (type === 'congtrinh')  return _json({ ok:true, data: _getCongTrinh() });
    if (type === 'hientruong') return _json({ ok:true, data: _getHienTruong(e.parameter.ma_tram) });
    return _json({ ok:false, error:'Unknown type: ' + type });
  } catch(err) {
    return _json({ ok:false, error: err.message });
  }
}

// ── doPost — ghi điểm hiện trường (append-only) ────────────
function doPost(e) {
  try {
    const records = JSON.parse(e.postData.contents);
    const arr     = Array.isArray(records) ? records : [records];
    const sh      = _getOrCreate(SHEET_HT);
    if (sh.getLastRow() === 0) {
      sh.appendRow(['MaTram','MaHopDong','TenDoi','LoaiDiem','ChuSoHuu',
                    'STT','Lat','Lng','SaiSoGPS','ChamBu',
                    'VatTu(JSON)','GhiChu','ThoiGian','ThoiGianDongBo']);
    }
    const now = new Date().toISOString();
    arr.forEach(r => {
      sh.appendRow([
        r.ma_tram || '', r.ma_hop_don || '', r.ten_doi || '',
        r.loai_diem || '', r.chu_so_huu || '', r.stt || '',
        r.lat || 0, r.lng || 0, r.sai_so_gps || 0,
        r.cham_bu ? 'TRUE' : 'FALSE',
        r.vat_tu || '[]', r.ghi_chu || '',
        r.thoi_gian || now, now
      ]);
    });
    SpreadsheetApp.flush();
    return _json({ ok:true, count: arr.length });
  } catch(err) {
    return _json({ ok:false, error: err.message });
  }
}

// ── Helpers ─────────────────────────────────────────────────
function _getVatTu() {
  const sh   = _getOrCreate(SHEET_DANH_MUC);
  const rows = sh.getDataRange().getValues();
  if (rows.length < 2) return [];
  const [h, ...data] = rows;
  return data.map(r => ({
    ma_vt       : r[0], ten_vt    : r[1], don_vi  : r[2],
    nhom        : r[3], mac_dinh  : r[4], sl_mac_dinh: r[5]
  }));
}

function _getCongTrinh() {
  const sh   = _getOrCreate(SHEET_KE_HOACH);
  const rows = sh.getDataRange().getValues();
  if (rows.length < 2) return [];
  const [h, ...data] = rows;
  return data.map(r => ({
    ma_tram: r[0], ma_hop_dong: r[1], ten_cong_trinh: r[2],
    trang_thai: r[3], ghi_chu: r[4]
  }));
}

function _getHienTruong(maTram) {
  const sh   = _getOrCreate(SHEET_HT);
  const rows = sh.getDataRange().getValues();
  if (rows.length < 2) return [];
  const [h, ...data] = rows;
  return data
    .filter(r => !maTram || r[0] === maTram)
    .map(r => ({
      ma_tram: r[0], ma_hop_don: r[1], ten_doi: r[2],
      loai_diem: r[3], chu_so_huu: r[4], stt: r[5],
      lat: r[6], lng: r[7], sai_so_gps: r[8], cham_bu: r[9],
      vat_tu: r[10], ghi_chu: r[11], thoi_gian: r[12]
    }));
}

function _getOrCreate(name) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(name) || ss.insertSheet(name);
}

function _json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

// ── setupSheets() — Chạy 1 lần sau khi deploy ───────────────
function setupSheets() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();

  // DanhMuc_VatTu
  const dm = _getOrCreate(SHEET_DANH_MUC);
  if (dm.getLastRow() === 0) {
    dm.appendRow(['ma_vt','ten_vt','don_vi','nhom','mac_dinh','sl_mac_dinh']);
    const vtData = [
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
    ];
    vtData.forEach(r => dm.appendRow(r));
  }

  // KeHoach_KPI
  const kh = _getOrCreate(SHEET_KE_HOACH);
  if (kh.getLastRow() === 0) {
    kh.appendRow(['ma_tram','ma_hop_dong','ten_cong_trinh','trang_thai','ghi_chu']);
    kh.appendRow(['LDG_TEST01','HD-2026-001','Kéo AC trạm Tà Nung','Đang thi công','Tuyến 1 pha dọc QL27C']);
    kh.appendRow(['LDG_TEST02','HD-2026-002','Kéo AC trạm Đà Loan','Chờ thi công','Tuyến 3 pha khu công nghiệp']);
    kh.appendRow(['LDG_TEST03','HD-2026-003','Kéo AC trạm Phi Liêng','Hoàn thành','Tuyến 1 pha vùng núi']);
  }

  Logger.log('setupSheets() hoàn tất.');
}

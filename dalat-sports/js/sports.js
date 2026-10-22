// Danh mục 21 bộ môn theo cột "CODE MÔN" của file Excel gốc.
// group: nhóm môn để lọc nhanh; color: màu nền icon (đủ tương phản với chữ/icon trắng).
window.SPORTS = [
  { code: 'BDA',     name: 'Bóng đá',               icon: '⚽', color: '#0B4D3B', group: 'Bóng' },
  { code: 'BRO',     name: 'Bóng rổ',               icon: '🏀', color: '#FF6B35', group: 'Bóng' },
  { code: 'CLONG',   name: 'Cầu lông',              icon: '🏸', color: '#1E90FF', group: 'Bóng' },
  { code: 'BBAN',    name: 'Bóng bàn',              icon: '🏓', color: '#E5484D', group: 'Bóng' },
  { code: 'DKINH',   name: 'Điền kinh',             icon: '🏃', color: '#F5A700', group: 'Cơ bản' },
  { code: 'BLAN',    name: 'Bơi lặn',               icon: '🏊', color: '#0091D5', group: 'Dưới nước' },
  { code: 'CANOE',   name: 'Canoeing',              icon: '🛶', color: '#0FA97A', group: 'Dưới nước' },
  { code: 'ĐTTT',    name: 'Đua thuyền truyền thống', icon: '🚣', color: '#0E6B51', group: 'Dưới nước' },
  { code: 'TAEK',    name: 'Taekwondo',             icon: '🥋', color: '#2F54EB', group: 'Võ thuật' },
  { code: 'JUDO',    name: 'Judo',                  icon: '🥋', color: '#1F2937', group: 'Võ thuật' },
  { code: 'KARATE',  name: 'Karate',                icon: '🥋', color: '#B91C1C', group: 'Võ thuật' },
  { code: 'VOVINAM', name: 'Vovinam',               icon: '🥋', color: '#1D4ED8', group: 'Võ thuật' },
  { code: 'VCT',     name: 'Võ cổ truyền',          icon: '🐉', color: '#C2410C', group: 'Võ thuật' },
  { code: 'JUJITSU', name: 'Jujitsu',               icon: '🤼', color: '#6D28D9', group: 'Võ thuật' },
  { code: 'VAT',     name: 'Vật',                   icon: '🤼', color: '#7C3AED', group: 'Võ thuật' },
  { code: 'BOXING',  name: 'Boxing',                icon: '🥊', color: '#DC2626', group: 'Võ thuật' },
  { code: 'CTA',     name: 'Cử tạ',                 icon: '🏋️', color: '#374151', group: 'Sức mạnh' },
  { code: 'THEH',    name: 'Thể hình',              icon: '💪', color: '#9A3412', group: 'Sức mạnh' },
  { code: 'DGAY',    name: 'Đẩy gậy',               icon: '🪵', color: '#854D0E', group: 'Sức mạnh' },
  { code: 'CVUA',    name: 'Cờ vua',                icon: '♟️', color: '#111827', group: 'Trí tuệ' },
  { code: 'KVTT',    name: 'Khiêu vũ thể thao',     icon: '💃', color: '#DB2777', group: 'Nghệ thuật' },
];
window.TIERS = ['Tuyển', 'Trẻ', 'Năng khiếu'];
window.LEVELS = ['Quốc tế', 'Quốc gia', 'Khu vực', 'Tỉnh', 'Thành phố'];
window.MEDALS = [
  { key: 'HCV', label: 'Vàng', cls: 'g' },
  { key: 'HCB', label: 'Bạc', cls: 's' },
  { key: 'HCĐ', label: 'Đồng', cls: 'b' },
  { key: 'KHC', label: 'Không HC', cls: '' },
];

// sw.js — Service Worker: offline-first + cache tile bản đồ
const VERSION     = 'dien-ac-v1';
const SHELL_CACHE = VERSION + '-shell';
const TILE_CACHE  = VERSION + '-tiles';
const MAX_TILES   = 500;

const SHELL_FILES = [
  './index.html',
  './manifest.json'
];

// ── Install: cache app shell ──────────────────────────────────
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(SHELL_CACHE)
      .then(c => c.addAll(SHELL_FILES))
      .then(() => self.skipWaiting())
  );
});

// ── Activate: xóa cache cũ ───────────────────────────────────
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys
        .filter(k => k !== SHELL_CACHE && k !== TILE_CACHE)
        .map(k => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

// ── Fetch strategy ────────────────────────────────────────────
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);

  // Tile OSM: cache-first, giới hạn MAX_TILES
  if (url.hostname.includes('tile.openstreetmap.org') ||
      url.hostname.includes('tile.osm.org')) {
    e.respondWith(tileStrategy(e.request));
    return;
  }

  // App shell: cache-first
  if (SHELL_FILES.some(f => e.request.url.includes(f.replace('./', '')))) {
    e.respondWith(
      caches.match(e.request).then(r => r || fetch(e.request))
    );
    return;
  }

  // API calls (GAS/mock): network-first, không cache
  e.respondWith(fetch(e.request).catch(() => new Response(
    JSON.stringify({ ok: false, error: 'Offline' }),
    { headers: { 'Content-Type': 'application/json' } }
  )));
});

async function tileStrategy(request) {
  const cache = await caches.open(TILE_CACHE);
  const cached = await cache.match(request);
  if (cached) return cached;

  try {
    const response = await fetch(request);
    if (response.ok) {
      // Giới hạn số tile
      const keys = await cache.keys();
      if (keys.length >= MAX_TILES) {
        await cache.delete(keys[0]);
      }
      cache.put(request, response.clone());
    }
    return response;
  } catch {
    return new Response('', { status: 503 });
  }
}

// ── Message: pre-cache vùng bản đồ ───────────────────────────
self.addEventListener('message', async e => {
  if (e.data.type !== 'PRECACHE_TILES') return;

  const { lat, lng, radiusKm = 3, minZoom = 14, maxZoom = 17 } = e.data;
  const tiles = getTilesInBbox(lat, lng, radiusKm, minZoom, maxZoom);
  const cache = await caches.open(TILE_CACHE);

  let done = 0;
  const client = await self.clients.get(e.source.id);

  for (const url of tiles) {
    try {
      const exists = await cache.match(url);
      if (!exists) {
        const res = await fetch(url);
        if (res.ok) {
          const keys = await cache.keys();
          if (keys.length >= MAX_TILES) await cache.delete(keys[0]);
          await cache.put(url, res);
        }
      }
      done++;
      if (client && done % 10 === 0) {
        client.postMessage({ type: 'PRECACHE_PROGRESS', done, total: tiles.length });
      }
    } catch { done++; }
  }

  if (client) client.postMessage({ type: 'PRECACHE_DONE', total: tiles.length });
});

// ── Tính danh sách tile trong bounding box ───────────────────
function getTilesInBbox(lat, lng, radiusKm, minZoom, maxZoom) {
  const R    = 6371;
  const dLat = (radiusKm / R) * (180 / Math.PI);
  const dLng = dLat / Math.cos(lat * Math.PI / 180);

  const bounds = {
    minLat: lat - dLat, maxLat: lat + dLat,
    minLng: lng - dLng, maxLng: lng + dLng
  };

  const urls = [];
  for (let z = minZoom; z <= maxZoom; z++) {
    const minX = lng2tile(bounds.minLng, z);
    const maxX = lng2tile(bounds.maxLng, z);
    const minY = lat2tile(bounds.maxLat, z);
    const maxY = lat2tile(bounds.minLat, z);
    for (let x = minX; x <= maxX; x++) {
      for (let y = minY; y <= maxY; y++) {
        urls.push(`https://tile.openstreetmap.org/${z}/${x}/${y}.png`);
      }
    }
  }
  return urls;
}

function lng2tile(lng, z) { return Math.floor((lng + 180) / 360 * Math.pow(2, z)); }
function lat2tile(lat, z) {
  return Math.floor((1 - Math.log(Math.tan(lat * Math.PI / 180) + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * Math.pow(2, z));
}

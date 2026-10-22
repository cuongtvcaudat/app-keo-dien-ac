// Cache offline cho dùng ngoài sân tập (mạng yếu)
const CACHE = 'dalat-sports-v1';
const ASSETS = ['./', './index.html', './css/app.css', './js/sports.js', './js/seed-data.js', './js/app.js', './icon.svg', './icon-pines.svg', './manifest.json', './js/vendor/xlsx.full.min.js'];
self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS))));
self.addEventListener('activate', e => e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))));
self.addEventListener('fetch', e => e.respondWith(caches.match(e.request).then(r => r || fetch(e.request))));

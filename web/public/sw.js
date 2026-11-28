const STATIC_CACHE = 'sora-static-v2';
const SHELL_CACHE = 'sora-shell-v2';
const APP_SHELL = ['/index.html', '/manifest.webmanifest'];
const MAX_STATIC_ENTRIES = 60;

async function trimCache(cacheName, maxItems) {
  const cache = await caches.open(cacheName);
  const keys = await cache.keys();
  if (keys.length > maxItems) {
    await Promise.all(keys.slice(0, keys.length - maxItems).map((k) => cache.delete(k)));
  }
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(APP_SHELL)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  const keep = new Set([STATIC_CACHE, SHELL_CACHE]);
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => !keep.has(k)).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;
  if (url.searchParams.has('key') || url.searchParams.has('token') || url.searchParams.has('auth')) return;

  // Network-first for HTML navigations to prevent stale app builds
  if (event.request.mode === 'navigate' || url.pathname === '/' || url.pathname.endsWith('.html')) {
    event.respondWith(
      fetch(event.request)
        .then((resp) => {
          if (resp.ok && resp.type === 'basic') {
            const copy = resp.clone();
            caches.open(SHELL_CACHE).then((c) => c.put('/index.html', copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => caches.match('/index.html'))
    );
    return;
  }

  // Bounded cache-first only for static build assets and manifest
  const isImmutableAsset =
    url.pathname.startsWith('/assets/') ||
    url.pathname === '/manifest.webmanifest' ||
    /\.(js|css|svg|png|webp|woff2)$/.test(url.pathname);
  if (!isImmutableAsset) return;

  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request).then((resp) => {
        if (resp.ok && resp.type === 'basic') {
          const copy = resp.clone();
          caches.open(STATIC_CACHE).then(async (cache) => {
            await cache.put(event.request, copy);
            await trimCache(STATIC_CACHE, MAX_STATIC_ENTRIES);
          }).catch(() => {});
        }
        return resp;
      });
    })
  );
});

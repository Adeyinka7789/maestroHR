const CACHE_NAME = 'maestrohr-v1';
const STATIC_ASSETS = [
  '/css/dashboard.css',
  '/css/layout.css',
  '/js/layout.js',
  '/js/onboarding-wizard.js',
  '/images/logo-sq.png',
  '/offline.html'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(STATIC_ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET' && !isAttendanceEndpoint(event.request.url)) {
    return;
  }

  const url = new URL(event.request.url);

  if (/^\/(css|js|images|fonts)\//.test(url.pathname)) {
    event.respondWith(cacheFirst(event.request));
    return;
  }

  if (url.pathname.startsWith('/htmx/')) {
    event.respondWith(networkFirstWithTimeout(event.request));
    return;
  }

  if (isAttendanceEndpoint(event.request.url)) {
    event.respondWith(attendanceWithQueue(event.request));
    return;
  }

  if (url.pathname.startsWith('/api/')) {
    event.respondWith(networkOnly(event.request));
    return;
  }
});

function isAttendanceEndpoint(url) {
  const path = new URL(url).pathname;
  return path === '/api/attendance/check-in' || path === '/api/attendance/check-out';
}

async function cacheFirst(request) {
  const cached = await caches.match(request);
  if (cached) {
    fetch(request).then(response => {
      if (response.ok) caches.open(CACHE_NAME).then(c => c.put(request, response));
    }).catch(() => {});
    return cached;
  }
  try {
    const response = await fetch(request);
    if (response.ok) {
      const cache = await caches.open(CACHE_NAME);
      cache.put(request, response.clone());
    }
    return response;
  } catch {
    return caches.match('/offline.html');
  }
}

async function networkFirstWithTimeout(request) {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);
    const response = await fetch(request, { signal: controller.signal });
    clearTimeout(timeout);
    if (response.ok) {
      const cache = await caches.open(CACHE_NAME);
      cache.put(request, response.clone());
    }
    return response;
  } catch (error) {
    // Only show offline page if genuinely offline
    if (!navigator.onLine) {
      const cached = await caches.match(request);
      if (cached) notifyClients({ type: 'SERVING_FROM_CACHE', url: request.url });
      return cached || caches.match('/offline.html');
    }
    // Online but request failed (timeout, server error) - try cache, else rethrow
    const cached = await caches.match(request);
    if (cached) {
      notifyClients({ type: 'SERVING_FROM_CACHE', url: request.url });
      return cached;
    }
    throw error;
  }
}

async function networkOnly(request) {
  try {
    return await fetch(request);
  } catch {
    return new Response(
      JSON.stringify({ offline: true, error: 'No network connection' }),
      { status: 503, headers: { 'Content-Type': 'application/json' } }
    );
  }
}

async function attendanceWithQueue(request) {
  try {
    return await fetch(request.clone());
  } catch {
    await queueAttendanceRequest(request);
    try {
      await self.registration.sync.register('attendance-sync');
    } catch {
      /* Background Sync API not supported */
    }
    return new Response(
      JSON.stringify({ queued: true, message: 'Attendance will sync when you reconnect' }),
      { status: 202, headers: { 'Content-Type': 'application/json' } }
    );
  }
}

self.addEventListener('sync', event => {
  if (event.tag === 'attendance-sync') {
    event.waitUntil(syncAttendance());
  }
});

function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('maestrohr-offline', 1);
    req.onupgradeneeded = e => {
      e.target.result.createObjectStore('attendance-queue', { keyPath: 'id', autoIncrement: true });
    };
    req.onsuccess = e => resolve(e.target.result);
    req.onerror = e => reject(e.target.error);
  });
}

async function queueAttendanceRequest(request) {
  const db = await openDB();
  const body = request.method !== 'GET' ? await request.text() : null;
  const tx = db.transaction('attendance-queue', 'readwrite');
  tx.objectStore('attendance-queue').add({
    url: request.url,
    method: request.method,
    body,
    headers: [...request.headers.entries()],
    timestamp: Date.now()
  });
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = e => reject(e.target.error);
  });
}

async function syncAttendance() {
  const db = await openDB();
  const queue = await new Promise((resolve, reject) => {
    const tx = db.transaction('attendance-queue', 'readonly');
    const req = tx.objectStore('attendance-queue').getAll();
    req.onsuccess = e => resolve(e.target.result);
    req.onerror = e => reject(e.target.error);
  });

  for (const item of queue) {
    try {
      const headers = new Headers(item.headers);
      const response = await fetch(item.url, {
        method: item.method,
        headers,
        body: item.body || undefined
      });
      if (response.ok) {
        const delTx = db.transaction('attendance-queue', 'readwrite');
        delTx.objectStore('attendance-queue').delete(item.id);
        await new Promise((resolve, reject) => {
          delTx.oncomplete = resolve;
          delTx.onerror = e => reject(e.target.error);
        });
        notifyClients({ type: 'ATTENDANCE_SYNCED', url: item.url });
      }
    } catch {
      /* keep in queue, retry on next sync */
    }
  }
}

async function notifyClients(message) {
  const clients = await self.clients.matchAll({ includeUncontrolled: true });
  clients.forEach(client => client.postMessage(message));
}

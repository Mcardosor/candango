// Service worker do Candango: guarda o app e os dados fixos para abrir rápido (e sem internet).
// GPS dos ônibus, busca de endereços e mapa sempre vêm da rede.
// Ao publicar mudanças, troque a versão para os celulares baixarem os arquivos novos.
const CACHE = "candango-v0.3.2";
const SHELL = [
  "./", "index.html", "app.css", "manifest.webmanifest", "dados.json",
  "js/app.js", "js/model.js", "js/planner.js", "js/live.js", "js/map.js",
  "icons/icon-192.png", "icons/apple-touch-icon.png",
  "https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.js",
  "https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.css",
];

self.addEventListener("install", (e) => {
  // "reload" pula o cache HTTP do navegador: o GitHub Pages deixa arquivos guardados por 10 min,
  // e sem isso a versão nova do cache podia nascer com os arquivos antigos
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: "reload" }))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  const cacheable = url.origin === location.origin || url.href.startsWith("https://unpkg.com/maplibre-gl@");
  if (!cacheable) return;
  // responde do cache na hora e atualiza em segundo plano
  e.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const hit = await cache.match(req, { ignoreSearch: true });
      const fresh = fetch(req, { cache: "no-cache" })
        .then((res) => { if (res.ok) cache.put(req, res.clone()); return res; })
        .catch(() => hit);
      return hit ?? fresh;
    }),
  );
});

// Gera app/src/main/assets/dados.json a partir da API pública da SEMOB-DF.
// Uso: node tools/build-data.mjs
// Conteúdo: paradas, linhas (trajeto por sentido, paradas atendidas, horários de partida).

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const BASE = "https://www.sistemas.dftrans.df.gov.br/service";
const OUT = join(dirname(fileURLToPath(import.meta.url)), "..", "app", "src", "main", "assets", "dados.json");
const STOP_MAX_DIST_M = 30;     // parada a até 30 m do trajeto = atendida pela linha
const DEFAULT_SPEED_KMH = 20;   // usada quando a SEMOB não informa duração média
const CONCURRENCY = 6;

// ---------- utilidades ----------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function get(path, tries = 4) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(BASE + path, { signal: AbortSignal.timeout(30000) });
      if (res.status === 404 || res.status === 400) return null; // 400 = busca inválida (ex.: "."), sem resultado
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return await res.json();
    } catch (e) {
      if (i === tries - 1) throw new Error(`${path}: ${e.message}`);
      await sleep(1000 * (i + 1));
    }
  }
}

async function pool(items, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: CONCURRENCY }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i], i);
    }
  }));
  return out;
}

// EPSG:3857 (metros) -> WGS84
const R = 6378137;
const toLonLat = ([x, y]) => [
  (x / R) * (180 / Math.PI),
  (2 * Math.atan(Math.exp(y / R)) - Math.PI / 2) * (180 / Math.PI),
];

// Projeção local equiretangular em metros (precisão suficiente no DF)
const LAT0 = -15.8 * (Math.PI / 180);
const M_PER_DEG_LAT = 111320;
const M_PER_DEG_LON = 111320 * Math.cos(LAT0);
const toXY = ([lon, lat]) => [lon * M_PER_DEG_LON, lat * M_PER_DEG_LAT];

// Codificação "encoded polyline" do Google (precisão 1e5), compacta e fácil de decodificar no app
function encodePolyline(coords) {
  let out = "", pLat = 0, pLon = 0;
  const enc = (v) => {
    v = v < 0 ? ~(v << 1) : v << 1;
    let s = "";
    while (v >= 0x20) { s += String.fromCharCode((0x20 | (v & 0x1f)) + 63); v >>= 5; }
    return s + String.fromCharCode(v + 63);
  };
  for (const [lon, lat] of coords) {
    const la = Math.round(lat * 1e5), lo = Math.round(lon * 1e5);
    out += enc(la - pLat) + enc(lo - pLon);
    pLat = la; pLon = lo;
  }
  return out;
}

// Douglas-Peucker em metros, para reduzir o tamanho dos trajetos
function simplify(coords, tol = 8) {
  if (coords.length < 3) return coords;
  const pts = coords.map(toXY);
  const keep = new Uint8Array(coords.length);
  keep[0] = keep[coords.length - 1] = 1;
  const stack = [[0, coords.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    let maxD = 0, idx = -1;
    for (let i = a + 1; i < b; i++) {
      const d = segDist(pts[i], pts[a], pts[b]).d;
      if (d > maxD) { maxD = d; idx = i; }
    }
    if (maxD > tol) { keep[idx] = 1; stack.push([a, idx], [idx, b]); }
  }
  return coords.filter((_, i) => keep[i]);
}

// Distância de p ao segmento ab, e fração t ao longo do segmento
function segDist(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1];
  const len2 = dx * dx + dy * dy;
  let t = len2 ? ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2 : 0;
  t = Math.max(0, Math.min(1, t));
  const x = a[0] + t * dx, y = a[1] + t * dy;
  return { d: Math.hypot(p[0] - x, p[1] - y), t };
}

// ---------- 1. paradas ----------
console.log("Baixando paradas...");
const stopsGeo = await get("/parada/geo/paradas");
const stops = stopsGeo.features.map((f) => {
  const [lon, lat] = toLonLat(f.geometry.coordinates);
  return { cod: f.properties.codDftrans ?? String(f.properties.sequencial), lon, lat, xy: toXY([lon, lat]) };
});
console.log(`  ${stops.length} paradas`);

// grade espacial de 200 m para achar paradas próximas de um trajeto
const CELL = 200;
const grid = new Map();
stops.forEach((s, i) => {
  const k = `${Math.floor(s.xy[0] / CELL)},${Math.floor(s.xy[1] / CELL)}`;
  (grid.get(k) ?? grid.set(k, []).get(k)).push(i);
});

// ---------- 2. lista de linhas ----------
// /linha/find/{q} devolve no máximo 30 resultados; expandimos o prefixo enquanto saturar.
console.log("Enumerando linhas...");
const lines = new Map(); // numero -> {numero, descricao, tarifa}
const ALPHABET = "0123456789.";
const queue = [...ALPHABET];
const seenQ = new Set();
while (queue.length) {
  const batch = queue.splice(0, CONCURRENCY);
  const results = await Promise.all(batch.map(async (q) => [q, (await get(`/linha/find/${encodeURIComponent(q)}`)) ?? []]));
  for (const [q, arr] of results) {
    seenQ.add(q);
    for (const l of arr) {
      if (!l.ativa) continue;
      const cur = lines.get(l.numero);
      if (!cur) lines.set(l.numero, { numero: l.numero, descricao: l.descricao, tarifa: l.faixaTarifaria?.tarifa ?? null });
    }
    if (arr.length >= 30 && q.length < 6) {
      for (const c of ALPHABET) if (!seenQ.has(q + c)) queue.push(q + c);
    }
  }
}
// linhas vistas no GPS ao vivo, caso alguma escape da busca
const gps = (await get("/gps/operacoes")) ?? [];
for (const op of gps) for (const v of op.veiculos) {
  if (v.linha && !lines.has(v.linha)) {
    const found = (await get(`/linha/numero/${encodeURIComponent(v.linha)}`)) ?? [];
    for (const l of found) if (l.ativa) lines.set(l.numero, { numero: l.numero, descricao: l.descricao, tarifa: l.faixaTarifaria?.tarifa ?? null });
  }
}
console.log(`  ${lines.size} linhas ativas (${seenQ.size} buscas)`);

// ---------- 3. trajetos, paradas atendidas e horários ----------
console.log("Baixando trajetos e horários...");
const lineList = [...lines.values()].sort((a, b) => a.numero.localeCompare(b.numero, "pt-BR", { numeric: true }));
let done = 0;
const outLines = await pool(lineList, async (l) => {
  const [percurso, horarios] = await Promise.all([
    get(`/percurso/linha/numero/${encodeURIComponent(l.numero)}`),
    get(`/horario/linha/numero/${encodeURIComponent(l.numero)}`),
  ]);
  if (++done % 50 === 0) console.log(`  ${done}/${lineList.length}`);

  // horários: sentido -> dias (máscara seg..dom) -> minutos desde 00:00
  const sched = {};
  let duracao = 0;
  for (const h of horarios ?? []) {
    if (h.duracaoMedia) duracao = h.duracaoMedia;
    for (const t of h.horarios ?? []) {
      const s = (sched[t.sentido] ??= {});
      (s[t.diasSemana] ??= new Set()).add(t.hora * 60 + t.minuto);
    }
  }

  const dirs = [];
  for (const f of percurso?.features ?? []) {
    const g = f.geometry;
    const parts = g.type === "MultiLineString" ? g.coordinates : [g.coordinates];
    const coords = parts.flat().map(toLonLat);
    if (coords.length < 2) continue;
    const xy = coords.map(toXY);

    // comprimento acumulado
    const cum = [0];
    for (let i = 1; i < xy.length; i++) cum.push(cum[i - 1] + Math.hypot(xy[i][0] - xy[i - 1][0], xy[i][1] - xy[i - 1][1]));
    const length = cum[cum.length - 1];

    // paradas a até STOP_MAX_DIST_M do trajeto, com a distância ao longo dele
    const best = new Map(); // stopIdx -> {d, along}
    for (let i = 1; i < xy.length; i++) {
      const a = xy[i - 1], b = xy[i];
      const minCx = Math.floor((Math.min(a[0], b[0]) - STOP_MAX_DIST_M) / CELL), maxCx = Math.floor((Math.max(a[0], b[0]) + STOP_MAX_DIST_M) / CELL);
      const minCy = Math.floor((Math.min(a[1], b[1]) - STOP_MAX_DIST_M) / CELL), maxCy = Math.floor((Math.max(a[1], b[1]) + STOP_MAX_DIST_M) / CELL);
      for (let cx = minCx; cx <= maxCx; cx++) for (let cy = minCy; cy <= maxCy; cy++) {
        for (const si of grid.get(`${cx},${cy}`) ?? []) {
          const { d, t } = segDist(stops[si].xy, a, b);
          if (d > STOP_MAX_DIST_M) continue;
          const along = cum[i - 1] + t * (cum[i] - cum[i - 1]);
          const prev = best.get(si);
          // trajetos circulares podem passar duas vezes; guardamos a primeira passagem mais próxima
          if (!prev || d < prev.d - 5 || (Math.abs(d - prev.d) <= 5 && along < prev.along)) best.set(si, { d, along });
        }
      }
    }
    const stopsOnDir = [...best.entries()].map(([si, v]) => [si, Math.round(v.along)]).sort((p, q) => p[1] - q[1]);

    const sentido = f.properties?.sentido ?? "CIRCULAR";
    const times = {};
    for (const [mask, set] of Object.entries(sched[sentido] ?? {})) times[mask] = [...set].sort((a, b) => a - b);

    dirs.push({
      s: sentido,
      shape: encodePolyline(simplify(coords)),
      len: Math.round(length),
      // minutos de viagem do início ao fim do trajeto
      dur: duracao > 0 ? duracao : Math.round((length / 1000 / DEFAULT_SPEED_KMH) * 60),
      durEstimada: !(duracao > 0),
      stops: stopsOnDir,
      times,
    });
  }
  return { n: l.numero, d: l.descricao, tarifa: l.tarifa, dirs };
});

const kept = outLines.filter((l) => l.dirs.length);
const data = {
  geradoEm: new Date().toISOString(),
  fonte: "SEMOB-DF / DFTrans (sistemas.dftrans.df.gov.br)",
  diasSemanaOrdem: "seg,ter,qua,qui,sex,sab,dom",
  stops: stops.map((s) => [s.cod, +s.lat.toFixed(6), +s.lon.toFixed(6)]),
  lines: kept,
};
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(data));

const withTimes = kept.filter((l) => l.dirs.some((d) => Object.keys(d.times).length)).length;
const servedStops = new Set(kept.flatMap((l) => l.dirs.flatMap((d) => d.stops.map((s) => s[0])))).size;
console.log(`OK: ${kept.length} linhas com trajeto (${withTimes} com horários), ${servedStops}/${stops.length} paradas atendidas`);
console.log(`Arquivo: ${OUT} (${(JSON.stringify(data).length / 1e6).toFixed(1)} MB)`);

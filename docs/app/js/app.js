// Estado e telas do Candango web. Espelha ui/AppViewModel.kt e ui/Screens.kt do app Android.
import { Geo, Repo } from "./model.js";
import { liveEta, plan, stopDepartures, spTime, Time, walkM, walkMinutes } from "./planner.js";
import { fetchBuses, searchPlaces } from "./live.js";
import { MapView, ME_COLOR } from "./map.js";

const WINDOW_MIN = 180; // "agora até daqui a 3 horas"
const POLL_MS = 15_000;
const WALKABLE_M = 3000;
// sem azul: o azul fica reservado ao marcador "você está aqui"
const PALETTE = [
  "#0B8A5F", "#5D4037", "#C62828", "#6A1B9A", "#EF6C00", "#00838F",
  "#AD1457", "#2E7D32", "#4527A0", "#D84315", "#00695C", "#827717",
];

/** Mesma cor do app Android: String.hashCode() do Java sobre o número da linha. */
export function colorFor(numero) {
  if (!numero) return "#78909C";
  let h = 0;
  for (let i = 0; i < numero.length; i++) h = (Math.imul(31, h) + numero.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(h) % PALETTE.length];
}

// ---------- ícones (Material Symbols, inline) ----------

const ICON = {
  bus: "M4 16c0 .88.39 1.67 1 2.22V20a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1h8v1a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm9 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm1.5-6H6V6h12v5z",
  walk: "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7",
  place: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5a2.5 2.5 0 0 1 0-5 2.5 2.5 0 0 1 0 5z",
  origin: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 16c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6z",
  myloc: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm8.94 3A8.99 8.99 0 0 0 13 3.06V1h-2v2.06A8.99 8.99 0 0 0 3.06 11H1v2h2.06A8.99 8.99 0 0 0 11 20.94V23h2v-2.06A8.99 8.99 0 0 0 20.94 13H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
  swap: "M16 17.01V10h-2v7.01h-3L15 21l4-3.99h-3zM9 3 5 6.99h3V14h2V6.99h3L9 3z",
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
  close: "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  search: "M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
  live: "M7.76 16.24C6.67 15.16 6 13.66 6 12s.67-3.16 1.76-4.24l1.42 1.42C8.45 9.9 8 10.9 8 12c0 1.1.45 2.1 1.17 2.83l-1.41 1.41zm8.48 0C17.33 15.16 18 13.66 18 12s-.67-3.16-1.76-4.24l-1.42 1.42C15.55 9.9 16 10.9 16 12c0 1.1-.45 2.1-1.17 2.83l1.41 1.41zM12 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm8 2c0 2.21-.9 4.21-2.35 5.65l1.42 1.42C20.88 17.26 22 14.76 22 12s-1.12-5.26-2.93-7.07l-1.42 1.42A7.94 7.94 0 0 1 20 12zM6.35 6.35 4.93 4.93C3.12 6.74 2 9.24 2 12s1.12 5.26 2.93 7.07l1.42-1.42C4.9 16.21 4 14.21 4 12s.9-4.21 2.35-5.65z",
  home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
  work: "M20 6h-4V4c0-1.11-.89-2-2-2h-4c-1.11 0-2 .89-2 2v2H4c-1.11 0-1.99.89-1.99 2L2 19c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2zm-6 0h-4V4h4v2z",
  star: "M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
  starOff: "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
  history: "M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
  bell: "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z",
  bellOff: "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2zm-2 1H8v-6c0-2.48 1.51-4.5 4-4.5s4 2.02 4 4.5v6z",
  nav: "M12 2 4.5 20.29l.71.71L12 18l6.79 3 .71-.71z",
  edit: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  map: "m20.5 3-.16.03L15 5.1 9 3 3.36 4.9c-.21.07-.36.25-.36.48V20.5c0 .28.22.5.5.5l.16-.03L9 18.9l6 2.1 5.64-1.9c.21-.07.36-.25.36-.48V3.5c0-.28-.22-.5-.5-.5zM15 19l-6-2.11V5l6 2.11V19z",
};
const icon = (name, cls = "", label = "") =>
  `<svg class="ic ${cls}" viewBox="0 0 24 24" ${label ? `role="img" aria-label="${label}"` : 'aria-hidden="true"'}><path d="${ICON[name]}"/></svg>`;

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const brl = (v) => v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
const km = (m) => (m / 1000).toLocaleString("pt-BR", { maximumFractionDigits: 1 });

// ---------- estado ----------

const S = {
  loaded: false,
  loadError: null,
  buses: [],
  busesUpdatedAt: 0,
  liveError: null,
  myLocation: null,
  myAccuracyM: null, // raio de erro informado pelo navegador; null quando marcado à mão
  manualMe: false, // a pessoa marcou no mapa onde está
  locationDenied: false,
  origin: null, // {name, pos}
  dest: null,
  startMs: null, // null = sair agora
  startChoice: "now", // now | 1 | 2 | 3 | custom
  stack: [{ type: "home" }],
  planning: false,
  planResults: [],
  search: null, // {field: "origin"|"dest"|null, query, hits, searching}
  pickOnMapFor: null,
  installPrompt: null,
  alerts: [], // {dir, along, stop}: avisar quando o ônibus estiver a ALERT_MIN da parada
  toast: null, // último aviso disparado, mostrado no topo
};

// ---------- favoritos e recentes (guardados só neste aparelho) ----------

const FAV_KEY = "candango.favoritos";
const MAX_RECENTS = 5;
const FAV_PLACES = { casa: { label: "Casa", icon: "home" }, trabalho: { label: "Trabalho", icon: "work" } };

const Fav = (() => {
  let d = {};
  try { d = JSON.parse(localStorage.getItem(FAV_KEY)) ?? {}; } catch {}
  d.places ??= {};
  d.lines ??= [];
  d.recents ??= [];
  const save = () => { try { localStorage.setItem(FAV_KEY, JSON.stringify(d)); } catch {} };
  const samePlace = (a, b) => Geo.dist(a.pos, b.pos) < 30;
  return {
    place: (k) => d.places[k] ?? null,
    setPlace(k, e) { if (e) d.places[k] = { name: e.name, pos: e.pos }; else delete d.places[k]; save(); },
    get lines() { return d.lines; },
    hasLine: (n) => d.lines.includes(n),
    toggleLine(n) { d.lines = d.lines.includes(n) ? d.lines.filter((x) => x !== n) : [...d.lines, n]; save(); },
    get recents() { return d.recents; },
    addRecent(e) {
      d.recents = [{ name: e.name, pos: e.pos }, ...d.recents.filter((r) => !samePlace(r, e))].slice(0, MAX_RECENTS);
      save();
    },
  };
})();

const screen = () => S.stack[S.stack.length - 1];
const effectiveOrigin = () => S.origin ?? (S.myLocation ? { name: "Minha localização", pos: S.myLocation, isMe: true } : null);
const walkableMe = (p) => (S.myLocation && Geo.dist(S.myLocation, p) <= WALKABLE_M ? S.myLocation : null);
const liveBusesOf = (line) => S.buses.filter((b) => b.linha === line.numero);

// ---------- histórico do navegador (botão voltar do celular) ----------

// Cada nível que o "voltar" desfaz (tela empilhada, busca, seleção no mapa) tem uma entrada no histórico.
// Depois de cada mudança, o histórico é ajustado para a profundidade atual do app.
let histDepth = 0;
let pendingGo = false;
const appDepth = () => S.stack.length - 1 + (S.search ? 1 : 0) + (S.pickOnMapFor ? 1 : 0);

function syncHistory() {
  if (pendingGo) return; // espera o history.go() anterior terminar
  const want = appDepth();
  if (want > histDepth) {
    for (; histDepth < want; histDepth++) history.pushState({ candango: histDepth + 1 }, "");
  } else if (want < histDepth) {
    pendingGo = true;
    const n = histDepth - want;
    histDepth = want;
    history.go(-n);
  }
}
window.addEventListener("popstate", () => {
  if (pendingGo) { pendingGo = false; syncHistory(); return; }
  histDepth = Math.max(0, histDepth - 1);
  doBack();
});
const goBack = () => (histDepth > 0 && !pendingGo ? history.back() : doBack());

function doBack() {
  if (S.search) { S.search = null; renderAll(); return; }
  if (S.pickOnMapFor) { S.pickOnMapFor = null; renderAll(); return; }
  if (S.stack.length > 1) { S.stack.pop(); renderAll(); }
}

function push(s) {
  S.stack.push(s);
  renderAll();
}

function replaceTop(s) { S.stack[S.stack.length - 1] = s; }

const openLine = (line, dirIndex = 0) => push({ type: "line", line, dirIndex });
const openStop = (stop) => push({ type: "stop", stop });
const openRoute = (option) => push({ type: "route", option });

// ---------- origem / destino / planejamento ----------

function closeOverlays() {
  S.search = null;
  S.pickOnMapFor = null;
}

/** Destino de uma escolha feita na busca ou no mapa: partida, destino, Casa/Trabalho ou "onde estou". */
function choose(field, e) {
  if (field === "me") setManualMe(e.pos);
  else if (FAV_PLACES[field]) {
    Fav.setPlace(field, { name: e.isMe ? FAV_PLACES[field].label : e.name, pos: e.pos });
    closeOverlays();
    renderAll();
  } else setEndpoint(field, e);
}

function setEndpoint(field, e) {
  if (e && !e.isMe && !e.fav) Fav.addRecent(e);
  if (field === "origin") S.origin = e?.isMe ? null : e;
  else S.dest = e;
  closeOverlays();
  replan();
  renderAll();
}

function swap() {
  const o = effectiveOrigin(), d = S.dest;
  S.origin = d;
  S.dest = o;
  replan();
  renderAll();
}

function setStart(choice, ms) {
  S.startChoice = choice;
  S.startMs = ms;
  replan();
  renderAll();
}

function clearTrip() {
  S.origin = null;
  S.dest = null;
  S.planResults = [];
  S.startMs = null;
  S.startChoice = "now";
  S.stack = [{ type: "home" }];
  renderAll();
}

let planTimer = null;
function replan(silent = false) {
  const o = effectiveOrigin()?.pos, d = S.dest?.pos;
  if (!o || !d || !S.loaded) { S.planResults = []; return; }
  if (screen().type === "home") S.stack.push({ type: "plan" });
  if (!silent) S.planning = true;
  clearTimeout(planTimer);
  // deixa a tela mostrar o "calculando" antes do cálculo, que roda na thread principal
  planTimer = setTimeout(() => {
    const res = plan(o, d, S.startMs ?? Date.now(), WINDOW_MIN, S.buses);
    S.planResults = res;
    S.planning = false;
    // mantém a tela de rota aberta atualizada com o novo ETA
    const s = screen();
    if (s.type === "route") {
      const same = res.find((x) => x.key === s.option.key);
      if (same) replaceTop({ type: "route", option: same });
    }
    renderAll();
  }, silent ? 0 : 30);
}

// ---------- busca ----------

function openSearch(field) {
  S.search = { field, query: "", hits: localHits("", field == null), searching: false };
  renderAll();
  const input = document.getElementById("search-input");
  input.value = "";
  input.placeholder = field === "origin" ? "De onde você sai?"
    : field === "dest" ? "Endereço, lugar ou parada"
    : FAV_PLACES[field] ? `Endereço de ${FAV_PLACES[field].label.toLowerCase()}`
    : "Número ou nome da linha";
  input.focus();
}

let searchTimer = null, searchAbort = null;
function onSearchQuery(q) {
  const s = S.search;
  if (!s) return;
  s.query = q;
  clearTimeout(searchTimer);
  searchAbort?.abort();
  const query = q.trim();
  const local = localHits(query, s.field == null);
  s.hits = local;
  renderSearchList();
  if (s.field == null || query.length < 3) return;
  searchTimer = setTimeout(async () => {
    searchAbort = new AbortController();
    s.searching = true;
    renderSearchList();
    try {
      const places = await searchPlaces(query, searchAbort.signal);
      if (S.search === s && s.query === q) s.hits = [...places.map((place) => ({ place })), ...local];
    } catch {
      // sem internet: fica só com linhas/paradas locais
    } finally {
      s.searching = false;
      if (S.search === s) renderSearchList();
    }
  }, 350);
}

function localHits(q, linesOnly) {
  if (!q) {
    if (!linesOnly) return [];
    // linhas favoritas primeiro
    const favs = Fav.lines.map((n) => Repo.lineByNumero.get(n)).filter(Boolean);
    return [...favs, ...Repo.lines.filter((l) => !Fav.hasLine(l.numero)).slice(0, 40)].map((line) => ({ line }));
  }
  const norm = q.toLowerCase();
  const lines = Repo.lines
    .filter((l) => l.numero.startsWith(q) || l.descricao.toLowerCase().includes(norm))
    .sort((a, b) => (a.numero.startsWith(q) ? 0 : 1) - (b.numero.startsWith(q) ? 0 : 1))
    .slice(0, 30).map((line) => ({ line }));
  if (linesOnly) return lines;
  const stops = /^\d+$/.test(q) ? Repo.stops.filter((s) => s.cod === q).map((stop) => ({ stop })) : [];
  return [...stops, ...lines.slice(0, 6)];
}

function onSearchHit(hit) {
  const field = S.search?.field;
  if (hit.line) { closeOverlays(); openLine(hit.line); return; }
  if (hit.stop) {
    if (field) choose(field, { name: hit.stop.label, pos: hit.stop.pos });
    else { closeOverlays(); openStop(hit.stop); }
    return;
  }
  if (hit.place && field) choose(field, { name: hit.place.name, pos: hit.place.pos });
}

function startPick(field) {
  S.search = null;
  S.pickOnMapFor = field;
  renderAll();
}

// ---------- tempo real e localização ----------

let pollTimer = null;
async function poll() {
  clearTimeout(pollTimer);
  try {
    S.buses = await fetchBuses();
    S.busesUpdatedAt = Date.now();
    S.liveError = null;
    if (screen().type !== "home") replan(true);
    checkAlerts();
    updateTrip();
  } catch {
    S.liveError = "Sem conexão com o GPS da SEMOB";
  }
  renderAll();
  if (!document.hidden || backgroundWork()) pollTimer = setTimeout(poll, POLL_MS);
}
// com aviso ou viagem ativa, continua atualizando mesmo com a tela em segundo plano
const backgroundWork = () => S.alerts.length > 0 || !!tripScreen();
document.addEventListener("visibilitychange", () => {
  if (document.hidden) { if (!backgroundWork()) clearTimeout(pollTimer); }
  else { poll(); if (tripScreen()) keepAwake(true); }
});

function startLocation() {
  // para testes locais: localhost:4173/app/?geo=-15.7962,-47.8838 simula a posição
  // (um terceiro número simula a margem de erro em metros: ?geo=-15.79,-47.88,3000)
  const fake = location.hostname === "localhost" && new URLSearchParams(location.search).get("geo");
  if (fake) {
    const [lat, lon, acc] = fake.split(",").map(Number);
    S.myLocation = { lat, lon };
    S.myAccuracyM = acc || null;
    // no console: __geo(-15.79, -47.88) "anda" com a posição simulada
    window.__geo = (la, lo) => { S.myLocation = { lat: la, lon: lo }; updateTrip(); renderAll(); };
    window.__trip = tripScreen;
    return;
  }
  try {
    const saved = JSON.parse(localStorage.getItem(MANUAL_ME_KEY));
    if (saved?.lat) { S.myLocation = saved; S.manualMe = true; }
  } catch {}
  if (!("geolocation" in navigator)) return;
  navigator.geolocation.watchPosition(
    (p) => {
      // posição marcada à mão só perde para um GPS de verdade
      if (S.manualMe && p.coords.accuracy > GOOD_ACCURACY_M) return;
      const first = !S.myLocation;
      if (S.manualMe) forgetManualMe();
      S.myLocation = { lat: p.coords.latitude, lon: p.coords.longitude };
      S.myAccuracyM = p.coords.accuracy;
      S.locationDenied = false;
      if (first && !S.origin && S.dest) replan();
      updateTrip();
      renderAll();
    },
    (err) => {
      if (err.code === err.PERMISSION_DENIED) { S.locationDenied = true; renderAll(); }
    },
    { enableHighAccuracy: true, maximumAge: 10_000, timeout: 30_000 },
  );
}

// No computador não há GPS: o navegador estima a posição pela internet e pode errar por quilômetros.
const MANUAL_ME_KEY = "candango.minhaPosicao";
const GOOD_ACCURACY_M = 100;
const POOR_ACCURACY_M = 300;
const locationIsPoor = () => !S.manualMe && S.myAccuracyM > POOR_ACCURACY_M;

function setManualMe(pos) {
  S.myLocation = pos;
  S.myAccuracyM = null;
  S.manualMe = true;
  try { localStorage.setItem(MANUAL_ME_KEY, JSON.stringify(pos)); } catch {}
  closeOverlays();
  if (!S.origin && S.dest) replan();
  renderAll();
}

function forgetManualMe() {
  S.manualMe = false;
  try { localStorage.removeItem(MANUAL_ME_KEY); } catch {}
}

// ---------- avisos (notificação, vibração e som) ----------

const ALERT_MIN = 5;
const TOAST_MS = 2 * 60_000;
let audioCtx = null;

/** Pede permissão de notificação e libera o som; precisa ser chamado num toque da pessoa. */
function prepareWarnings() {
  if (window.Notification?.permission === "default") Notification.requestPermission().catch(() => {});
  try { audioCtx ??= new AudioContext(); audioCtx.resume(); } catch {}
}

function beep() {
  if (!audioCtx) return;
  try {
    for (const [t, f] of [[0, 880], [0.25, 1175]]) {
      const o = audioCtx.createOscillator(), g = audioCtx.createGain();
      o.frequency.value = f;
      g.gain.value = 0.15;
      o.connect(g).connect(audioCtx.destination);
      o.start(audioCtx.currentTime + t);
      o.stop(audioCtx.currentTime + t + 0.2);
    }
  } catch {}
}

async function warn(title, body) {
  S.toast = { title, body, at: Date.now() };
  navigator.vibrate?.([300, 150, 300, 150, 300]);
  beep();
  if (window.Notification?.permission !== "granted") return;
  try {
    // no Android a notificação precisa sair pelo service worker
    const reg = await navigator.serviceWorker?.getRegistration();
    if (reg) await reg.showNotification(title, { body, icon: "icons/icon-192.png", tag: "candango", renotify: true });
    else new Notification(title, { body, icon: "icons/icon-192.png" });
  } catch {}
}

const alertIndex = (dir, stop) => S.alerts.findIndex((a) => a.dir === dir && a.stop === stop);

function toggleAlert(dir, along, stop) {
  const i = alertIndex(dir, stop);
  if (i >= 0) S.alerts.splice(i, 1);
  else { S.alerts.push({ dir, along, stop }); prepareWarnings(); checkAlerts(); }
  renderAll();
}

function checkAlerts() {
  S.alerts = S.alerts.filter((a) => {
    const live = liveEta(a.dir, a.along, S.buses);
    if (!live || live.minutes > ALERT_MIN) return true;
    warn(`Linha ${a.dir.line.numero} chegando`, `Está a ${live.minutes <= 1 ? "menos de 1 min" : `${live.minutes} min`} da ${a.stop.label}`);
    return false;
  });
}

// ---------- viagem em andamento: acompanha a pessoa até o destino ----------

const AT_STOP_M = 40;
const ON_ROUTE_M = 120;
let wakeLock = null;

const tripScreen = () => S.stack.find((t) => t.type === "trip");

async function keepAwake(on) {
  try {
    if (on && !wakeLock) {
      wakeLock = await navigator.wakeLock?.request("screen");
      wakeLock?.addEventListener("release", () => { wakeLock = null; });
    } else if (!on && wakeLock) {
      await wakeLock.release();
      wakeLock = null;
    }
  } catch {}
}

function startTrip(option) {
  prepareWarnings();
  keepAwake(true);
  push({ type: "trip", option, legIdx: 0, phase: "walk", warned: new Set() });
  updateTrip();
  renderAll();
}

function warnOnce(t, key, title, body) {
  if (t.warned.has(key)) return;
  t.warned.add(key);
  warn(title, body);
}

/** Onde a pessoa está no trajeto do ônibus; sem GPS confiável, estima pelo tempo desde o embarque. */
function rideAlong(t, leg) {
  const me = S.myLocation;
  if (me) {
    const p = leg.dir.project(me);
    if (p.distM < ON_ROUTE_M && p.alongM >= leg.boardAlong - 200 && p.alongM <= leg.alightAlong + 400) {
      t.along = Math.max(t.along, p.alongM); // ônibus não anda para trás
      return t.along;
    }
  }
  return Math.max(t.along, leg.boardAlong + ((Date.now() - t.boardedAt) / 60_000) * leg.dir.speedMPerMin);
}

/** Paradas que ainda faltam até a de descida (inclusive), a partir de [along]. */
function stopsAhead(leg, along) {
  const d = leg.dir, out = [];
  for (let i = 0; i < d.stopIdx.length; i++) {
    if (d.stopAlong[i] > along + 30 && d.stopAlong[i] <= leg.alightAlong + 1) out.push(Repo.stops[d.stopIdx[i]]);
  }
  return out;
}

function nextPhase(t) {
  const legs = t.option.legs, leg = legs[t.legIdx];
  if (t.phase === "walk") t.phase = "wait";
  else if (t.phase === "wait") { t.phase = "ride"; t.boardedAt = Date.now(); t.along = leg.boardAlong; }
  else if (t.phase === "ride") {
    if (t.legIdx < legs.length - 1) {
      t.legIdx++;
      t.phase = Geo.dist(leg.alightStop.pos, legs[t.legIdx].boardStop.pos) < AT_STOP_M ? "wait" : "walk";
    } else t.phase = S.dest ? "walkEnd" : "done";
  } else if (t.phase === "walkEnd") t.phase = "done";
}

/** Avança as etapas sozinho conforme a posição e o GPS dos ônibus. */
function updateTrip() {
  const t = tripScreen();
  if (!t) return;
  const me = S.myLocation;
  const leg = t.option.legs[t.legIdx];
  const num = leg.dir.line.numero;
  if (t.phase === "walk" && me && Geo.dist(me, leg.boardStop.pos) <= AT_STOP_M) nextPhase(t);
  if (t.phase === "walk" || t.phase === "wait") {
    const p = me && leg.dir.project(me);
    // já está dentro do ônibus, andando pelo trajeto depois do ponto de embarque
    if (t.phase === "wait" && p && p.distM < 60 && p.alongM > leg.boardAlong + 150 && p.alongM < leg.alightAlong) {
      nextPhase(t);
    } else {
      const live = liveEta(leg.dir, leg.boardAlong, S.buses);
      if (live && live.minutes <= 2) {
        warnOnce(t, `chega${t.legIdx}`, `Linha ${num} chegando`, `Está a ${live.minutes <= 1 ? "menos de 1 min" : `${live.minutes} min`} da ${leg.boardStop.label}`);
      }
    }
  }
  if (t.phase === "ride") {
    const along = rideAlong(t, leg);
    const ahead = stopsAhead(leg, along);
    if (ahead.length === 1) warnOnce(t, `desce${t.legIdx}`, "Desça na próxima parada", `${leg.alightStop.label} · linha ${num}`);
    if (along >= leg.alightAlong - AT_STOP_M) nextPhase(t);
  }
  if (t.phase === "walkEnd" && me && S.dest && Geo.dist(me, S.dest.pos) <= 60) nextPhase(t);
}

const fmtDist = (m) => (m >= 1000 ? `${(m / 1000).toLocaleString("pt-BR", { maximumFractionDigits: 1 })} km` : `${Math.round(m)} m`);

// ---------- mapa ----------

let mapView;
let lastFitKey = null;

function mapPadding() {
  const wide = matchMedia("(min-width: 900px)").matches;
  const top = document.getElementById("top").getBoundingClientRect();
  if (wide) return { top: 12, bottom: 12, left: top.right + 12, right: 12 };
  return { top: top.bottom + 8, bottom: Math.round(innerHeight * 0.48), left: 0, right: 0 };
}

function fitKey() {
  const s = screen();
  if (s.type === "route") return "rota:" + s.option.key;
  if (s.type === "trip") return `viagem:${s.legIdx}:${s.phase}`;
  if (s.type === "line") return `linha:${s.line.numero}:${s.dirIndex}`;
  if (s.type === "stop") return "parada:" + s.stop.idx;
  const o = effectiveOrigin()?.pos, d = S.dest?.pos;
  return `plano:${o?.lat},${o?.lon}:${d?.lat},${d?.lon}`;
}

function fitPoints() {
  const s = screen();
  if (s.type === "route") {
    return [effectiveOrigin()?.pos, S.dest?.pos, ...s.option.legs.flatMap((l) => l.dir.shapeBetween(l.boardAlong, l.alightAlong))].filter(Boolean);
  }
  if (s.type === "trip") {
    // a pessoa e o próximo ponto que importa
    const leg = s.option.legs[s.legIdx];
    const target = s.phase === "ride" ? leg.alightStop.pos : s.phase === "walkEnd" || s.phase === "done" ? S.dest?.pos : leg.boardStop.pos;
    return [S.myLocation, target].filter(Boolean);
  }
  if (s.type === "line") return s.line.dirs[s.dirIndex]?.shape ?? [];
  if (s.type === "stop") return [s.stop.pos, walkableMe(s.stop.pos)].filter(Boolean);
  return [effectiveOrigin()?.pos, S.dest?.pos].filter(Boolean);
}

function renderMap() {
  if (!mapView?.loadedOnce || !S.loaded) return;
  const c = mapView;
  c.setMe(S.myLocation);
  const o = effectiveOrigin();
  const markers = [];
  if (o && !o.isMe) markers.push({ pos: o.pos, kind: "origem", label: "Partida" });
  if (S.dest) markers.push({ pos: S.dest.pos, kind: "destino", label: "Destino" });
  const allBuses = () => S.buses.map((bus) => ({ bus, color: colorFor(bus.linha), highlight: false }));
  const s = screen();

  if (s.type === "route" || s.type === "trip") {
    const op = s.option, walk = "#455A64", lines = [];
    if (o) lines.push({ points: [o.pos, op.first.boardStop.pos], color: walk, dashed: true });
    op.legs.forEach((leg, i) => {
      if (i > 0) lines.push({ points: [op.legs[i - 1].alightStop.pos, leg.boardStop.pos], color: walk, dashed: true });
      lines.push({ points: leg.dir.shapeBetween(leg.boardAlong, leg.alightAlong), color: colorFor(leg.dir.line.numero) });
    });
    if (S.dest) lines.push({ points: [op.last.alightStop.pos, S.dest.pos], color: walk, dashed: true });
    c.setLines(lines);
    c.setColoredStops(op.legs.flatMap((leg) => {
      const color = colorFor(leg.dir.line.numero);
      return [[leg.boardStop, color], [leg.alightStop, color]];
    }));
    c.setBuses(op.legs.flatMap((leg) => liveBusesOf(leg.dir.line).map((bus) => ({ bus, color: colorFor(leg.dir.line.numero), highlight: true }))));
  } else if (s.type === "line") {
    const dir = s.line.dirs[s.dirIndex], color = colorFor(s.line.numero);
    c.setLines(dir ? [{ points: dir.shape, color }] : []);
    c.setColoredStops(dir ? dir.stopIdx.map((i) => [Repo.stops[i], color]) : []);
    c.setBuses(liveBusesOf(s.line).map((bus) => ({ bus, color, highlight: true })));
  } else if (s.type === "stop") {
    const me = walkableMe(s.stop.pos);
    c.setLines(me ? [{ points: [me, s.stop.pos], color: ME_COLOR, dashed: true }] : []);
    c.setColoredStops([[s.stop, "#0B8A5F"]]);
    c.setBuses(allBuses());
  } else {
    c.setLines([]);
    c.setColoredStops([]);
    c.setBuses(allBuses());
  }
  c.setMarkers(markers);

  // enquadra o mapa só quando o "assunto" da tela muda
  const key = fitKey();
  if (key !== lastFitKey) {
    lastFitKey = key;
    const pts = fitPoints();
    if (pts.length) c.fit(pts, mapPadding());
  }
}

function onMapClick(latlon, hit) {
  if (S.pickOnMapFor) {
    choose(S.pickOnMapFor, { name: "Ponto no mapa", pos: latlon });
    return;
  }
  if (hit.linha && Repo.lineByNumero.has(hit.linha)) openLine(Repo.lineByNumero.get(hit.linha));
  else if (hit.stopIdx != null) openStop(Repo.stops[hit.stopIdx]);
}

// ---------- renderização ----------

const $ = (id) => document.getElementById(id);

function renderAll() {
  if (!tripScreen() && wakeLock) keepAwake(false);
  renderTop();
  renderPanel();
  renderSearch();
  renderFab();
  renderMap();
  syncHistory();
}

function renderTop() {
  const s = screen();
  const top = $("top");
  let html;
  if (s.type === "home" || s.type === "plan") {
    const o = effectiveOrigin();
    html = `<div class="card trip">
      <div class="endpoints">
        <button class="endpoint" data-act="search-origin">${icon("origin", "c-origin")}<span class="${o ? "" : "ph"}">${esc(o?.name ?? "Escolha a partida")}</span></button>
        <hr>
        <button class="endpoint" data-act="search-dest">${icon("place", "c-dest")}<span class="${S.dest ? "" : "ph"}">${esc(S.dest?.name ?? "Para onde?")}</span></button>
      </div>
      <button class="icon-btn" data-act="swap" title="Inverter">${icon("swap", "", "Inverter")}</button>
    </div>`;
    if (S.dest) {
      const chip = (choice, label) => `<button class="chip ${S.startChoice === choice ? "on" : ""}" data-act="start" data-choice="${choice}">${label}</button>`;
      const custom = S.startChoice === "custom" && S.startMs ? `Saída ${Time.fmt(spTime(S.startMs).min)}` : "Horário…";
      html += `<div class="chips">${chip("now", "Agora")}${chip("1", "+1h")}${chip("2", "+2h")}${chip("3", "+3h")}
        <label class="chip ${S.startChoice === "custom" ? "on" : ""}">${custom}<input type="time" id="start-time" aria-label="Horário de saída"></label>
        <button class="chip" data-act="clear">Limpar</button></div>`;
    }
  } else {
    const nums = (o) => o.legs.map((l) => l.dir.line.numero).join(" + ");
    const title = s.type === "route" ? (s.option.isDirect ? "Linha " : "Linhas ") + nums(s.option)
      : s.type === "trip" ? `Viagem · ${nums(s.option)}`
      : s.type === "line" ? `Linha ${s.line.numero}` : s.stop.label;
    html = `<div class="card titlebar"><button class="icon-btn" data-act="back">${s.type === "trip" ? icon("close", "", "Encerrar viagem") : icon("back", "", "Voltar")}</button><b>${esc(title)}</b></div>`;
  }
  if (S.toast && Date.now() - S.toast.at > TOAST_MS) S.toast = null;
  if (S.toast) {
    html += `<div class="banner toast"><span><b>${esc(S.toast.title)}</b><br>${esc(S.toast.body)}</span><button class="icon-btn" data-act="toast-close">${icon("close", "", "Fechar")}</button></div>`;
  }
  S.alerts.forEach((a, i) => {
    const live = liveEta(a.dir, a.along, S.buses);
    const when = live ? `ônibus a ${live.minutes} min` : "esperando um ônibus com GPS";
    html += `<div class="banner alert">${icon("bell")}<span>Aviso: ${esc(a.dir.line.numero)} na ${esc(a.stop.label)} · ${when}</span><button class="icon-btn" data-act="alert-cancel" data-i="${i}">${icon("close", "", "Cancelar aviso")}</button></div>`;
  });
  if (S.pickOnMapFor) {
    const f = S.pickOnMapFor;
    html += banner(f === "me" ? "Toque no mapa onde você está"
      : FAV_PLACES[f] ? `Toque no mapa para marcar ${f === "casa" ? "sua casa" : "seu trabalho"}`
      : `Toque no mapa para escolher o ${f === "origin" ? "ponto de partida" : "destino"}`, true);
  }
  if (S.liveError) html += banner(S.liveError, false);
  top.innerHTML = html;
}

const banner = (text, closable) =>
  `<div class="banner"><span>${esc(text)}</span>${closable ? `<button class="icon-btn" data-act="back">${icon("close", "", "Fechar")}</button>` : ""}</div>`;

function renderPanel() {
  const panel = $("panel");
  const body = panel.querySelector(".panel-body");
  const s = screen();
  const viewKey = s.type + (s.option?.key ?? s.line?.numero ?? s.stop?.idx ?? "");
  const keepScroll = body.dataset.view === viewKey ? body.scrollTop : 0;
  let html;
  if (!S.loaded) html = `<div class="pad">${S.loadError ? `<p class="err">${esc(S.loadError)}</p>` : `<div class="spinner"></div><p class="muted center">Carregando linhas e paradas…</p>`}</div>`;
  else if (s.type === "home") html = homeContent();
  else if (s.type === "plan") html = planContent();
  else if (s.type === "route") html = routeContent(s.option);
  else if (s.type === "trip") html = tripContent(s);
  else if (s.type === "line") html = lineContent(s.line, s.dirIndex);
  else html = stopContent(s.stop);
  body.innerHTML = html;
  body.dataset.view = viewKey;
  body.scrollTop = keepScroll;
  updateLiveStatus();
}

function liveStatus() {
  return `<div class="live-status">${icon("live", "c-live")}<span id="live-status-text"></span></div>`;
}

function updateLiveStatus() {
  const el = $("live-status-text");
  if (!el) return;
  el.textContent = S.busesUpdatedAt
    ? `${S.buses.length.toLocaleString("pt-BR")} ônibus ao vivo · atualizado há ${Math.round((Date.now() - S.busesUpdatedAt) / 1000)}s`
    : "Conectando ao GPS dos ônibus…";
}
setInterval(updateLiveStatus, 1000);

function homeContent() {
  const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent);
  const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone;
  let install = "";
  if (!standalone && S.installPrompt) {
    install = `<button class="btn primary" data-act="install">Instalar o Candango</button>`;
  } else if (!standalone && isIos) {
    install = `<p class="tip">📲 <b>Instale no iPhone:</b> toque em <b>Compartilhar</b> <span class="share">⎋</span> e depois em <b>Adicionar à Tela de Início</b>.</p>`;
  }
  const places = Object.entries(FAV_PLACES).map(([k, f]) => {
    const p = Fav.place(k);
    return `<div class="fav ${p ? "" : "empty"}">
      <button class="fav-main" data-act="fav-go" data-k="${k}">${icon(f.icon)}<span><b>${f.label}</b><small class="muted ellipsis">${p ? esc(p.name) : "Toque para definir"}</small></span></button>
      ${p ? `<button class="icon-btn small" data-act="fav-edit" data-k="${k}">${icon("edit", "", `Mudar ${f.label}`)}</button>` : ""}
    </div>`;
  }).join("");
  const favLines = Fav.lines.filter((n) => Repo.lineByNumero.has(n));
  return `<div class="pad">
    ${liveStatus()}
    <div class="favs">${places}</div>
    ${favLines.length ? `<div class="chips inline">${favLines.map((n) => `<button class="chip-badge" data-act="line" data-n="${esc(n)}">${badge(n)}</button>`).join("")}</div>` : ""}
    <div class="row gap">
      <button class="btn" data-act="lines">${icon("bus")}Linhas</button>
      <button class="btn" data-act="nearest" ${S.myLocation ? "" : "disabled"}>${icon("myloc")}Parada mais próxima</button>
    </div>
    ${S.locationDenied ? `<p class="tip">Sem acesso à sua localização. Para ver as paradas perto de você, permita a localização para este site nas configurações do navegador.</p>` : ""}
    ${locationIsPoor() ? `<p class="tip warn">📍 Sua localização pode estar errada (margem de ~${fmtDist(S.myAccuracyM)}). No computador ela é estimada pela internet, sem GPS.
      <button class="link" data-act="pick-me">Marcar onde estou</button></p>` : ""}
    ${S.manualMe ? `<p class="small muted">📍 Localização marcada no mapa · <button class="link" data-act="pick-me">mudar</button></p>` : ""}
    ${install}
    ${nearbyContent()}
    ${S.locationDenied || locationIsPoor() || S.manualMe || !S.myLocation ? "" : `<p class="small muted"><button class="link" data-act="pick-me">Localização errada? Marque no mapa</button></p>`}
    ${S.myLocation ? "" : `<p class="muted small">Dica: toque e segure no mapa para marcar o destino.</p>`}
  </div>`;
}

// ---------- "Perto de mim": próximas linhas nas paradas ao redor ----------

const NEARBY_RADIUS_M = 500;
const NEARBY_MAX_STOPS = 15;
const NEARBY_ROWS = 8;
let nearbyCache = { key: null, rows: [] };

/** Uma linha por sentido, na parada próxima onde ela passa primeiro. */
function nearbyRows() {
  const me = S.myLocation;
  if (!me || !S.loaded) return [];
  const stops = Repo.stopsNear(me, NEARBY_RADIUS_M).slice(0, NEARBY_MAX_STOPS);
  const key = `${stops.map(([s]) => s.idx).join(",")}|${S.busesUpdatedAt}|${spTime().min}`;
  if (nearbyCache.key === key) return nearbyCache.rows;
  const nowMin = spTime().min;
  const best = new Map();
  for (const [stop, d] of stops) {
    const walk = walkMinutes(walkM(d));
    for (const r of stopDepartures(stop, Date.now(), 90, S.buses)) {
      // só vale se dá tempo de chegar à parada a pé
      const live = r.live && r.live.minutes >= walk ? r.live : null;
      const at = live ? nowMin + live.minutes : r.passes.find((p) => p >= nowMin + walk);
      if (at == null) continue;
      const cur = best.get(r.dir);
      if (!cur || at < cur.at) best.set(r.dir, { ...r, live, stop, distM: walkM(d), at });
    }
  }
  const rows = [...best.values()]
    .sort((a, b) => (Fav.hasLine(b.dir.line.numero) - Fav.hasLine(a.dir.line.numero)) || a.at - b.at)
    .slice(0, NEARBY_ROWS);
  nearbyCache = { key, rows };
  return rows;
}

function nearbyContent() {
  if (!S.myLocation || !S.loaded) return "";
  const rows = nearbyRows();
  const nowMin = spTime().min;
  let html = `<h3>Perto de você</h3>`;
  if (!rows.length) return html + `<p class="muted small">Nenhuma linha passando a até ${NEARBY_RADIUS_M} m nos próximos 90 minutos.</p>`;
  html += `<div class="nearby">`;
  html += rows.map((r) => `<button class="item" data-act="stop" data-i="${r.stop.idx}">
      ${badge(r.dir.line.numero)}${Fav.hasLine(r.dir.line.numero) ? icon("star", "c-star") : ""}
      <div class="grow"><div class="ellipsis small">${esc(r.dir.line.descricao)}</div>
        <small class="muted ellipsis">${esc(r.stop.label)} · ${r.distM} m · ${r.dir.label}</small></div>
      <div class="right">${r.live ? liveMinutes(r.live) : `<b>${Time.wait(r.at - nowMin)}</b><small class="muted">${Time.fmt(r.at)}</small>`}</div>
    </button>`).join("");
  return html + `</div>`;
}

function planContent() {
  const head = `<div class="row pad-x head">
    <b class="grow">${S.startMs ? `Saindo às ${Time.fmt(spTime(S.startMs).min)} · próximas 3h` : "Saindo agora · próximas 3h"}</b>
    ${S.planning ? `<div class="spinner small"></div>` : ""}</div>`;
  if (!effectiveOrigin()) return head + hint("Escolha o ponto de partida ou permita o acesso à localização.");
  if (!S.planning && !S.planResults.length) {
    return head + hint("Nenhuma opção encontrada entre esses pontos nesse horário, nem com uma troca de ônibus. Tente outro horário ou um ponto mais perto de uma via com ônibus.");
  }
  return head + S.planResults.map((o, i) => optionCard(o, i)).join("");
}

const hint = (t) => `<p class="muted pad">${esc(t)}</p>`;

const badge = (numero, big = false) =>
  `<span class="badge ${big ? "big" : ""}" style="background:${colorFor(numero)}">${icon("bus")}${esc(numero)}</span>`;
const walkMin = (m) => `<span class="walk">${icon("walk")}${m}</span>`;
const chevron = `<span class="chev">›</span>`;

function liveMinutes(live) {
  return `<div class="live"><span>${icon("live")}${live.minutes <= 1 ? "Agora" : `${live.minutes} min`}</span><small>ao vivo</small></div>`;
}

function optionCard(o, i) {
  const nowMin = spTime().min;
  let seq = walkMin(o.walkToMin);
  o.legs.forEach((leg, k) => {
    if (k > 0 && o.transferWalkMin > 0) seq += chevron + walkMin(o.transferWalkMin);
    seq += chevron + badge(leg.dir.line.numero);
  });
  seq += chevron + walkMin(o.walkFromMin);
  const desc = o.isDirect ? o.first.dir.line.descricao : `1 troca · na ${o.legs[1].boardStop.label}`;
  const upcoming = o.boardTimes.slice(0, 6).map(Time.fmt).join("  ");
  const t0 = o.trips[0];
  const right = o.live ? liveMinutes(o.live)
    : t0 ? `<b class="big-time">${Time.fmt(t0.boards[0])}</b><small class="muted">em ${Time.wait(t0.boards[0] - nowMin)}</small>` : "";
  return `<button class="item option" data-act="route" data-i="${i}">
    <div class="grow">
      <div class="seq">${seq}</div>
      <div class="ellipsis">${esc(desc)}</div>
      <small class="muted">Embarque na ${esc(o.first.boardStop.label)} · ${o.legs.reduce((a, l) => a + l.rideMin, 0)} min de ônibus</small>
      ${upcoming ? `<small class="times">${upcoming}</small>` : ""}
    </div>
    <div class="right">${right}${t0 ? `<small class="muted">chega ${Time.fmt(t0.arrival)}</small>` : ""}</div>
  </button>`;
}

function step(ic, title, sub, color) {
  return `<div class="step">${icon(ic, "", "").replace("<svg", `<svg style="fill:${color ?? "var(--muted)"}"`)}<div><div class="step-title">${esc(title)}</div>${sub ? `<small class="muted pre">${esc(sub)}</small>` : ""}</div></div>`;
}

function routeContent(o) {
  const fares = o.legs.map((l) => l.dir.line.tarifa);
  const fare = fares.every((f) => f != null) ? fares.reduce((a, b) => a + b, 0) : null;
  const meta = [o.isDirect ? o.first.dir.label : "1 troca", fare != null ? brl(fare) + (o.isDirect ? "" : " (sem integração)") : null].filter(Boolean).join(" · ");
  let html = `<div class="pad">
    <div class="row">${o.legs.map((l) => badge(l.dir.line.numero, true)).join(chevron)}</div>
    <div class="row head">
      <div class="grow">${o.trips[0] ? `<b>Chega às ${Time.fmt(o.trips[0].arrival)}</b>` : ""}<br><small class="muted">${esc(meta)}</small></div>
      ${o.live ? liveMinutes(o.live) : ""}
    </div>`;
  const first = o.first;
  const alertOn = alertIndex(first.dir, first.boardStop) >= 0;
  html += `<div class="row gap trip-actions">
      <button class="btn primary" data-act="trip-start">${icon("nav")}Iniciar viagem</button>
      <button class="btn ${alertOn ? "on" : ""}" data-act="alert-route">${icon(alertOn ? "bell" : "bellOff")}${alertOn ? "Aviso ligado" : `Avisar a ${ALERT_MIN} min`}</button>
    </div>`;
  html += step("walk", `Caminhe até a ${o.first.boardStop.label}`, `${o.walkToM} m · ${o.walkToMin} min`);
  o.legs.forEach((leg, i) => {
    if (i > 0) {
      const same = o.transferWalkM < 30;
      html += step("walk", same ? "Troque de ônibus na mesma parada" : `Caminhe até a ${leg.boardStop.label}`, same ? "" : `${o.transferWalkM} m · ${o.transferWalkMin} min`);
    }
    html += step("bus", `Pegue a linha ${leg.dir.line.numero} · ${leg.dir.label}`,
      `${leg.dir.line.descricao}\n${leg.rideMin} min · ${km(leg.distanceM)} km${leg.dir.durEstimada ? " (estimado)" : ""}`, colorFor(leg.dir.line.numero));
    html += step("place", `Desça na ${leg.alightStop.label}`, "");
  });
  html += step("walk", "Caminhe até o destino", `${o.walkFromM} m · ${o.walkFromMin} min`);
  html += `<hr>`;
  html += o.live
    ? `<p class="c-live small">Ônibus ${esc(o.live.bus.id)} da ${esc(o.first.dir.line.numero)} está a ${km(o.live.distanceM)} km do seu ponto</p>`
    : `<p class="muted small">Nenhum ônibus da ${esc(o.first.dir.line.numero)} com GPS ativo vindo para o seu ponto agora</p>`;
  if (o.isDirect) html += `<h3>Passagens previstas no ponto de embarque</h3>${timesGrid(o.boardTimes)}`;
  else html += `<h3>Próximas viagens</h3>${tripsTable(o)}`;
  html += `<p class="muted tiny">Horários calculados a partir da tabela da SEMOB e da distância do terminal até o ponto.</p></div>`;
  return html;
}

function tripContent(t) {
  const legs = t.option.legs, leg = legs[t.legIdx];
  const num = leg.dir.line.numero, me = S.myLocation;
  const nowMin = spTime().min;
  const distTo = (p) => (me ? walkM(Geo.dist(me, p)) : null);
  const walkInfo = (p) => {
    const m = distTo(p);
    return m == null ? "Sem sua localização" : `Faltam ${fmtDist(m)} · ${Math.max(walkMinutes(m), 1)} min a pé`;
  };
  // quando o ônibus chega ao ponto: ao vivo ou pela tabela
  const busInfo = () => {
    const live = liveEta(leg.dir, leg.boardAlong, S.buses);
    if (live) return `<p class="c-live strong">${icon("live")} Ônibus ao vivo a ${live.minutes <= 1 ? "menos de 1 min" : `${live.minutes} min`} do ponto</p>`;
    const next = stopDepartures(leg.boardStop, Date.now(), WINDOW_MIN, []).find((r) => r.dir === leg.dir)?.passes[0];
    return `<p class="muted small">Nenhum ônibus da ${esc(num)} com GPS vindo agora${next != null ? ` · previsto às <b>${Time.fmt(next)}</b> (${next - nowMin <= 0 ? "agora" : Time.wait(next - nowMin)})` : ""}</p>`;
  };
  let big, sub = "", extra = "", button = "";
  if (t.phase === "walk") {
    big = `Vá até a ${leg.boardStop.label}`;
    sub = walkInfo(leg.boardStop.pos);
    extra = busInfo();
    button = "Cheguei na parada";
  } else if (t.phase === "wait") {
    big = `Pegue a linha ${num}`;
    sub = `${leg.dir.label} · ${leg.dir.line.descricao}`;
    extra = busInfo();
    button = "Entrei no ônibus";
  } else if (t.phase === "ride") {
    const ahead = stopsAhead(leg, rideAlong(t, leg));
    big = ahead.length <= 1 ? "Desça na próxima!" : `Faltam ${ahead.length} paradas`;
    sub = `Desça na ${leg.alightStop.label}`;
    if (ahead.length > 1) extra = `<p class="muted small">Próxima parada: ${esc(ahead[0].label)}</p>`;
    button = "Desci do ônibus";
  } else if (t.phase === "walkEnd") {
    big = "Caminhe até o destino";
    sub = walkInfo(S.dest.pos);
    button = "Cheguei";
  } else {
    big = "Você chegou! 🎉";
    sub = S.dest?.name ?? "";
  }
  const urgent = t.phase === "ride" && big.startsWith("Desça");
  const steps = legs.map((l, i) => `<span class="${i < t.legIdx ? "done" : i === t.legIdx ? "now" : ""}">${badge(l.dir.line.numero)}</span>`).join(chevron);
  return `<div class="pad">
    <div class="row">${steps}</div>
    <div class="trip-now ${urgent ? "urgent" : ""}"><div class="trip-big">${esc(big)}</div><div class="muted">${esc(sub)}</div></div>
    ${extra}
    ${t.phase === "done"
      ? `<button class="btn primary" data-act="back">Encerrar viagem</button>`
      : `<button class="btn primary" data-act="trip-next">${button}</button>
         <p class="muted tiny">O app avança sozinho pelo GPS e avisa quando o ônibus estiver chegando e quando for a hora de descer.
           Deixe o Candango aberto durante a viagem.</p>`}
  </div>`;
}

function tripsTable(o) {
  if (!o.trips.length) return `<p class="muted small">Sem viagens nesse período.</p>`;
  return o.trips.slice(0, 12).map((t) => {
    const parts = t.boards.map((b, i) => `<b style="color:${colorFor(o.legs[i].dir.line.numero)}">${esc(o.legs[i].dir.line.numero)}</b> ${Time.fmt(b)}`).join(chevron);
    const wait = t.boards.length > 1 ? `<small class="muted">espera ${t.boards[1] - (t.boards[0] + o.first.rideMin + o.transferWalkMin)} min</small>` : "";
    return `<div class="trip-row"><span class="grow">${parts}</span>${wait}<b class="arrive">chega ${Time.fmt(t.arrival)}</b></div>`;
  }).join("");
}

function timesGrid(times, highlightFrom = null) {
  if (!times.length) return `<p class="muted small">Sem horários nesse período.</p>`;
  return `<div class="times-grid">${times.map((t) => `<span class="${highlightFrom != null && t < highlightFrom ? "past" : ""}">${Time.fmt(t)}</span>`).join("")}</div>`;
}

function lineContent(line, dirIndex) {
  const dir = line.dirs[dirIndex];
  if (!dir) return "";
  const live = liveBusesOf(line);
  const now = spTime();
  const fav = Fav.hasLine(line.numero);
  return `<div class="pad">
    <div class="row head">${badge(line.numero, true)}<div class="grow"><b>${esc(line.descricao)}</b>
      ${line.tarifa != null ? `<br><small class="muted">Tarifa ${brl(line.tarifa)}</small>` : ""}</div>
      <button class="icon-btn" data-act="fav-line" data-n="${esc(line.numero)}">${icon(fav ? "star" : "starOff", "c-star", fav ? "Tirar dos favoritos" : "Favoritar linha")}</button></div>
    ${line.dirs.length > 1 ? `<div class="chips inline">${line.dirs.map((d, i) => `<button class="chip ${i === dirIndex ? "on" : ""}" data-act="dir" data-i="${i}">${d.label}</button>`).join("")}</div>` : ""}
    <p class="small ${live.length ? "c-live" : "muted"}">${icon("live")} ${live.length ? `${live.length} ônibus desta linha no mapa agora` : "Nenhum ônibus desta linha com GPS ativo agora"}</p>
    <small class="muted">${dir.stopIdx.length} paradas · ${km(dir.lengthM)} km · ~${dir.durMin} min de viagem</small>
    <h3>Partidas do terminal hoje</h3>
    ${timesGrid(dir.departures(now.dayIdx), now.min)}
  </div>`;
}

function stopContent(stop) {
  const rows = stopDepartures(stop, Date.now(), WINDOW_MIN, S.buses);
  const nowMin = spTime().min;
  const nLines = new Set(Repo.dirsAtStop[stop.idx].map(([d]) => d.line)).size;
  const me = walkableMe(stop.pos);
  let walk = "";
  if (me) {
    const m = walkM(Geo.dist(me, stop.pos));
    walk = `<div class="c-me small strong">${icon("walk")} ${m < 1000 ? `${m} m` : `${km(m)} km`} · ${Math.max(walkMinutes(m), 1)} min a pé de você</div>`;
  }
  let html = `<div class="row pad head">
    <div class="grow"><b>${esc(stop.label)}</b><br><small class="muted">${nLines} linhas passam aqui · próximas 3h</small>${walk}</div>
    <button class="btn" data-act="go-here">Ir para cá</button></div>`;
  if (!rows.length) html += hint("Nenhuma passagem prevista nas próximas 3 horas.");
  const here = Repo.dirsAtStop[stop.idx];
  html += rows.map((r) => {
    const on = alertIndex(r.dir, stop) >= 0;
    return `<div class="item" role="button" tabindex="0" data-act="line" data-n="${esc(r.dir.line.numero)}">
      ${badge(r.dir.line.numero)}
      <div class="grow"><div class="ellipsis small">${esc(r.dir.line.descricao)}</div>
        <small class="muted ellipsis">${r.dir.label} · ${r.passes.slice(0, 4).map(Time.fmt).join("  ")}</small></div>
      <div class="right">${r.live ? liveMinutes(r.live) : r.passes[0] != null ? `<b>${Time.wait(r.passes[0] - nowMin)}</b>` : ""}</div>
      <button class="icon-btn bell ${on ? "on" : ""}" data-act="alert-stop" data-k="${here.findIndex(([d]) => d === r.dir)}">${icon(on ? "bell" : "bellOff", "", on ? "Cancelar aviso" : `Avisar quando estiver a ${ALERT_MIN} min`)}</button>
    </div>`;
  }).join("");
  return html;
}

function renderSearch() {
  const el = $("search");
  el.hidden = !S.search;
  if (S.search) renderSearchList();
}

function renderSearchList() {
  const s = S.search;
  if (!s) return;
  let html = s.searching ? `<div class="spinner small pad-x"></div>` : "";
  if (s.field) {
    if (S.myLocation) html += searchRow("myloc", "Minha localização", "", `data-act="hit-me"`);
    html += searchRow("map", "Escolher no mapa", "", `data-act="pick"`);
    if (FAV_PLACES[s.field] && Fav.place(s.field)) html += searchRow("close", `Remover ${FAV_PLACES[s.field].label}`, "", `data-act="fav-remove"`);
    if (!s.query.trim()) {
      if (!FAV_PLACES[s.field]) {
        for (const [k, f] of Object.entries(FAV_PLACES)) {
          const p = Fav.place(k);
          if (p) html += searchRow(f.icon, f.label, p.name, `data-act="hit-fav" data-k="${k}"`);
        }
      }
      Fav.recents.forEach((r, i) => { html += searchRow("history", r.name, "Recente", `data-act="hit-recent" data-i="${i}"`); });
    }
  }
  s.hits.forEach((h, i) => {
    if (h.place) html += searchRow("place", h.place.name, h.place.detail, `data-act="hit" data-i="${i}"`);
    else if (h.stop) html += searchRow("bus", h.stop.label, "Parada de ônibus", `data-act="hit" data-i="${i}"`);
    else html += `<button class="item" data-act="hit" data-i="${i}">${badge(h.line.numero)}<span class="grow clamp2">${esc(h.line.descricao)}</span></button>`;
  });
  $("search-list").innerHTML = html;
}

const searchRow = (ic, title, sub, attrs) =>
  `<button class="item" ${attrs}><span class="round">${icon(ic)}</span><span class="grow"><span class="ellipsis">${esc(title)}</span>${sub ? `<small class="muted ellipsis">${esc(sub)}</small>` : ""}</span></button>`;

function renderFab() {
  const fab = $("fab");
  fab.hidden = !(S.myLocation && !S.pickOnMapFor && S.loaded);
  const wide = matchMedia("(min-width: 900px)").matches;
  fab.style.bottom = wide ? "" : `${$("panel").offsetHeight + 12}px`;
}

// ---------- eventos ----------

document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-act]");
  if (!b || b.disabled) return;
  const act = b.dataset.act;
  const s = screen();
  switch (act) {
    case "search-origin": openSearch("origin"); break;
    case "search-dest": openSearch("dest"); break;
    case "lines": openSearch(null); break;
    case "swap": swap(); break;
    case "back": goBack(); break;
    case "clear": clearTrip(); break;
    case "start": {
      const h = b.dataset.choice === "now" ? 0 : +b.dataset.choice;
      setStart(b.dataset.choice, h ? Date.now() + h * 3_600_000 : null);
      break;
    }
    case "nearest": {
      const near = S.myLocation && Repo.stopsNear(S.myLocation, 800)[0];
      if (near) openStop(near[0]);
      else alert("Nenhuma parada a até 800 m de você.");
      break;
    }
    case "route": openRoute(S.planResults[+b.dataset.i]); break;
    case "dir": replaceTop({ ...s, dirIndex: +b.dataset.i }); renderAll(); break;
    case "line": openLine(Repo.lineByNumero.get(b.dataset.n)); break;
    case "go-here": setEndpoint("dest", { name: s.stop.label, pos: s.stop.pos }); break;
    case "hit": onSearchHit(S.search.hits[+b.dataset.i]); break;
    case "hit-me": choose(S.search.field, { name: "Minha localização", pos: S.myLocation, isMe: true }); break;
    case "hit-recent": choose(S.search.field, Fav.recents[+b.dataset.i]); break;
    case "hit-fav": { const k = b.dataset.k; choose(S.search.field, { name: FAV_PLACES[k].label, pos: Fav.place(k).pos, fav: true }); break; }
    case "fav-remove": Fav.setPlace(S.search.field, null); goBack(); break;
    case "fav-go": {
      const k = b.dataset.k, p = Fav.place(k);
      if (p) setEndpoint("dest", { name: FAV_PLACES[k].label, pos: p.pos, fav: true });
      else openSearch(k);
      break;
    }
    case "fav-edit": openSearch(b.dataset.k); break;
    case "fav-line": Fav.toggleLine(b.dataset.n); renderAll(); break;
    case "alert-stop": { const [dir, along] = Repo.dirsAtStop[s.stop.idx][+b.dataset.k]; toggleAlert(dir, along, s.stop); break; }
    case "alert-route": toggleAlert(s.option.first.dir, s.option.first.boardAlong, s.option.first.boardStop); break;
    case "alert-cancel": S.alerts.splice(+b.dataset.i, 1); renderAll(); break;
    case "toast-close": S.toast = null; renderAll(); break;
    case "trip-start": startTrip(s.option); break;
    case "trip-next": nextPhase(s); updateTrip(); renderAll(); break;
    case "stop": openStop(Repo.stops[+b.dataset.i]); break;
    case "pick": startPick(S.search.field); break;
    case "pick-me": startPick("me"); break;
    case "close-search": goBack(); break;
    case "me": mapView.center(S.myLocation, 16, mapPadding()); break;
    case "install": S.installPrompt?.prompt(); S.installPrompt = null; renderAll(); break;
  }
});

document.addEventListener("change", (e) => {
  if (e.target.id !== "start-time" || !e.target.value) return;
  const [hh, mm] = e.target.value.split(":").map(Number);
  const nowMin = spTime().min;
  let diff = hh * 60 + mm - nowMin;
  if (diff < -30) diff += 1440; // horário que já passou = amanhã
  setStart("custom", Date.now() + diff * 60_000);
});

$("search-input").addEventListener("input", (e) => onSearchQuery(e.target.value));
window.addEventListener("resize", renderFab);
window.addEventListener("beforeinstallprompt", (e) => {
  e.preventDefault();
  S.installPrompt = e;
  renderAll();
});

// ---------- início ----------

async function main() {
  renderAll();
  mapView = new MapView($("map"), { onClick: onMapClick, onLongPress: (p) => setEndpoint("dest", { name: "Ponto no mapa", pos: p }) });
  startLocation();
  poll();
  try {
    const t0 = performance.now();
    const r = await fetch("dados.json");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    Repo.load(await r.json());
    console.info(`Candango: dados carregados em ${Math.round(performance.now() - t0)} ms`);
    S.loaded = true;
  } catch (err) {
    S.loadError = `Falha ao carregar as linhas e paradas (${err.message}). Verifique a internet e recarregue.`;
  }
  await mapView.ready;
  mapView.loadedOnce = true;
  if (S.loaded) mapView.setAllStops(Repo.stops);
  replan();
  renderAll();
}

main();

// em localhost fica sem service worker, para o desenvolvimento sempre ver os arquivos novos
if ("serviceWorker" in navigator && location.hostname !== "localhost") navigator.serviceWorker.register("sw.js").catch(() => {});

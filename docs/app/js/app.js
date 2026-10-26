// Estado e telas do Candango web. Espelha ui/AppViewModel.kt e ui/Screens.kt do app Android.
import { Geo, Repo } from "./model.js";
import { plan, stopDepartures, spTime, Time, walkM, walkMinutes } from "./planner.js";
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
};

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

function setEndpoint(field, e) {
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
  S.search = { field, query: "", hits: field == null ? Repo.lines.slice(0, 40).map((line) => ({ line })) : [], searching: false };
  renderAll();
  const input = document.getElementById("search-input");
  input.value = "";
  input.placeholder = field === "origin" ? "De onde você sai?" : field === "dest" ? "Endereço, lugar ou parada" : "Número ou nome da linha";
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
  if (!q) return linesOnly ? Repo.lines.slice(0, 40).map((line) => ({ line })) : [];
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
    if (field) setEndpoint(field, { name: hit.stop.label, pos: hit.stop.pos });
    else { closeOverlays(); openStop(hit.stop); }
    return;
  }
  if (hit.place && field) setEndpoint(field, { name: hit.place.name, pos: hit.place.pos });
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
  } catch {
    S.liveError = "Sem conexão com o GPS da SEMOB";
  }
  renderAll();
  if (!document.hidden) pollTimer = setTimeout(poll, POLL_MS);
}
document.addEventListener("visibilitychange", () => {
  if (document.hidden) clearTimeout(pollTimer);
  else poll();
});

function startLocation() {
  // para testes locais: localhost:4173/app/?geo=-15.7962,-47.8838 simula a posição
  const fake = location.hostname === "localhost" && new URLSearchParams(location.search).get("geo");
  if (fake) {
    const [lat, lon] = fake.split(",").map(Number);
    S.myLocation = { lat, lon };
    return;
  }
  if (!("geolocation" in navigator)) return;
  navigator.geolocation.watchPosition(
    (p) => {
      const first = !S.myLocation;
      S.myLocation = { lat: p.coords.latitude, lon: p.coords.longitude };
      S.locationDenied = false;
      if (first && !S.origin && S.dest) replan();
      renderAll();
    },
    (err) => {
      if (err.code === err.PERMISSION_DENIED) { S.locationDenied = true; renderAll(); }
    },
    { enableHighAccuracy: true, maximumAge: 10_000, timeout: 30_000 },
  );
}

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

  if (s.type === "route") {
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
    setEndpoint(S.pickOnMapFor, { name: "Ponto no mapa", pos: latlon });
    return;
  }
  if (hit.linha && Repo.lineByNumero.has(hit.linha)) openLine(Repo.lineByNumero.get(hit.linha));
  else if (hit.stopIdx != null) openStop(Repo.stops[hit.stopIdx]);
}

// ---------- renderização ----------

const $ = (id) => document.getElementById(id);

function renderAll() {
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
    const title = s.type === "route"
      ? (s.option.isDirect ? "Linha " : "Linhas ") + s.option.legs.map((l) => l.dir.line.numero).join(" + ")
      : s.type === "line" ? `Linha ${s.line.numero}` : s.stop.label;
    html = `<div class="card titlebar"><button class="icon-btn" data-act="back">${icon("back", "", "Voltar")}</button><b>${esc(title)}</b></div>`;
  }
  if (S.pickOnMapFor) {
    html += banner(`Toque no mapa para escolher o ${S.pickOnMapFor === "origin" ? "ponto de partida" : "destino"}`, true);
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
  return `<div class="pad">
    ${liveStatus()}
    <h2>Para onde vamos?</h2>
    <p class="muted">Escolha um destino para ver as linhas que levam até lá agora e nas próximas 3 horas.
      Dica: toque e segure no mapa para marcar o destino.</p>
    <div class="row gap">
      <button class="btn" data-act="lines">${icon("bus")}Linhas</button>
      <button class="btn" data-act="nearest" ${S.myLocation ? "" : "disabled"}>${icon("myloc")}Parada mais próxima</button>
    </div>
    ${S.locationDenied ? `<p class="tip">Sem acesso à sua localização. Para ver as paradas perto de você, permita a localização para este site nas configurações do navegador.</p>` : ""}
    ${install}
  </div>`;
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
  return `<div class="pad">
    <div class="row head">${badge(line.numero, true)}<div class="grow"><b>${esc(line.descricao)}</b>
      ${line.tarifa != null ? `<br><small class="muted">Tarifa ${brl(line.tarifa)}</small>` : ""}</div></div>
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
  html += rows.map((r) => `<button class="item" data-act="line" data-n="${esc(r.dir.line.numero)}">
      ${badge(r.dir.line.numero)}
      <div class="grow"><div class="ellipsis small">${esc(r.dir.line.descricao)}</div>
        <small class="muted ellipsis">${r.dir.label} · ${r.passes.slice(0, 4).map(Time.fmt).join("  ")}</small></div>
      <div class="right">${r.live ? liveMinutes(r.live) : r.passes[0] != null ? `<b>${Time.wait(r.passes[0] - nowMin)}</b>` : ""}</div>
    </button>`).join("");
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
    case "hit-me": setEndpoint(S.search.field, { name: "Minha localização", pos: S.myLocation, isMe: true }); break;
    case "pick": startPick(S.search.field); break;
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

// Planejador de viagens (diretas e com uma baldeação). Espelha data/Planner.kt do app Android.
import { Geo, Repo } from "./model.js";

// ---------- horário de Brasília ----------

const fmtParts = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/Sao_Paulo", hourCycle: "h23", weekday: "short", hour: "2-digit", minute: "2-digit",
});
const DOW = { Mon: 0, Tue: 1, Wed: 2, Thu: 3, Fri: 4, Sat: 5, Sun: 6 };

/** Dia da semana (0 = segunda) e minutos desde 00:00, no fuso de Brasília. */
export function spTime(ms = Date.now()) {
  const p = Object.fromEntries(fmtParts.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { dayIdx: DOW[p.weekday], min: +p.hour * 60 + +p.minute };
}

export const Time = {
  /** minutos desde 00:00 de hoje -> "HH:mm" (marca "+1" se for amanhã) */
  fmt(min) {
    const m = ((min % 1440) + 1440) % 1440;
    const s = `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
    return min >= 1440 ? `${s} (+1)` : s;
  },
  /** espera em minutos -> "7 min" ou "3h16" */
  wait(min) {
    return min < 60 ? `${min} min` : `${Math.floor(min / 60)}h${String(min % 60).padStart(2, "0")}`;
  },
};

// ---------- constantes ----------

const SEARCH_RADIUS_M = 700;
const WALK_M_PER_MIN = 75;
const WALK_DETOUR = 1.25; // caminho real ≈ 25% maior que a linha reta
const MIN_RIDE_M = 300;
const TRANSFER_BUFFER_MIN = 2; // folga para descer e chegar ao outro ponto
const MAX_TRANSFER_WAIT_MIN = 60;
const TRANSFER_PENALTY_MIN = 8; // incômodo de trocar de ônibus, usado só na ordenação
const MAX_TRANSFER_CANDIDATES = 200; // muitas linhas só rodam no pico; avaliar poucas deixa a janela vazia
const MAX_TRANSFER_RESULTS = 6;

export const walkMinutes = (m) => Math.round(m / WALK_M_PER_MIN);
export const walkM = (straightM) => Math.round(straightM * WALK_DETOUR);

const rideMin = (leg) => Math.round(leg.dir.minutesBetween(leg.boardAlong, leg.alightAlong));

function makeLeg(dir, boardStop, alightStop, boardAlong, alightAlong) {
  const leg = { dir, boardStop, alightStop, boardAlong, alightAlong };
  leg.rideMin = rideMin(leg);
  leg.distanceM = alightAlong - boardAlong;
  return leg;
}

function makeOption(legs, walkToM, transferWalkM, walkFromM, trips, live) {
  return {
    legs, walkToM, transferWalkM, walkFromM, trips, live,
    first: legs[0],
    last: legs[legs.length - 1],
    isDirect: legs.length === 1,
    walkToMin: walkMinutes(walkToM),
    walkFromMin: walkMinutes(walkFromM),
    transferWalkMin: walkMinutes(transferWalkM),
    /** horários de embarque no primeiro ônibus */
    boardTimes: trips.map((t) => t.boards[0]),
    /** identifica a opção entre replanejamentos (para manter a tela de rota aberta) */
    key: legs.map((l) => `${l.dir.line.numero}/${l.dir.sentido}/${l.boardStop.idx}`).join("|"),
  };
}

/** Contexto de um planejamento: janela de horário e cache de passagens. */
class Ctx {
  constructor(startMs, windowMin, buses) {
    const t = spTime(startMs);
    this.dayIdx = t.dayIdx;
    this.startMin = t.min;
    this.windowMin = windowMin;
    this.buses = buses;
    this.cache = new Map();
  }

  /** passagens num ponto do trajeto, de ontem a amanhã, em minutos relativos a hoje */
  passes(dir, along) {
    let key = this.cache.get(dir);
    if (!key) this.cache.set(dir, (key = new Map()));
    let p = key.get(along);
    if (!p) {
      const y = (this.dayIdx + 6) % 7, tm = (this.dayIdx + 1) % 7;
      const set = new Set([
        ...dir.passingTimes(y, along).map((x) => x - 1440),
        ...dir.passingTimes(this.dayIdx, along),
        ...dir.passingTimes(tm, along).map((x) => x + 1440),
      ]);
      p = [...set].sort((a, b) => a - b);
      key.set(along, p);
    }
    return p;
  }

  inWindow(passes, walkToMin) {
    const lo = this.startMin + walkToMin, hi = this.startMin + this.windowMin;
    return passes.filter((x) => x >= lo && x <= hi);
  }
}

/**
 * Opções de origin a dest com embarque entre startMs e startMs + windowMin minutos:
 * linhas diretas e, quando ajudam, combinações com uma baldeação.
 */
export function plan(origin, dest, startMs, windowMin, buses) {
  const nearO = Repo.stopsNear(origin, SEARCH_RADIUS_M);
  const nearD = Repo.stopsNear(dest, SEARCH_RADIUS_M);
  if (!nearO.length || !nearD.length) return [];

  const ctx = new Ctx(startMs, windowMin, buses);
  const direct = directOptions(nearO, nearD, ctx);
  const bestDirect = direct.length ? Math.min(...direct.map(sortKey)) : null;
  const transfers = transferOptions(nearO, nearD, ctx, new Set(direct.map((o) => o.first.dir.line)))
    // com boas diretas, só mostra baldeação que chega antes
    .filter((o) => bestDirect == null || sortKey(o) < bestDirect)
    .slice(0, direct.length ? MAX_TRANSFER_RESULTS : MAX_TRANSFER_RESULTS + 2);

  return [...direct, ...transfers].sort((a, b) => sortKey(a) - sortKey(b));
}

// ---------- diretas ----------

function directOptions(nearO, nearD, ctx) {
  const destDist = new Map(nearD.map(([s, d]) => [s.idx, d]));
  // melhor par embarque/desembarque por sentido de linha
  const best = new Map(); // dir -> {leg, walkTo, walkFrom, cost}
  for (const [oStop, oDist] of nearO) {
    for (const [dir, oAlong] of Repo.dirsAtStop[oStop.idx]) {
      for (let k = 0; k < dir.stopIdx.length; k++) {
        const dAlong = dir.stopAlong[k];
        if (dAlong - oAlong < MIN_RIDE_M) continue;
        const dDist = destDist.get(dir.stopIdx[k]);
        if (dDist == null) continue;
        const leg = makeLeg(dir, oStop, Repo.stops[dir.stopIdx[k]], oAlong, dAlong);
        const walkTo = walkM(oDist), walkFrom = walkM(dDist);
        const cost = walkMinutes(walkTo) + walkMinutes(walkFrom) + leg.rideMin;
        const cur = best.get(dir);
        if (!cur || cost < cur.cost) best.set(dir, { leg, walkTo, walkFrom, cost });
      }
    }
  }

  const byLine = new Map();
  for (const { leg, walkTo, walkFrom } of best.values()) {
    const boards = ctx.inWindow(ctx.passes(leg.dir, leg.boardAlong), walkMinutes(walkTo));
    const o = makeOption(
      [leg], walkTo, 0, walkFrom,
      boards.map((b) => ({ boards: [b], arrival: b + leg.rideMin + walkMinutes(walkFrom) })),
      liveEta(leg.dir, leg.boardAlong, ctx.buses),
    );
    if (!o.trips.length && !o.live) continue;
    // uma opção por linha: o sentido que chega primeiro
    const cur = byLine.get(leg.dir.line);
    if (!cur || sortKey(o) < sortKey(cur)) byLine.set(leg.dir.line, o);
  }
  return [...byLine.values()];
}

// ---------- uma baldeação ----------

function transferOptions(nearO, nearD, ctx, directLines) {
  // 1) lado do destino: para cada parada "a montante", o melhor 2º trecho de cada sentido de linha
  const secondByStop = new Map(); // stopIdx -> Map(dirB -> {leg, walkFromM, cost})
  for (const [dStop, dDist] of nearD) {
    for (const [dirB, dAlong] of Repo.dirsAtStop[dStop.idx]) {
      if (directLines.has(dirB.line)) continue; // já resolve sozinha
      const walkFrom = walkM(dDist);
      for (let k = 0; k < dirB.stopIdx.length; k++) {
        const uAlong = dirB.stopAlong[k];
        if (dAlong - uAlong < MIN_RIDE_M) continue;
        const leg = makeLeg(dirB, Repo.stops[dirB.stopIdx[k]], dStop, uAlong, dAlong);
        const cand = { leg, walkFromM: walkFrom, cost: leg.rideMin + walkMinutes(walkFrom) };
        let m = secondByStop.get(dirB.stopIdx[k]);
        if (!m) secondByStop.set(dirB.stopIdx[k], (m = new Map()));
        const cur = m.get(dirB);
        if (!cur || cand.cost < cur.cost) m.set(dirB, cand);
      }
    }
  }
  if (!secondByStop.size) return [];

  // 2) lado da origem: desce do 1º ônibus em algum ponto e caminha até um ponto do 2º
  const best = new Map(); // dirA -> Map(dirB -> cand)
  for (const [oStop, oDist] of nearO) {
    const walkTo = walkM(oDist);
    for (const [dirA, oAlong] of Repo.dirsAtStop[oStop.idx]) {
      if (directLines.has(dirA.line)) continue;
      for (let k = 0; k < dirA.stopIdx.length; k++) {
        const tAlong = dirA.stopAlong[k];
        if (tAlong - oAlong < MIN_RIDE_M) continue;
        const alight = Repo.stops[dirA.stopIdx[k]];
        let legA = null;
        for (const [tStop, tDist] of Repo.transferNeighbors(alight)) {
          const seconds = secondByStop.get(tStop.idx);
          if (!seconds) continue;
          for (const [dirB, second] of seconds) {
            if (dirB.line === dirA.line) continue;
            legA ??= makeLeg(dirA, oStop, alight, oAlong, tAlong);
            const transferWalk = walkM(tDist);
            const cost = walkMinutes(walkTo) + legA.rideMin + walkMinutes(transferWalk) + second.cost;
            let m = best.get(dirA);
            if (!m) best.set(dirA, (m = new Map()));
            const cur = m.get(dirB);
            if (!cur || cost < cur.cost) m.set(dirB, { legA, walkTo, transferWalk, second, cost });
          }
        }
      }
    }
  }

  // 3) só os candidatos mais promissores ganham o encadeamento de horários (a parte cara)
  const cands = [];
  for (const m of best.values()) for (const c of m.values()) cands.push(c);
  cands.sort((a, b) => a.cost - b.cost);

  const opts = [];
  for (const c of cands.slice(0, MAX_TRANSFER_CANDIDATES)) {
    const legB = c.second.leg;
    const walkToMin = walkMinutes(c.walkTo);
    const transferMin = walkMinutes(c.transferWalk);
    const walkFromMin = walkMinutes(c.second.walkFromM);
    const passesB = ctx.passes(legB.dir, legB.boardAlong);
    // para cada 2º ônibus, fica com o 1º ônibus mais tarde que ainda o alcança (menos espera)
    const byB = new Map();
    for (const a of ctx.inWindow(ctx.passes(c.legA.dir, c.legA.boardAlong), walkToMin)) {
      const ready = a + c.legA.rideMin + transferMin + TRANSFER_BUFFER_MIN;
      const b = passesB.find((x) => x >= ready);
      if (b == null || b - ready > MAX_TRANSFER_WAIT_MIN) continue;
      byB.set(b, { boards: [a, b], arrival: b + legB.rideMin + walkFromMin });
    }
    if (!byB.size) continue;
    opts.push(makeOption(
      [c.legA, legB], c.walkTo, c.transferWalk, c.second.walkFromM,
      [...byB.values()].sort((x, y) => x.boards[0] - y.boards[0]),
      liveEta(c.legA.dir, c.legA.boardAlong, ctx.buses),
    ));
  }
  opts.sort((a, b) => sortKey(a) - sortKey(b));
  // evita repetir a mesma dupla de linhas em sentidos/pontos diferentes
  const seen = new Set();
  return opts.filter((o) => {
    const k = o.legs.map((l) => l.dir.line.numero).join("+");
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

// ---------- ordenação e tempo real ----------

/** Chegada estimada ao destino (minutos desde 00:00) + penalidade por baldeação. */
export function sortKey(o) {
  const nowMin = spTime().min;
  let arrival;
  if (o.isDirect) {
    const board = o.live && o.live.minutes >= o.walkToMin ? nowMin + o.live.minutes : o.trips[0]?.boards[0];
    arrival = board == null ? null : board + o.first.rideMin + o.walkFromMin;
  } else {
    arrival = o.trips[0]?.arrival;
  }
  if (arrival == null) return Number.MAX_SAFE_INTEGER;
  return arrival + (o.legs.length - 1) * TRANSFER_PENALTY_MIN;
}

/** Ônibus da linha, no mesmo sentido, que ainda não passou pelo ponto alongM. */
export function liveEta(dir, alongM, buses) {
  const now = Date.now();
  let best = null;
  for (const bus of buses) {
    if (bus.linha !== dir.line.numero || now - bus.timeMs > 10 * 60_000) continue;
    const p = dir.project(bus.pos);
    if (p.distM > 60) continue;
    if (bus.heading != null && (bus.speedKmh ?? 0) > 3 && Geo.angleDiff(bus.heading, p.bearing) > 75) continue;
    const gap = alongM - p.alongM;
    if (gap < -50) continue;
    const g = Math.max(gap, 0);
    if (!best || g < best.distanceM) best = { bus, minutes: Math.round(dir.minutesBetween(0, g)), distanceM: g };
  }
  return best;
}

/** Próximas passagens de cada sentido de linha numa parada, como {dir, passes, live}. */
export function stopDepartures(stop, startMs, windowMin, buses) {
  const { dayIdx, min: startMin } = spTime(startMs);
  const tm = (dayIdx + 1) % 7;
  return Repo.dirsAtStop[stop.idx]
    .map(([dir, along]) => {
      const set = new Set([...dir.passingTimes(dayIdx, along), ...dir.passingTimes(tm, along).map((x) => x + 1440)]);
      const passes = [...set].filter((x) => x >= startMin && x <= startMin + windowMin).sort((a, b) => a - b);
      return { dir, passes, live: liveEta(dir, along, buses) };
    })
    .filter((r) => r.passes.length || r.live)
    .sort((a, b) => rowKey(a, startMin) - rowKey(b, startMin));
}

const rowKey = (r, startMin) => (r.live ? r.live.minutes + startMin : r.passes[0] ?? Number.MAX_SAFE_INTEGER);

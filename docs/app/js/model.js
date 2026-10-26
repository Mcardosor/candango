// Dados fixos (dados.json, gerado por tools/build-data.mjs) e geometria.
// Espelha data/Model.kt do app Android.

export const M_PER_DEG = 111_320;
const RAD = Math.PI / 180;

export const Geo = {
  dist(a, b) {
    const k = Math.cos(((a.lat + b.lat) / 2) * RAD);
    const dx = (b.lon - a.lon) * k;
    const dy = b.lat - a.lat;
    return Math.sqrt(dx * dx + dy * dy) * M_PER_DEG;
  },
  /** Rumo de a para b em graus (0 = norte, sentido horário). */
  bearing(a, b) {
    const k = Math.cos(((a.lat + b.lat) / 2) * RAD);
    const deg = Math.atan2((b.lon - a.lon) * k, b.lat - a.lat) / RAD;
    return (deg + 360) % 360;
  },
  angleDiff(a, b) {
    const d = Math.abs(a - b) % 360;
    return d > 180 ? 360 - d : d;
  },
};

export function decodePolyline(s) {
  const out = [];
  let i = 0, lat = 0, lon = 0;
  const next = () => {
    let shift = 0, result = 0, b;
    do {
      b = s.charCodeAt(i++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };
  while (i < s.length) {
    lat += next();
    lon += next();
    out.push({ lat: lat / 1e5, lon: lon / 1e5 });
  }
  return out;
}

export class Stop {
  constructor(idx, cod, pos) {
    this.idx = idx;
    this.cod = cod;
    this.pos = pos;
  }
  get label() { return `Parada ${this.cod}`; }
}

export class Line {
  constructor(numero, descricao, tarifa) {
    this.numero = numero;
    this.descricao = descricao;
    this.tarifa = tarifa ?? null;
    this.dirs = [];
  }
}

/** Um sentido (IDA, VOLTA ou CIRCULAR) de uma linha. */
export class Dir {
  constructor(line, j) {
    this.line = line;
    this.sentido = j.s;
    this._encoded = j.shape;
    this.lengthM = j.len;
    this.durMin = j.dur;
    this.durEstimada = j.durEstimada ?? true;
    this.stopIdx = j.stops.map((p) => p[0]);
    this.stopAlong = j.stops.map((p) => p[1]);
    /** máscara de dias (seg..dom, "S"/"N") -> partidas em minutos desde 00:00 */
    this.times = j.times;
    this._depCache = new Map();
  }

  get shape() {
    return (this._shape ??= decodePolyline(this._encoded));
  }

  /** Distância acumulada no trajeto simplificado, já escalada para o comprimento oficial. */
  get cum() {
    if (!this._cum) {
      const sh = this.shape;
      const c = new Float64Array(sh.length);
      for (let i = 1; i < sh.length; i++) c[i] = c[i - 1] + Geo.dist(sh[i - 1], sh[i]);
      const last = c[c.length - 1];
      const scale = last > 0 ? this.lengthM / last : 1;
      for (let i = 0; i < c.length; i++) c[i] *= scale;
      this._cum = c;
    }
    return this._cum;
  }

  get speedMPerMin() { return this.lengthM / Math.max(this.durMin, 1); }

  minutesBetween(alongFrom, alongTo) { return (alongTo - alongFrom) / this.speedMPerMin; }

  project(p) {
    const sh = this.shape, cum = this.cum;
    let best = { distM: Infinity, alongM: 0, bearing: 0 };
    const k = Math.cos(p.lat * RAD);
    for (let i = 1; i < sh.length; i++) {
      const a = sh[i - 1], b = sh[i];
      const bx = (b.lon - a.lon) * k, by = b.lat - a.lat;
      const px = (p.lon - a.lon) * k, py = p.lat - a.lat;
      const len2 = bx * bx + by * by;
      const t = len2 > 0 ? Math.min(1, Math.max(0, (px * bx + py * by) / len2)) : 0;
      const d = Math.hypot(px - t * bx, py - t * by) * M_PER_DEG;
      if (d < best.distM) {
        best = { distM: d, alongM: Math.round(cum[i - 1] + t * (cum[i] - cum[i - 1])), bearing: Geo.bearing(a, b) };
      }
    }
    return best;
  }

  /** Trecho do trajeto entre duas distâncias ao longo dele. */
  shapeBetween(fromM, toM) {
    const sh = this.shape, cum = this.cum;
    const out = [];
    for (let i = 1; i < sh.length; i++) {
      const a = cum[i - 1], b = cum[i];
      if (b < fromM) continue;
      if (a > toM) break;
      if (!out.length) out.push(this._interp(i, Math.max(fromM, a)));
      out.push(b <= toM ? sh[i] : this._interp(i, toM));
    }
    return out;
  }

  _interp(i, m) {
    const sh = this.shape, cum = this.cum;
    const a = sh[i - 1], b = sh[i];
    const span = cum[i] - cum[i - 1];
    const t = span > 0 ? Math.min(1, Math.max(0, (m - cum[i - 1]) / span)) : 0;
    return { lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t };
  }

  /** Partidas do terminal num dia da semana (0 = segunda), em minutos desde 00:00. */
  departures(dayIdx) {
    let d = this._depCache.get(dayIdx);
    if (!d) {
      const set = new Set();
      for (const [mask, list] of Object.entries(this.times)) if (mask[dayIdx] === "S") for (const t of list) set.add(t);
      d = [...set].sort((x, y) => x - y);
      this._depCache.set(dayIdx, d);
    }
    return d;
  }

  /** Horário estimado de passagem a [alongM] metros do início, para as partidas do dia. */
  passingTimes(dayIdx, alongM) {
    const offset = Math.round(alongM / this.speedMPerMin);
    return this.departures(dayIdx).map((t) => t + offset);
  }

  get label() {
    return this.sentido === "IDA" ? "Ida" : this.sentido === "VOLTA" ? "Volta" : "Circular";
  }
}

// grade de ~220 m para buscas de proximidade rápidas
const CELL_DEG = 0.002;
// terminais grandes (Rodoviária, Gama, Taguatinga) têm plataformas de chegada e saída a 200–400 m umas das outras
export const TRANSFER_RADIUS_M = 400;

export const Repo = {
  geradoEm: "",
  stops: [],
  lines: [],
  lineByNumero: new Map(),
  /** para cada parada, os pares [dir, along] das linhas que passam nela */
  dirsAtStop: [],
  _grid: new Map(),
  _neighbors: new Map(),

  load(json) {
    this.geradoEm = json.geradoEm;
    this.stops = json.stops.map((a, i) => new Stop(i, String(a[0]), { lat: a[1], lon: a[2] }));
    const atStop = this.stops.map(() => []);
    this.lines = json.lines.map((lj) => {
      const line = new Line(lj.n, lj.d, lj.tarifa);
      line.dirs = lj.dirs.map((dj) => {
        const dir = new Dir(line, dj);
        for (let k = 0; k < dir.stopIdx.length; k++) atStop[dir.stopIdx[k]].push([dir, dir.stopAlong[k]]);
        return dir;
      });
      return line;
    });
    this.lineByNumero = new Map(this.lines.map((l) => [l.numero, l]));
    this.dirsAtStop = atStop;
    for (const s of this.stops) {
      const key = cellKey(Math.floor(s.pos.lat / CELL_DEG), Math.floor(s.pos.lon / CELL_DEG));
      let cell = this._grid.get(key);
      if (!cell) this._grid.set(key, (cell = []));
      cell.push(s);
    }
  },

  /** Paradas a até radiusM de p, da mais próxima à mais distante, como [stop, distM]. */
  stopsNear(p, radiusM) {
    const r = Math.floor(radiusM / (M_PER_DEG * 0.96) / CELL_DEG) + 1; // cos(15,8°) ≈ 0,96
    const cy = Math.floor(p.lat / CELL_DEG), cx = Math.floor(p.lon / CELL_DEG);
    const out = [];
    for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
      const cell = this._grid.get(cellKey(cy + dy, cx + dx));
      if (!cell) continue;
      for (const s of cell) {
        const d = Geo.dist(s.pos, p);
        if (d <= radiusM) out.push([s, d]);
      }
    }
    return out.sort((a, b) => a[1] - b[1]);
  },

  /** Paradas a até TRANSFER_RADIUS_M de uma parada (inclui ela mesma), para baldeação a pé. */
  transferNeighbors(stop) {
    let n = this._neighbors.get(stop.idx);
    if (!n) this._neighbors.set(stop.idx, (n = this.stopsNear(stop.pos, TRANSFER_RADIUS_M)));
    return n;
  },
};

function cellKey(cy, cx) { return cy * 100000 + cx; }

// GPS dos ônibus (SEMOB-DF) e busca de endereços (Photon/OpenStreetMap). Espelha data/LiveApi.kt.

const GPS_URL = "https://www.sistemas.dftrans.df.gov.br/service/gps/operacoes";
const PHOTON_URL = "https://photon.komoot.io/api/";

const TIMEOUT_MS = 12_000;

async function getJson(url, signal) {
  // sem limite de tempo, uma resposta que nunca chega travava as atualizações para sempre
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  signal?.addEventListener("abort", () => ctl.abort());
  try {
    const r = await fetch(url, { headers: { Accept: "application/json" }, signal: ctl.signal });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.json();
  } finally {
    clearTimeout(timer);
  }
}

/** Ônibus com GPS: {id, linha, pos, timeMs, speedKmh, heading}. */
export async function fetchBuses() {
  const ops = await getJson(GPS_URL);
  if (!Array.isArray(ops)) throw new Error("resposta inesperada do GPS");
  const out = [];
  for (const op of ops) {
    for (const v of op.veiculos ?? []) {
      const loc = v.localizacao;
      if (!loc || !loc.latitude) continue;
      out.push({
        id: v.numero ?? "",
        linha: v.linha?.trim() || null,
        pos: { lat: loc.latitude, lon: loc.longitude },
        timeMs: v.horario ?? 0,
        speedKmh: v.velocidade?.valor ?? null,
        heading: v.direcao ?? null,
      });
    }
  }
  return out;
}

/** Busca de endereços limitada ao Distrito Federal: {name, detail, pos}. */
export async function searchPlaces(query, signal) {
  const url = `${PHOTON_URL}?q=${encodeURIComponent(query)}&limit=8&lang=default&lat=-15.79&lon=-47.88&bbox=-48.30,-16.06,-47.30,-15.49`;
  const root = await getJson(url, signal);
  const seen = new Set();
  const out = [];
  for (const f of root.features ?? []) {
    const c = f.geometry?.coordinates;
    if (!c) continue;
    const p = f.properties ?? {};
    const name = p.name || [p.street, p.housenumber].filter(Boolean).join(", ");
    if (!name) continue;
    const detail = [...new Set([p.street !== name ? p.street : null, p.district, p.city].filter(Boolean))].join(" · ");
    // o OSM repete o mesmo lugar (bairro, rua, ponto)
    const key = name + "|" + detail;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ name, detail, pos: { lat: c[1], lon: c[0] } });
  }
  return out;
}

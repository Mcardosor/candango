// Mapa (MapLibre GL JS). Espelha ui/MapController.kt do app Android.
/* global maplibregl */

const STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
export const BRASILIA = { lat: -15.7939, lon: -47.8828 };
const FONT = ["Noto Sans Bold"];
export const ME_COLOR = "#1E88E5";
const MAX_FIT_ZOOM = 17;
const LONG_PRESS_MS = 550;

const fc = (features) => ({ type: "FeatureCollection", features });
const pt = (p, props = {}) => ({ type: "Feature", geometry: { type: "Point", coordinates: [p.lon, p.lat] }, properties: props });
const ln = (pts, props) => ({ type: "Feature", geometry: { type: "LineString", coordinates: pts.map((p) => [p.lon, p.lat]) }, properties: props });

export class MapView {
  /**
   * @param container elemento do mapa
   * @param handlers {onClick(latlon, {stopIdx, linha}), onLongPress(latlon)}
   */
  constructor(container, handlers) {
    this.handlers = handlers;
    this.map = new maplibregl.Map({
      container,
      style: STYLE_URL,
      center: [BRASILIA.lon, BRASILIA.lat],
      zoom: 11,
      attributionControl: { compact: true },
      dragRotate: false,
      pitchWithRotate: false,
    });
    this.map.touchZoomRotate.disableRotation();
    this.ready = new Promise((resolve) => this.map.on("load", () => { this._setup(); resolve(); }));
  }

  _setup() {
    const m = this.map;
    for (const id of ["todas-paradas", "rotas", "paradas", "onibus", "marcadores", "eu"]) {
      m.addSource(id, { type: "geojson", data: fc([]) });
    }

    // todas as paradas, só com zoom de rua (tocáveis)
    m.addLayer({
      id: "todas-paradas-camada", type: "circle", source: "todas-paradas", minzoom: 14.5,
      paint: { "circle-radius": 4, "circle-color": "#FFFFFF", "circle-stroke-color": "#0B8A5F", "circle-stroke-width": 2 },
    });

    const solid = ["==", ["get", "dashed"], false];
    m.addLayer({
      id: "rotas-contorno", type: "line", source: "rotas", filter: solid,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": "#FFFFFF", "line-width": 9 },
    });
    m.addLayer({
      id: "rotas-linha", type: "line", source: "rotas", filter: solid,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": ["get", "color"], "line-width": 6 },
    });
    m.addLayer({
      id: "rotas-pe", type: "line", source: "rotas", filter: ["==", ["get", "dashed"], true],
      layout: { "line-cap": "round" },
      paint: { "line-color": ["get", "color"], "line-width": 4, "line-dasharray": [0.5, 1.5] },
    });

    m.addLayer({
      id: "paradas-camada", type: "circle", source: "paradas",
      paint: { "circle-radius": 5, "circle-color": "#FFFFFF", "circle-stroke-color": ["get", "color"], "circle-stroke-width": 2.5 },
    });

    m.addLayer({
      id: "onibus-camada", type: "circle", source: "onibus",
      paint: {
        "circle-radius": ["case", ["get", "highlight"], 11, 7],
        "circle-color": ["get", "color"], "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 2,
      },
    });
    m.addLayer({
      id: "onibus-rotulo", type: "symbol", source: "onibus", minzoom: 12.5,
      layout: { "text-field": ["get", "label"], "text-font": FONT, "text-size": 11, "text-offset": [0, 1.5] },
      paint: { "text-color": "#1B1B1F", "text-halo-color": "#FFFFFF", "text-halo-width": 1.5 },
    });

    m.addLayer({
      id: "marcadores-camada", type: "circle", source: "marcadores",
      paint: {
        "circle-radius": 8,
        "circle-color": ["match", ["get", "kind"], "origem", "#2E7D32", "destino", "#C62828", "#455A64"],
        "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 3,
      },
    });
    m.addLayer({
      id: "marcadores-rotulo", type: "symbol", source: "marcadores",
      layout: { "text-field": ["get", "label"], "text-font": FONT, "text-size": 12, "text-offset": [0, -1.6] },
      paint: { "text-color": "#1B1B1F", "text-halo-color": "#FFFFFF", "text-halo-width": 2 },
    });

    // "você está aqui": halo translúcido + ponto azul maior que os ônibus, sempre por cima de tudo
    m.addLayer({
      id: "eu-halo", type: "circle", source: "eu",
      paint: { "circle-radius": 22, "circle-color": ME_COLOR, "circle-opacity": 0.18, "circle-stroke-color": ME_COLOR, "circle-stroke-width": 1 },
    });
    m.addLayer({
      id: "eu-ponto", type: "circle", source: "eu",
      paint: { "circle-radius": 9, "circle-color": ME_COLOR, "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 3.5 },
    });

    for (const id of ["onibus-camada", "paradas-camada", "todas-paradas-camada"]) {
      m.on("mouseenter", id, () => (m.getCanvas().style.cursor = "pointer"));
      m.on("mouseleave", id, () => (m.getCanvas().style.cursor = ""));
    }
    m.on("click", (e) => this.handlers.onClick(toLatLon(e.lngLat), this._hitTest(e.point)));
    // toque longo (celular) ou clique direito (computador) marca o destino
    m.on("contextmenu", (e) => this.handlers.onLongPress(toLatLon(e.lngLat)));
    let timer = null;
    const cancel = () => { clearTimeout(timer); timer = null; };
    m.on("touchstart", (e) => {
      cancel();
      if (e.originalEvent.touches.length !== 1) return;
      timer = setTimeout(() => this.handlers.onLongPress(toLatLon(e.lngLat)), LONG_PRESS_MS);
    });
    for (const ev of ["touchend", "touchcancel", "touchmove", "movestart", "zoomstart"]) m.on(ev, cancel);
  }

  /** O que há sob o toque: uma parada (índice) e/ou a linha de um ônibus. */
  _hitTest(p) {
    const box = [[p.x - 18, p.y - 18], [p.x + 18, p.y + 18]];
    const bus = this.map.queryRenderedFeatures(box, { layers: ["onibus-camada"] })[0]?.properties.linha || null;
    const stop = this.map.queryRenderedFeatures(box, { layers: ["paradas-camada", "todas-paradas-camada"] })[0]?.properties.idx;
    return { stopIdx: stop ?? null, linha: bus };
  }

  _set(id, features) { this.map.getSource(id)?.setData(fc(features)); }

  setAllStops(stops) { this._set("todas-paradas", stops.map((s) => pt(s.pos, { idx: s.idx }))); }

  setLines(lines) {
    this._set("rotas", lines.filter((l) => l.points.length >= 2).map((l) => ln(l.points, { color: l.color, dashed: !!l.dashed })));
  }

  /** paradas destacadas, cada uma com a cor da sua linha: [[stop, color], ...] */
  setColoredStops(list) { this._set("paradas", list.map(([s, color]) => pt(s.pos, { color, idx: s.idx }))); }

  setBuses(list) {
    this._set("onibus", list.map(({ bus, color, highlight }) =>
      pt(bus.pos, { color, label: bus.linha ?? "", linha: bus.linha ?? "", highlight: !!highlight })));
  }

  setMarkers(list) { this._set("marcadores", list.map((m) => pt(m.pos, { kind: m.kind, label: m.label ?? "" }))); }

  setMe(p) { this._set("eu", p ? [pt(p)] : []); }

  /** Enquadra os pontos na área livre do mapa (padding = espaço ocupado pelos painéis). */
  fit(points, padding) {
    if (!points.length) return;
    if (points.length === 1) return this.center(points[0], 15, padding);
    const b = new maplibregl.LngLatBounds();
    for (const p of points) b.extend([p.lon, p.lat]);
    // pontos muito próximos (ex.: você a 50 m da parada) dariam zoom exagerado
    this.map.fitBounds(b, { padding: pad(padding, 60), maxZoom: MAX_FIT_ZOOM, duration: 600 });
  }

  center(p, zoom, padding) {
    // offset (e não padding): padding na câmera fica gravado e se soma ao do próximo fitBounds
    const offset = [(padding.left - padding.right) / 2, (padding.top - padding.bottom) / 2];
    this.map.easeTo({ center: [p.lon, p.lat], zoom, offset, duration: 600 });
  }

  get cameraCenter() { return toLatLon(this.map.getCenter()); }
}

const toLatLon = (ll) => ({ lat: ll.lat, lon: ll.lng });
const pad = (p, extra) => ({ top: p.top + extra, bottom: p.bottom + extra, left: p.left + extra, right: p.right + extra });

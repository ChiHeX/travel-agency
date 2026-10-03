// 天地图使用 Web Mercator WMTS，与 Leaflet 默认投影一致。
export function mapTileSources(provider, key) {
  if (provider === 'osm') {
    return [{
      url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      options: {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
      }
    }]
  }
  if (!key) return []
  return ['vec', 'cva'].map((layer, index) => ({
    url: `https://t{s}.tianditu.gov.cn/${layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=${layer}&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=${encodeURIComponent(key)}`,
    options: {
      subdomains: '01234567',
      minNativeZoom: 1,
      maxNativeZoom: 18,
      maxZoom: 19,
      zIndex: index + 1,
      attribution: index === 0 ? '&copy; <a href="https://www.tianditu.gov.cn/">天地图</a>' : ''
    }
  }))
}

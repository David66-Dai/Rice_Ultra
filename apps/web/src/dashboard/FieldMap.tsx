import riceArea from '../assets/rice_area.png'
import { MAP_LAYERS } from './mock'
import type { MapLayer, Plot } from './mock'
import { STATUS_LABEL } from './mock'

const TOOLS = [
  { id: 'N', label: '北' },
  { id: '+', label: '放大' },
  { id: '−', label: '缩小' },
  { id: '层', label: '图层' },
]

function plotCenter(plot: Plot) {
  return {
    x: plot.hotspot.left + plot.hotspot.width / 2,
    y: plot.hotspot.top + plot.hotspot.height / 2,
  }
}

export function FieldMap({
  plots,
  selectedId,
  layer,
  onSelect,
  onLayer,
}: {
  plots: Plot[]
  selectedId: string
  layer: MapLayer
  onSelect: (id: string) => void
  onLayer: (id: MapLayer) => void
}) {
  const selected = plots.find((p) => p.id === selectedId) ?? plots[0]
  const pop = plotCenter(selected)
  const counts = {
    normal: plots.filter((p) => p.status === 'normal').length,
    warning: plots.filter((p) => p.status === 'warning').length,
    offline: plots.filter((p) => p.status === 'offline').length,
  }

  return (
    <section className="map">
      <header className="map__head">
        <h3>农田地块地图</h3>
        <div className="map__tabs" role="tablist" aria-label="地图图层">
          {MAP_LAYERS.map((item) => (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={layer === item.id}
              className={layer === item.id ? 'is-on' : ''}
              onClick={() => onLayer(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>
      </header>

      <div className="map__stage">
        <img className="map__photo" src={riceArea} alt="示范基地稻田航拍" />
        <div className={`map__tint map__tint--${layer}`} aria-hidden="true" />

        {plots.map((plot) => (
          <button
            key={plot.id}
            type="button"
            className={`map__hot map__hot--${plot.status}${plot.id === selectedId ? ' is-sel' : ''}`}
            style={{
              left: `${plot.hotspot.left}%`,
              top: `${plot.hotspot.top}%`,
              width: `${plot.hotspot.width}%`,
              height: `${plot.hotspot.height}%`,
            }}
            onClick={() => onSelect(plot.id)}
          >
            <span>{plot.id}</span>
          </button>
        ))}

        <div className="map__tools" aria-label="地图工具">
          {TOOLS.map((tool) => (
            <button key={tool.id} type="button" title={tool.label}>
              {tool.id}
            </button>
          ))}
        </div>

        <div className="map__pop" style={{ left: `${pop.x}%`, top: `${Math.max(pop.y - 6, 8)}%` }}>
          <div className="map__pop-h">
            <strong>
              {selected.id} · {selected.name}
            </strong>
            <span className={`tag tag--${selected.status}`}>{STATUS_LABEL[selected.status]}</span>
          </div>
          <dl>
            <div>
              <dt>面积</dt>
              <dd>{selected.area} 亩</dd>
            </div>
            <div>
              <dt>作物</dt>
              <dd>{selected.crop}</dd>
            </div>
            <div>
              <dt>设备</dt>
              <dd>
                {selected.devicesOnline}/{selected.devices} 在线
              </dd>
            </div>
          </dl>
        </div>

        <footer className="map__legend">
          <span>
            <i className="dot dot--ok" />
            正常 {counts.normal}
          </span>
          <span>
            <i className="dot dot--warn" />
            预警 {counts.warning}
          </span>
          <span>
            <i className="dot dot--off" />
            离线 {counts.offline}
          </span>
          <span className="map__legend-sep">示范基地航拍 · 点击地块查看详情</span>
        </footer>
      </div>
    </section>
  )
}

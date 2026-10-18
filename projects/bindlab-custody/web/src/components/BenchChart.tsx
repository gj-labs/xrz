import { useState } from 'react'
import type { BenchRow } from '../api'

const SIZES = ['8M', '35M', '150M', '650M', '3B']
const BACKENDS = [
  { key: 'qnn', label: 'QNN · Hexagon NPU', color: 'var(--series-1)' },
  { key: 'xnnpack', label: 'XNNPACK · CPU', color: 'var(--series-2)' },
] as const

const W = 640, H = 300, M = { t: 16, r: 16, b: 40, l: 56 }

/** Latest row per (model, backend) for one device. */
function latest(rows: BenchRow[], device: string) {
  const m = new Map<string, BenchRow>()
  for (const r of rows) if (r.device === device) m.set(`${r.model}|${r.backend}`, r)
  return m
}

export function BenchChart({ rows, device }: { rows: BenchRow[]; device: 's25' | 'xr' }) {
  const [hover, setHover] = useState<{ r: BenchRow; x: number; y: number } | null>(null)
  const [table, setTable] = useState(false)
  const data = latest(rows, device)
  const sizes = SIZES.filter(s => BACKENDS.some(b => data.has(`${s}|${b.key}`)))
    .concat([...new Set([...data.values()].map(r => r.model))].filter(s => !SIZES.includes(s)))

  if (!sizes.length) return <p className="muted">No benchmark results for this device yet. Run the ladder on the device.</p>

  const vals = [...data.values()].flatMap(r => r.p50_ms ? [r.p50_ms] : [])
  const lo = Math.pow(10, Math.floor(Math.log10(Math.min(...vals, 1))))
  const hi = Math.pow(10, Math.ceil(Math.log10(Math.max(...vals, 10))))
  const ih = H - M.t - M.b, iw = W - M.l - M.r
  const y = (v: number) => M.t + ih - (Math.log10(v / lo) / Math.log10(hi / lo)) * ih
  const ticks: number[] = []
  for (let t = lo; t <= hi; t *= 10) ticks.push(t)
  const group = iw / sizes.length
  const bw = Math.min(28, (group - 16) / 2)

  // Edge limit: the first size where the NPU path fails.
  const limit = sizes.find(s => { const r = data.get(`${s}|qnn`); return r && r.status !== 'ok' })

  return (
    <div className="bench">
      <div className="legend-row">
        {BACKENDS.map(b => <span key={b.key} className="legend"><i style={{ background: b.color }} />{b.label}</span>)}
        <button className="ghost" onClick={() => setTable(t => !t)}>{table ? 'Chart' : 'Table'} view</button>
      </div>
      {table ? (
        <table className="data">
          <thead><tr><th>Model</th><th>Backend</th><th>Status</th><th>Load ms</th><th>p50 ms</th><th>p95 ms</th><th>Peak RSS MB</th><th>Thermal headroom</th></tr></thead>
          <tbody>
            {sizes.flatMap(s => BACKENDS.map(b => data.get(`${s}|${b.key}`)).filter(Boolean).map(r => (
              <tr key={r!.id}>
                <td>ESM2-{r!.model}</td><td>{r!.backend}</td><td>{r!.status}{r!.error ? ` (${r!.error})` : ''}</td>
                <td>{r!.load_ms?.toFixed(0) ?? '–'}</td><td>{r!.p50_ms?.toFixed(1) ?? '–'}</td>
                <td>{r!.p95_ms?.toFixed(1) ?? '–'}</td><td>{r!.peak_rss_mb?.toFixed(0) ?? '–'}</td>
                <td>{r!.thermal_headroom ?? '–'}</td>
              </tr>
            )))}
          </tbody>
        </table>
      ) : (
        <div className="chart-wrap">
          <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="p50 latency by ESM2 size and backend (log scale)">
            {ticks.map(t => (
              <g key={t}>
                <line x1={M.l} x2={W - M.r} y1={y(t)} y2={y(t)} className="grid" />
                <text x={M.l - 8} y={y(t)} className="tick" textAnchor="end" dominantBaseline="middle">{t >= 1000 ? `${t / 1000}s` : `${t}ms`}</text>
              </g>
            ))}
            <line x1={M.l} x2={W - M.r} y1={M.t + ih} y2={M.t + ih} className="axis" />
            {sizes.map((s, i) => {
              const cx = M.l + group * i + group / 2
              return (
                <g key={s}>
                  <text x={cx} y={H - 16} className="tick" textAnchor="middle">ESM2-{s}</text>
                  {BACKENDS.map((b, j) => {
                    const r = data.get(`${s}|${b.key}`)
                    const x = cx + (j === 0 ? -bw - 1 : 1)
                    if (!r) return null
                    if (r.status !== 'ok' || !r.p50_ms) {
                      return (
                        <g key={b.key} onMouseEnter={() => setHover({ r, x: x + bw / 2, y: M.t + ih - 30 })} onMouseLeave={() => setHover(null)}>
                          <rect x={x - 4} y={M.t} width={bw + 8} height={ih} fill="transparent" />
                          <text x={x + bw / 2} y={M.t + ih - 8} textAnchor="middle" className="fail">✕</text>
                          <text x={x + bw / 2} y={M.t + ih - 24} textAnchor="middle" className="fail-label">{r.status === 'oom' ? 'OOM' : 'fail'}</text>
                        </g>
                      )
                    }
                    const top = Math.min(y(r.p50_ms), M.t + ih - 4)
                    return (
                      <g key={b.key} onMouseEnter={() => setHover({ r, x: x + bw / 2, y: top })} onMouseLeave={() => setHover(null)}>
                        <rect x={x - 4} y={M.t} width={bw + 8} height={ih} fill="transparent" />
                        <path d={`M${x},${M.t + ih} V${top + 4} q0,-4 4,-4 h${bw - 8} q4,0 4,4 V${M.t + ih} Z`} fill={b.color} />
                      </g>
                    )
                  })}
                </g>
              )
            })}
            {limit && (() => {
              const lx = M.l + group * sizes.indexOf(limit)
              return (
                <g>
                  <line x1={lx} x2={lx} y1={M.t} y2={M.t + ih} className="limit" />
                  <text x={lx + 6} y={M.t + 12} className="limit-label">NPU edge limit</text>
                </g>
              )
            })()}
          </svg>
          {hover && (
            <div className="tooltip" style={{ left: `${(hover.x / W) * 100}%`, top: `${(hover.y / H) * 100}%` }}>
              <b>ESM2-{hover.r.model} · {hover.r.backend}</b>
              {hover.r.status === 'ok' ? (
                <>
                  <span>p50 {hover.r.p50_ms?.toFixed(1)} ms · p95 {hover.r.p95_ms?.toFixed(1)} ms</span>
                  <span>load {hover.r.load_ms?.toFixed(0)} ms · {hover.r.peak_rss_mb?.toFixed(0)} MB</span>
                </>
              ) : <span>{hover.r.status}{hover.r.error ? `: ${hover.r.error}` : ''}</span>}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

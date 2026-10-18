import { useCallback, useEffect, useState } from 'react'
import {
  API, PHASES, api, phaseIndex, useWs,
  type BenchRow, type Detection, type Run, type RunDetail, type WsMsg,
} from './api'
import { BenchChart } from './components/BenchChart'
import { ProteinViewer } from './components/ProteinViewer'

type Tab = 'binding' | 'bench' | 'lab' | 'evidence'

const DEMO_STRUCTURE = '/structures/demo_complex.pdb'
// Barnase (target) / barstar (binder): classic high-affinity pair, PDB 1BRS chains A/D.
const DEMO = {
  target_seq: 'AQVINTFDGVADYLQTYHKLPDNYITKSEAQALGWVASKGNLADVAPGKSIGGDIFSNREGKLPGKSGRTWREADINYTSGFRNSDRILYSSDWLIYKTTDHYQTFTKIR',
  binder_seq: 'KKAVINGEQIRSISDLHQTLKKELALPEYYGENLDALWDCLTGWVEYPLVLEWRQFEQSKQLTENGAESVLQVFREAKAEGCDITIILS',
  target_residues: [27, 59, 73, 83, 87, 102],
  binder_residues: [29, 35, 38, 39, 76],
}

export default function App() {
  const [tab, setTab] = useState<Tab>('binding')
  const [runs, setRuns] = useState<Run[]>([])
  const [runId, setRunId] = useState<string | null>(null)
  const [run, setRun] = useState<RunDetail | null>(null)
  const [bench, setBench] = useState<BenchRow[]>([])
  const [device, setDevice] = useState<'s25' | 'xr'>('s25')
  const [live, setLive] = useState<{ step_id: string; detections: Detection[]; anomaly: boolean } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async (id = runId) => {
    const rs = await api<Run[]>('/runs')
    setRuns(rs)
    const pick = id ?? rs[0]?.id ?? null
    setRunId(pick)
    setRun(pick ? await api<RunDetail>(`/runs/${pick}`) : null)
  }, [runId])

  useEffect(() => {
    refresh().catch(e => setError(String(e)))
    api<BenchRow[]>('/bench').then(setBench).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const connected = useWs((m: WsMsg) => {
    if (m.type === 'state') {
      // Follow whichever run just moved, so phone-initiated runs appear automatically.
      setRunId(m.run_id)
      refresh(m.run_id)
      if (m.state.startsWith('STEP_')) setTab('lab')
    } else if (m.type === 'bench') {
      api<BenchRow[]>('/bench').then(setBench)
    } else if (m.type === 'detections' || m.type === 'anomaly') {
      setLive({ step_id: m.step_id, detections: m.detections, anomaly: m.type === 'anomaly' })
    }
  })

  const act = async (path: string, body: unknown = {}) => {
    setError(null)
    try {
      await api(path, body)
      await refresh()
    } catch (e) { setError(String(e)) }
  }

  const simulateBinding = () => act('/binding', {
    ...DEMO, model: '650M', backend: 'qnn', score: 0.87, latency_ms: 236, structure: DEMO_STRUCTURE,
  })

  const state = run?.state
  const pi = state ? phaseIndex(state) : -1

  return (
    <div className="app">
      <header>
        <div className="brand">
          <h1>BindLab XR</h1>
          <span className={`dot ${connected ? 'on' : ''}`} title={connected ? 'Live' : 'Disconnected'} />
          <span className="muted">{connected ? 'live' : 'offline'}</span>
        </div>
        <select value={runId ?? ''} onChange={e => refresh(e.target.value)} aria-label="Run">
          {runs.length === 0 && <option value="">No runs yet</option>}
          {runs.map(r => <option key={r.id} value={r.id}>Run {r.id} · {r.state}</option>)}
        </select>
      </header>

      <ol className="stepper">
        {PHASES.map((p, i) => (
          <li key={p.key} className={i < pi ? 'done' : i === pi ? (state === 'BIND_FAIL' ? 'fail' : 'now') : ''}>
            <span>{i < pi ? '✓' : i + 1}</span>{p.label}
          </li>
        ))}
      </ol>

      <nav className="tabs">
        {([['binding', 'Binding'], ['bench', 'Edge benchmark'], ['lab', 'Lab live'], ['evidence', 'Approval & evidence']] as const)
          .map(([k, l]) => <button key={k} className={tab === k ? 'active' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </nav>

      {error && <div className="error" role="alert">{error}</div>}

      {tab === 'binding' && (
        <section className="grid2">
          <div className="card">
            <h2>Binding result</h2>
            {!run ? (
              <>
                <p className="muted">Waiting for the S25 Ultra. Scan a sample QR code on the phone to score a binder on-device.</p>
                <button onClick={simulateBinding}>Use demo pair (barnase–barstar)</button>
              </>
            ) : (
              <>
                <div className={`hero ${state === 'BIND_FAIL' ? 'bad' : 'good'}`}>
                  <span className="hero-num">{run.data.score.toFixed(2)}</span>
                  <span>{state === 'BIND_FAIL' ? '✕ Binding unlikely' : '✓ Binding successful'}</span>
                </div>
                <dl>
                  <dt>Model</dt><dd>ESM2-{run.data.model} on {run.data.backend === 'qnn' ? 'Hexagon NPU (QNN)' : 'CPU (XNNPACK)'}</dd>
                  <dt>On-device latency</dt><dd>{run.data.latency_ms?.toFixed(0) ?? '–'} ms</dd>
                  <dt>Target</dt><dd className="seq">{run.data.target_seq}</dd>
                  <dt>Binder</dt><dd className="seq">{run.data.binder_seq}</dd>
                  <dt>Predicted interface</dt><dd>Target {run.data.target_residues.join(', ')} · binder {run.data.binder_residues.join(', ')}</dd>
                </dl>
                <div className="actions">
                  <button disabled={state !== 'BIND_OK'} onClick={() => act(`/lab/handoff/${run.id}`)}>Send to lab</button>
                  <button disabled={state !== 'SENT_TO_LAB'} onClick={() => act(`/lab/enter/${run.id}`)}>Go to lab → beam protocol to XR</button>
                  <button className="ghost" onClick={simulateBinding}>New demo run</button>
                </div>
              </>
            )}
          </div>
          <div className="card viewer-card">
            <h2>Complex · 360°</h2>
            <ProteinViewer
              src={run?.data.structure ?? DEMO_STRUCTURE}
              targetResidues={run?.data.target_residues ?? DEMO.target_residues}
              binderResidues={run?.data.binder_residues ?? DEMO.binder_residues}
              bound={!!run && state !== 'BIND_FAIL'}
            />
            <p className="muted small">
              <span className="swatch" style={{ background: 'var(--series-1)' }} /> target ·{' '}
              <span className="swatch" style={{ background: 'var(--series-2)' }} /> binder · sticks = ESM2-predicted interface.
              Structure from PDB; nothing is folded on-device. <a href={`/xr.html${run ? `?run=${run.id}` : ''}`}>Open in XR</a>
            </p>
          </div>
        </section>
      )}

      {tab === 'bench' && (
        <section className="card">
          <div className="row">
            <h2>How far can the edge go? ESM2 8M → 3B, p50 latency (log scale)</h2>
            <div className="seg">
              <button className={device === 's25' ? 'active' : ''} onClick={() => setDevice('s25')}>Galaxy S25 Ultra</button>
              <button className={device === 'xr' ? 'active' : ''} onClick={() => setDevice('xr')}>Galaxy XR</button>
            </div>
          </div>
          <BenchChart rows={bench} device={device} />
        </section>
      )}

      {tab === 'lab' && (
        <section className="grid2">
          <div className="card">
            <h2>{run?.protocol.name ?? 'Protocol'}</h2>
            {!run ? <p className="muted">No run.</p> : (
              <ol className="steps">
                {run.protocol.steps.map((s, i) => {
                  const done = pi > 2 || (pi === 2 && i < run.step_index)
                  const now = pi === 2 && i === run.step_index && state !== 'LAB_ENTERED'
                  const bad = now && live?.anomaly && live.step_id === s.id
                  return (
                    <li key={s.id} className={done ? 'done' : bad ? 'fail' : now ? 'now' : ''}>
                      <b>{done ? '✓' : bad ? '✕' : now ? '🔒' : '·'} {s.title}</b>
                      <span className="muted small">
                        key: {s.required.map(l => `${run.protocol.coco_to_lab[l] ?? l} (${l})`).join(', ')}
                        {s.forbidden.length > 0 && ` · forbidden: ${s.forbidden.join(', ')}`}
                      </span>
                    </li>
                  )
                })}
              </ol>
            )}
          </div>
          <div className="card">
            <h2>Galaxy XR camera · YOLO11n detections</h2>
            <div className={`camera ${live?.anomaly ? 'alarm' : ''}`}>
              {live?.detections.map((d, i) => (
                <div key={i} className="box" style={{
                  left: `${d.box[0] * 100}%`, top: `${d.box[1] * 100}%`,
                  width: `${d.box[2] * 100}%`, height: `${d.box[3] * 100}%`,
                }}>
                  <span>{run?.protocol.coco_to_lab[d.label] ?? d.label} {(d.conf * 100).toFixed(0)}%</span>
                </div>
              ))}
              {!live && <p className="muted">Waiting for headset detections…</p>}
              {live?.anomaly && <div className="alarm-label">⚠ Anomaly: forbidden object detected</div>}
            </div>
          </div>
        </section>
      )}

      {tab === 'evidence' && (
        <section className="grid2">
          <div className="card">
            <h2>Approval</h2>
            {!run ? <p className="muted">No run.</p> : (
              <>
                <p>{state === 'APPROVAL' ? 'All protocol steps passed. Approve to beam the result to the headset.' :
                  pi > 3 ? `Approved by ${run.data.approval?.approver ?? '—'}.` : 'Waiting for the lab protocol to finish.'}</p>
                <div className="actions">
                  <button disabled={state !== 'APPROVAL'} onClick={() => act(`/approve/${run.id}`, { approver: 'PI' })}>Approve & beam to XR</button>
                  <button disabled={pi < 4} onClick={() => act(`/evidence/${run.id}`)}>Build evidence packet</button>
                  <button disabled={pi < 5} onClick={() => act(`/patent/${run.id}`)}>Draft patent</button>
                </div>
                <div className="actions">
                  {pi >= 5 && <a className="button ghost" href={`${API}/evidence/${run.id}.pdf`} target="_blank">Evidence PDF ↗</a>}
                  {pi >= 6 && <a className="button ghost" href={`${API}/patent/${run.id}.pdf`} target="_blank">Patent draft PDF ↗</a>}
                </div>
                <p className="muted small">The patent draft is auto-generated from run data. It is not a filing and not legal advice.</p>
              </>
            )}
          </div>
          <div className="card">
            <div className="row">
              <h2>Event log</h2>
              {run && <span className={`badge ${run.chain_ok ? 'good' : 'bad'}`}>{run.chain_ok ? '✓ hash chain verified' : '✕ chain broken'}</span>}
            </div>
            <table className="data">
              <thead><tr><th>Time</th><th>Event</th><th>sha256</th></tr></thead>
              <tbody>
                {run?.events.map(e => (
                  <tr key={e.id}><td>{new Date(e.ts * 1000).toLocaleTimeString()}</td><td>{e.kind}</td><td className="mono">{e.hash.slice(0, 12)}…</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}

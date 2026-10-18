import { useEffect, useRef, useState } from 'react'

// Dev (vite on :5173) talks to the backend on :8000 on the same host; in prod the
// backend serves this bundle, so same-origin works.
export const API: string =
  import.meta.env.VITE_API ??
  (location.port === '5173' ? `${location.protocol}//${location.hostname}:8000` : '')

export const WS_URL = (API || location.origin).replace(/^http/, 'ws') + '/ws?client=web'

export type State =
  | 'BINDING' | 'BIND_OK' | 'BIND_FAIL' | 'SENT_TO_LAB' | 'LAB_ENTERED'
  | `STEP_${number}` | 'APPROVAL' | 'BEAMED' | 'EVIDENCE' | 'PATENT_DRAFT'

export interface RunData {
  target_seq: string
  binder_seq: string
  model: string
  backend: 'qnn' | 'xnnpack'
  score: number
  target_residues: number[]
  binder_residues: number[]
  latency_ms?: number
  structure?: string
  handoff?: Record<string, unknown>
  approval?: { approver: string; note?: string }
}

export interface Run {
  id: string
  created: number
  state: State
  step_index: number
  data: RunData
}

export interface ProtocolStep { id: string; title: string; required: string[]; forbidden: string[] }
export interface Protocol { name: string; steps: ProtocolStep[]; coco_to_lab: Record<string, string> }

export interface Event {
  id: number
  ts: number
  kind: string
  payload: Record<string, unknown>
  hash: string
  prev_hash: string
}

export interface RunDetail extends Run { events: Event[]; chain_ok: boolean; protocol: Protocol }

export interface BenchRow {
  id: number
  created: number
  device: 's25' | 'xr'
  model: string
  backend: 'qnn' | 'xnnpack'
  load_ms: number | null
  p50_ms: number | null
  p95_ms: number | null
  peak_rss_mb: number | null
  thermal_headroom: number | null
  status: 'ok' | 'oom' | 'load_failed'
  error: string | null
}

export interface Detection { label: string; conf: number; box: number[] }

export type WsMsg =
  | { type: 'state'; run_id: string; state: State; step_index: number }
  | { type: 'protocol'; run_id: string; steps: ProtocolStep[] }
  | { type: 'detections'; run_id: string; step_id: string; detections: Detection[] }
  | { type: 'anomaly'; run_id: string; step_id: string; detections: Detection[] }
  | { type: 'bench' } & Partial<BenchRow>
  | { type: 'beam'; run_id: string }

export async function api<T>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(API + path, body === undefined ? undefined : {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!r.ok) throw new Error(`${r.status} ${await r.text()}`)
  return r.json()
}

/** Subscribes to the backend hub; reconnects every 2s if it drops. */
export function useWs(onMsg: (m: WsMsg) => void): boolean {
  const [connected, setConnected] = useState(false)
  const cb = useRef(onMsg)
  cb.current = onMsg
  useEffect(() => {
    let ws: WebSocket | undefined
    let timer: number | undefined
    let closed = false
    const open = () => {
      ws = new WebSocket(WS_URL)
      ws.onopen = () => setConnected(true)
      ws.onmessage = e => cb.current(JSON.parse(e.data))
      ws.onclose = () => {
        setConnected(false)
        if (!closed) timer = window.setTimeout(open, 2000)
      }
    }
    open()
    return () => { closed = true; clearTimeout(timer); ws?.close() }
  }, [])
  return connected
}

export const PHASES: { key: string; label: string }[] = [
  { key: 'BIND_OK', label: 'Binding' },
  { key: 'SENT_TO_LAB', label: 'Sent to lab' },
  { key: 'STEP', label: 'Lab protocol' },
  { key: 'APPROVAL', label: 'Approval' },
  { key: 'BEAMED', label: 'Beamed to XR' },
  { key: 'EVIDENCE', label: 'Evidence' },
  { key: 'PATENT_DRAFT', label: 'Patent draft' },
]

export function phaseIndex(state: State): number {
  if (state === 'BINDING' || state === 'BIND_FAIL') return 0
  if (state === 'LAB_ENTERED' || state.startsWith('STEP_')) return 2
  return PHASES.findIndex(p => p.key === state)
}

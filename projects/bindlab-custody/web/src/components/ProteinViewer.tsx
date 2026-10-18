import { useEffect, useRef } from 'react'
import * as $3Dmol from '3dmol'

interface Props {
  /** PDB URL with the target as chain A and the binder as chain D. */
  src: string
  targetResidues: number[]
  binderResidues: number[]
  /** Plays the docking animation (binder slides in from offset) when true. */
  bound: boolean
}

const TARGET = 'A'
const BINDER = 'D'
const FRAMES = 40
const OFFSET = 28 // Å the binder starts away from its bound pose

/** Builds a multi-model PDB where the binder moves from OFFSET Å away into its bound pose. */
function dockingFrames(pdb: string): string {
  const atoms = pdb.split('\n').filter(l => l.startsWith('ATOM') || l.startsWith('HETATM'))
  const parse = (l: string) => [+l.slice(30, 38), +l.slice(38, 46), +l.slice(46, 54)]
  const centroid = (chain: string) => {
    const pts = atoms.filter(l => l[21] === chain).map(parse)
    return [0, 1, 2].map(i => pts.reduce((s, p) => s + p[i], 0) / pts.length)
  }
  const a = centroid(TARGET), d = centroid(BINDER)
  const dir = d.map((v, i) => v - a[i])
  const len = Math.hypot(...dir) || 1
  const unit = dir.map(v => v / len)
  const fmt = (v: number) => v.toFixed(3).padStart(8)
  let out = ''
  for (let f = 0; f < FRAMES; f++) {
    const t = f / (FRAMES - 1)
    const ease = 1 - Math.pow(1 - t, 3)
    const shift = OFFSET * (1 - ease)
    out += `MODEL     ${String(f + 1).padStart(4)}\n`
    for (const l of atoms) {
      if (l[21] !== BINDER) { out += l + '\n'; continue }
      const [x, y, z] = parse(l)
      out += l.slice(0, 30) + fmt(x + unit[0] * shift) + fmt(y + unit[1] * shift) +
        fmt(z + unit[2] * shift) + l.slice(54) + '\n'
    }
    out += 'ENDMDL\n'
  }
  return out
}

export function ProteinViewer({ src, targetResidues, binderResidues, bound }: Props) {
  const host = useRef<HTMLDivElement>(null)
  const viewer = useRef<$3Dmol.GLViewer | null>(null)
  const pdb = useRef<string>('')

  // Create the viewer once per structure.
  useEffect(() => {
    if (!host.current) return
    const v = $3Dmol.createViewer(host.current, { backgroundAlpha: 0 })
    viewer.current = v
    let cancelled = false
    fetch(src).then(r => r.text()).then(text => {
      if (cancelled) return
      pdb.current = text
      draw()
    })
    const onResize = () => v.resize()
    window.addEventListener('resize', onResize)
    return () => {
      cancelled = true
      window.removeEventListener('resize', onResize)
      v.clear()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src])

  // Redraw when binding state or interface changes.
  const key = `${bound}|${targetResidues.join(',')}|${binderResidues.join(',')}`
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { if (pdb.current) draw() }, [key])

  function draw() {
    const v = viewer.current
    if (!v) return
    v.stopAnimate()
    v.removeAllModels()
    v.removeAllSurfaces()
    if (bound) v.addModelsAsFrames(dockingFrames(pdb.current), 'pdb')
    else v.addModel(pdb.current, 'pdb')

    v.setStyle({ chain: TARGET }, { cartoon: { color: '#3987e5', opacity: 0.95 } })
    v.setStyle({ chain: BINDER }, { cartoon: { color: '#eb6834' } })
    if (bound) {
      v.addStyle({ chain: TARGET, resi: targetResidues },
        { stick: { colorscheme: 'cyanCarbon', radius: 0.3 } })
      v.addStyle({ chain: BINDER, resi: binderResidues },
        { stick: { colorscheme: 'yellowCarbon', radius: 0.3 } })
      v.addSurface($3Dmol.SurfaceType.VDW, { opacity: 0.35, color: '#7fffd4' },
        { chain: TARGET, resi: targetResidues })
    }
    v.zoomTo()
    v.render()
    if (bound) v.animate({ loop: 'forward', reps: 1, interval: 40 })
    v.spin('y', 0.4)
  }

  return <div ref={host} className="viewer" aria-label="3D protein complex viewer" />
}

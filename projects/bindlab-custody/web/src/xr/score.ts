// Local geometry scoring for the hand-posed binder. This is a crude proximity heuristic
// (mean nearest-neighbour distance between interface atoms), NOT a docking score.
import * as THREE from 'three'

/** Mean distance (Å) from each binder-interface atom to its nearest target-interface atom. */
export interface GeometryResult { score: number; meanA: number; minA: number }

const p = new THREE.Vector3()

/**
 * @param binder  binder-interface atom positions in binderGroup-local metres (xyz triples)
 * @param target  target-interface atom positions in molecule-local metres
 * @param rel     binderGroup transform relative to the molecule
 * @param scale   metres per Å
 */
export function geometryScore(binder: Float32Array, target: Float32Array, rel: THREE.Matrix4, scale: number): GeometryResult {
  if (binder.length === 0 || target.length === 0) return { score: 0, meanA: Infinity, minA: Infinity }
  let sum = 0, minAll = Infinity
  for (let i = 0; i < binder.length; i += 3) {
    p.set(binder[i], binder[i + 1], binder[i + 2]).applyMatrix4(rel)
    let best = Infinity
    for (let j = 0; j < target.length; j += 3) {
      const dx = p.x - target[j], dy = p.y - target[j + 1], dz = p.z - target[j + 2]
      const d2 = dx * dx + dy * dy + dz * dz
      if (d2 < best) best = d2
    }
    const d = Math.sqrt(best)
    sum += d
    if (d < minAll) minAll = d
  }
  const meanA = sum / (binder.length / 3) / scale
  // 4 Å -> 1.0, 12 Å -> 0.0
  const score = THREE.MathUtils.clamp((12 - meanA) / 8, 0, 1)
  return { score, meanA, minA: minAll / scale }
}

/** Uniform hash grid over target atoms for cheap "any binder atom closer than r" tests. */
export class ClashGrid {
  private cells = new Map<string, number[]>()
  private target: Float32Array
  private cell: number
  constructor(target: Float32Array, cell: number) {
    this.target = target
    this.cell = cell
    for (let i = 0; i < target.length; i += 3) {
      const key = this.key(target[i], target[i + 1], target[i + 2])
      let arr = this.cells.get(key)
      if (!arr) this.cells.set(key, arr = [])
      arr.push(i)
    }
  }
  private key(x: number, y: number, z: number) {
    return `${Math.floor(x / this.cell)},${Math.floor(y / this.cell)},${Math.floor(z / this.cell)}`
  }
  /** True if any transformed `points` atom lies within `r` (same units as positions) of a target atom. */
  anyWithin(points: Float32Array, rel: THREE.Matrix4, r: number): boolean {
    const r2 = r * r, t = this.target
    for (let i = 0; i < points.length; i += 3) {
      p.set(points[i], points[i + 1], points[i + 2]).applyMatrix4(rel)
      const cx = Math.floor(p.x / this.cell), cy = Math.floor(p.y / this.cell), cz = Math.floor(p.z / this.cell)
      for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) {
        const arr = this.cells.get(`${cx + dx},${cy + dy},${cz + dz}`)
        if (!arr) continue
        for (const j of arr) {
          const ex = p.x - t[j], ey = p.y - t[j + 1], ez = p.z - t[j + 2]
          if (ex * ex + ey * ey + ez * ez < r2) return true
        }
      }
    }
    return false
  }
}

// Frosted-glass sprites (labels, residue tags, pill buttons) drawn to canvas textures.
import * as THREE from 'three'

export const TEAL = '#2DD4BF'
export const AMBER = '#F59E0B'
export const TEXT = '#F3F4F6'
export const GLASS = 'rgba(20,24,28,0.75)'

export interface SpriteStyle {
  /** Rendered height in metres; width follows the text aspect. */
  heightM: number
  font?: number
  pad?: number
  radius?: number
  bg?: string
  color?: string
  border?: string
}

const DPR = 2
const FONT = (px: number) => `600 ${px}px system-ui, -apple-system, "Segoe UI", sans-serif`

export class TextSprite extends THREE.Sprite {
  text = ''
  private style: Required<SpriteStyle>
  declare material: THREE.SpriteMaterial

  constructor(text: string, style: SpriteStyle) {
    super(new THREE.SpriteMaterial({ depthTest: false, transparent: true }))
    this.style = { font: 40, pad: 28, radius: 20, bg: GLASS, color: TEXT, border: '', ...style }
    this.renderOrder = 10
    this.setText(text)
  }

  setText(text: string, patch?: Partial<SpriteStyle>) {
    if (text === this.text && !patch) return
    this.text = text
    if (patch) Object.assign(this.style, patch)
    this.draw()
  }

  private draw() {
    const { font, pad, radius, bg, color, border, heightM } = this.style
    const canvas = document.createElement('canvas')
    const g = canvas.getContext('2d')!
    g.font = FONT(font * DPR)
    const w = Math.ceil(g.measureText(this.text).width + pad * 2 * DPR)
    const h = Math.ceil(font * 1.7 * DPR)
    canvas.width = w; canvas.height = h
    g.font = FONT(font * DPR)
    g.fillStyle = bg
    g.beginPath(); g.roundRect(2, 2, w - 4, h - 4, Math.min(radius * DPR, h / 2 - 2)); g.fill()
    if (border) { g.strokeStyle = border; g.lineWidth = 3 * DPR; g.stroke() }
    g.fillStyle = color; g.textAlign = 'center'; g.textBaseline = 'middle'
    g.fillText(this.text, w / 2, h / 2 + font * DPR * 0.05)
    const tex = new THREE.CanvasTexture(canvas)
    tex.colorSpace = THREE.SRGBColorSpace
    tex.anisotropy = 4
    this.material.map?.dispose()
    this.material.map = tex
    this.material.needsUpdate = true
    this.scale.set(heightM * w / h, heightM, 1)
  }
}

const PILL_IDLE: Partial<SpriteStyle> = { bg: 'rgba(20,24,28,0.7)', border: '', color: TEXT }
const PILL_HOT: Partial<SpriteStyle> = { bg: 'rgba(45,212,191,0.28)', border: TEAL, color: '#FFFFFF' }

/** Pill button: a sprite that lights up on hover and fires `onPress` on select. */
export class Pill extends TextSprite {
  hovered = false
  onPress: () => void
  constructor(label: string, onPress: () => void, heightM = 0.045) {
    super(label, { heightM, font: 36, pad: 34, radius: 40, ...PILL_IDLE })
    this.onPress = onPress
    this.userData.pill = true
  }
  setHover(on: boolean) {
    if (on === this.hovered) return
    this.hovered = on
    this.setText(this.text, on ? PILL_HOT : PILL_IDLE)
  }
  setLabel(label: string) { this.setText(label, this.hovered ? PILL_HOT : PILL_IDLE) }
}

/** Thin additive ring used as the score halo. */
export function makeHalo(radius: number): THREE.Mesh<THREE.RingGeometry, THREE.MeshBasicMaterial> {
  const geo = new THREE.RingGeometry(radius * 0.965, radius, 96)
  const mat = new THREE.MeshBasicMaterial({
    color: TEAL, transparent: true, opacity: 0.6, blending: THREE.AdditiveBlending,
    depthWrite: false, side: THREE.DoubleSide,
  })
  const mesh = new THREE.Mesh(geo, mat)
  mesh.renderOrder = 5
  return mesh
}

const cAmber = new THREE.Color(AMBER), cTeal = new THREE.Color(TEAL)
/** Colour ramp amber (0) -> teal (1). */
export function scoreColor(score: number, out: THREE.Color): THREE.Color {
  return out.copy(cAmber).lerp(cTeal, THREE.MathUtils.clamp(score, 0, 1))
}

/** Moves `material.opacity` toward `target` with a ~200 ms settle. */
export function fadeOpacity(mat: THREE.Material, target: number, dt: number) {
  const k = 1 - Math.exp(-dt / 0.07)
  mat.opacity += (target - mat.opacity) * k
  if (Math.abs(mat.opacity - target) < 0.01) mat.opacity = target
}

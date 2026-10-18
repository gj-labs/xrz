// WebXR "desk mode" viewer for the Galaxy XR browser: the binding complex floats in
// passthrough ~0.6 m ahead, the binder docks in when the run reports a successful
// binding, and pinch/trigger grabs either the whole complex or just the binder so the
// ligand can be re-posed by hand. A desktop fallback renders the same scene with
// drag-to-rotate so the page is still useful without a headset.
import * as THREE from 'three'
import { API, WS_URL, type Run } from './api'
import { XrAudio } from './xr/audio'
import { ClashGrid, geometryScore } from './xr/score'
import { AMBER, Pill, TEAL, TextSprite, fadeOpacity, makeHalo, scoreColor } from './xr/ui'

const TARGET = 'A', BINDER = 'D'
const SCALE = 0.007 // metres per Å: ~50 Å complex -> ~0.35 m
const DOCK_OFFSET = 48 // Å (same metric travel as the previous 28 Å @ 0.012 m/Å)
const HOME = new THREE.Vector3(0, 1.25, -0.6) // relative to local-floor
const TAG_REACH = 0.15 // metres: hand distance from the bounding sphere that reveals residue tags
// Clash buzz threshold. Nominally 2.5 Å, but the demo complex's closest native contact
// (an H-bond) is 2.497 Å, so a strict 2.5 would buzz at rest; 2.45 only fires on real overlap.
const CLASH_A = 2.45 // Å
const MAX_UNDO = 3

const params = new URLSearchParams(location.search)
const status = document.getElementById('status')!
if (params.get('debug') === '1') status.classList.add('debug')
const say = (t: string) => { status.textContent = t }

// ---------------------------------------------------------------- renderer / scene
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
renderer.setPixelRatio(devicePixelRatio)
renderer.setSize(innerWidth, innerHeight)
renderer.setClearColor(0x000000, 0)
renderer.xr.enabled = true
renderer.xr.setReferenceSpaceType('local-floor')
document.body.appendChild(renderer.domElement)

const scene = new THREE.Scene()
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.01, 50)
camera.position.set(0, 1.4, 0.35)
camera.lookAt(HOME)
scene.add(new THREE.HemisphereLight(0xffffff, 0x334455, 2.2))
const sun = new THREE.DirectionalLight(0xffffff, 1.5)
sun.position.set(1, 3, 2)
scene.add(sun)

const molecule = new THREE.Group()
molecule.position.copy(HOME)
scene.add(molecule)
const binderGroup = new THREE.Group()
molecule.add(binderGroup)

// Docking physics (unchanged): the binder slides in along dockDir with a cubic ease.
let dockDir = new THREE.Vector3()
let dockT = 1 // 0 = apart, 1 = bound
let docking = false

// ---------------------------------------------------------------- PDB
interface Atom { chain: string; resi: number; resn: string; x: number; y: number; z: number; el: string }

const AA: Record<string, string> = {
  ALA: 'A', ARG: 'R', ASN: 'N', ASP: 'D', CYS: 'C', GLN: 'Q', GLU: 'E', GLY: 'G', HIS: 'H', ILE: 'I',
  LEU: 'L', LYS: 'K', MET: 'M', PHE: 'F', PRO: 'P', SER: 'S', THR: 'T', TRP: 'W', TYR: 'Y', VAL: 'V',
}

function parsePdb(text: string): Atom[] {
  return text.split('\n').filter(l => l.startsWith('ATOM')).map(l => ({
    chain: l[21], resi: +l.slice(22, 26), resn: l.slice(17, 20).trim(),
    x: +l.slice(30, 38), y: +l.slice(38, 46), z: +l.slice(46, 54),
    el: l.slice(76, 78).trim() || l[13],
  })).filter(a => a.chain === TARGET || a.chain === BINDER)
}

interface Chain {
  group: THREE.Group
  meshes: THREE.InstancedMesh[]
  /** all atom positions, group-local metres */
  all: Float32Array
  /** interface atom positions, group-local metres */
  iface: Float32Array
  centre: THREE.Vector3
  radius: number
  /** residue centroids for tags */
  residues: { label: string; pos: THREE.Vector3 }[]
}

function buildChain(atoms: Atom[], center: THREE.Vector3, base: number, hot: number, iface: Set<number>): Chain {
  const geo = new THREE.SphereGeometry(1, 10, 8)
  const plain = atoms.filter(a => !iface.has(a.resi)), hotAtoms = atoms.filter(a => iface.has(a.resi))
  const make = (list: Atom[], mat: THREE.MeshStandardMaterial, bump: number) => {
    const mesh = new THREE.InstancedMesh(geo, mat, Math.max(list.length, 1))
    mesh.count = list.length
    const m = new THREE.Matrix4()
    list.forEach((a, i) => {
      const r = (a.el === 'H' ? 0.6 : 1.1) * SCALE * bump
      m.makeScale(r, r, r).setPosition((a.x - center.x) * SCALE, (a.y - center.y) * SCALE, (a.z - center.z) * SCALE)
      mesh.setMatrixAt(i, m)
    })
    return mesh
  }
  const meshes = [
    make(plain, new THREE.MeshStandardMaterial({ color: base, roughness: 0.55, metalness: 0.05 }), 1),
    make(hotAtoms, new THREE.MeshStandardMaterial({ color: hot, emissive: hot, emissiveIntensity: 0.6, roughness: 0.4, metalness: 0.05 }), 1.25),
  ]
  const group = new THREE.Group()
  meshes.forEach(m => group.add(m))

  const toArr = (list: Atom[]) => {
    const f = new Float32Array(list.length * 3)
    list.forEach((a, i) => { f[i * 3] = (a.x - center.x) * SCALE; f[i * 3 + 1] = (a.y - center.y) * SCALE; f[i * 3 + 2] = (a.z - center.z) * SCALE })
    return f
  }
  const all = toArr(atoms), ifaceArr = toArr(hotAtoms)
  const centre = new THREE.Vector3()
  for (let i = 0; i < all.length; i += 3) centre.add(new THREE.Vector3(all[i], all[i + 1], all[i + 2]))
  centre.divideScalar(atoms.length)
  let radius = 0
  for (let i = 0; i < all.length; i += 3) radius = Math.max(radius, centre.distanceTo(new THREE.Vector3(all[i], all[i + 1], all[i + 2])))

  const byRes = new Map<number, Atom[]>()
  hotAtoms.forEach(a => { const l = byRes.get(a.resi) ?? []; l.push(a); byRes.set(a.resi, l) })
  const residues = [...byRes.entries()].map(([resi, list]) => ({
    label: `${AA[list[0].resn] ?? list[0].resn}${resi}`,
    pos: list.reduce((v, a) => v.add(new THREE.Vector3((a.x - center.x) * SCALE, (a.y - center.y) * SCALE, (a.z - center.z) * SCALE)), new THREE.Vector3()).divideScalar(list.length),
  }))
  return { group, meshes, all, iface: ifaceArr, centre, radius, residues }
}

// ---------------------------------------------------------------- overlays
const audio = new XrAudio()
let target: Chain | null = null, binder: Chain | null = null
let clashGrid: ClashGrid | null = null
let moleculeRadius = 0.2

const halo = makeHalo(1)
halo.visible = false
scene.add(halo)

const label = new TextSprite('Loading complex…', { heightM: 0.05, font: 40, pad: 36, radius: 20 })
scene.add(label)

const tags: TextSprite[] = []
const pills: Pill[] = []
const hud = new THREE.Group()
scene.add(hud)

const ghostMat = new THREE.MeshStandardMaterial({ color: 0xf59e0b, transparent: true, opacity: 0.15, roughness: 0.6, depthWrite: false })
const ghosts: THREE.Group[] = []

// ---------------------------------------------------------------- state
interface Pose { position: THREE.Vector3; quaternion: THREE.Quaternion }
let run: Run | undefined
let currentRunId = ''
let lastRunOk = false
let binderFree = false // user has posed the binder: stop applying the dock formula
let geom = { score: 0, meanA: Infinity, minA: Infinity }
const committed: Pose[] = []
type Held = 'molecule' | 'binder' | 'pill' | null
const held: Held[] = [null, null]
const binderGrabbed = () => held.includes('binder')
let lastCommitScore: number | null = null

const relMatrix = new THREE.Matrix4()
/** binderGroup transform relative to the molecule, whichever parent it currently has. */
function binderRelative(): THREE.Matrix4 {
  if (binderGroup.parent === molecule) return relMatrix.compose(binderGroup.position, binderGroup.quaternion, binderGroup.scale)
  molecule.updateWorldMatrix(true, false)
  binderGroup.updateWorldMatrix(true, false)
  return relMatrix.copy(molecule.matrixWorld).invert().multiply(binderGroup.matrixWorld)
}

function currentPose(): Pose {
  const m = binderRelative()
  const position = new THREE.Vector3(), quaternion = new THREE.Quaternion(), s = new THREE.Vector3()
  m.decompose(position, quaternion, s)
  return { position, quaternion }
}

function recomputeGeom() {
  if (binder && target) geom = geometryScore(binder.iface, target.iface, binderRelative(), SCALE)
}

function applyPose(p: Pose) {
  if (binderGrabbed()) return
  binderGroup.position.copy(p.position)
  binderGroup.quaternion.copy(p.quaternion)
  binderFree = true
  recomputeGeom()
  refreshGhosts()
}

function refreshGhosts() {
  ghosts.forEach((g, i) => {
    const p = committed[committed.length - 1 - i]
    g.visible = !!p
    if (p) { g.position.copy(p.position); g.quaternion.copy(p.quaternion) }
  })
}

function makeGhost(chain: Chain): THREE.Group {
  const g = new THREE.Group()
  chain.meshes.forEach(src => {
    const mesh = new THREE.InstancedMesh(src.geometry, ghostMat, src.count)
    mesh.instanceMatrix.copy(src.instanceMatrix)
    mesh.renderOrder = 2
    g.add(mesh)
  })
  g.visible = false
  return g
}

// ---------------------------------------------------------------- actions
async function commit() {
  if (!binder) return
  const pose = currentPose()
  committed.push(pose)
  if (committed.length > MAX_UNDO) committed.shift()
  refreshGhosts()
  audio.chime()
  lastCommitScore = geom.score
  say(`Committed pose · geometry ${geom.score.toFixed(2)}`)
  if (!run) return
  try {
    const r = await fetch(`${API}/commit`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        run_id: run.id,
        pose: { position: pose.position.toArray(), quaternion: pose.quaternion.toArray() },
        geometry_score: geom.score,
      }),
    })
    if (!r.ok) say(`Commit stored locally (server ${r.status})`)
  } catch { say('Commit stored locally (server offline)') }
}

function undo() {
  if (!committed.length) return
  committed.pop()
  const prev = committed[committed.length - 1]
  if (prev) applyPose(prev)
  else if (!binderGrabbed()) {
    // Back to the docked pose.
    binderGroup.quaternion.identity()
    binderGroup.position.set(0, 0, 0)
    binderFree = false
    recomputeGeom()
    refreshGhosts()
  }
  updateLabel()
}

function toggleMute() {
  audio.unlock()
  const muted = audio.toggleMute()
  pills[2]?.setLabel(muted ? 'Unmute' : 'Mute')
}

// ---------------------------------------------------------------- loading
async function fetchRun(): Promise<Run | undefined> {
  const runId = params.get('run')
  try {
    return runId ? await (await fetch(`${API}/runs/${runId}`)).json()
      : (await (await fetch(`${API}/runs`)).json())[0]
  } catch { return undefined }
}

async function load() {
  run = await fetchRun()
  const data = run?.data as (Run['data'] & { structure_ref?: string }) | undefined
  const src = data?.structure ?? (data?.structure_ref ? `/structures/${data.structure_ref}.pdb` : '/structures/demo_complex.pdb')
  const atoms = parsePdb(await (await fetch(src)).text())
  const center = new THREE.Vector3()
  atoms.forEach(a => center.add(new THREE.Vector3(a.x, a.y, a.z)))
  center.divideScalar(atoms.length)

  const tIface = new Set(run?.data.target_residues ?? [27, 59, 73, 83, 87, 102])
  const bIface = new Set(run?.data.binder_residues ?? [29, 35, 38, 39, 76])
  const tAtoms = atoms.filter(a => a.chain === TARGET), bAtoms = atoms.filter(a => a.chain === BINDER)
  target = buildChain(tAtoms, center, 0x6b7f99, 0x2dd4bf, tIface)
  binder = buildChain(bAtoms, center, 0xf59e0b, 0xffe066, bIface)
  molecule.add(target.group)
  binderGroup.add(binder.group)
  clashGrid = new ClashGrid(target.all, CLASH_A * SCALE)
  moleculeRadius = 0
  for (let i = 0; i < target.all.length; i += 3) moleculeRadius = Math.max(moleculeRadius, Math.hypot(target.all[i], target.all[i + 1], target.all[i + 2]))
  for (let i = 0; i < binder.all.length; i += 3) moleculeRadius = Math.max(moleculeRadius, Math.hypot(binder.all[i], binder.all[i + 1], binder.all[i + 2]))

  const centroid = (xs: Atom[]) => xs.reduce((v, a) => v.add(new THREE.Vector3(a.x, a.y, a.z)), new THREE.Vector3()).divideScalar(xs.length)
  dockDir = centroid(bAtoms).sub(centroid(tAtoms)).normalize()

  // Halo sized to the binder.
  halo.geometry.dispose()
  halo.geometry = new THREE.RingGeometry(binder.radius * 1.3 * 0.965, binder.radius * 1.3, 96)
  halo.visible = true

  // Residue tags: up to 6, split between target (teal) and binder (amber).
  const tagStyle = { heightM: 0.022, font: 34, pad: 22, radius: 20 }
  target.residues.slice(0, 3).forEach(r => {
    const s = new TextSprite(r.label, { ...tagStyle, color: TEAL })
    s.position.copy(r.pos); s.material.opacity = 0
    molecule.add(s); tags.push(s)
  })
  binder.residues.slice(0, 6 - Math.min(3, target.residues.length)).forEach(r => {
    const s = new TextSprite(r.label, { ...tagStyle, color: AMBER })
    s.position.copy(r.pos); s.material.opacity = 0
    binderGroup.add(s); tags.push(s)
  })

  for (let i = 0; i < MAX_UNDO; i++) { const g = makeGhost(binder); ghosts.push(g); molecule.add(g) }

  pills.push(new Pill('Commit', () => { void commit() }), new Pill('Undo', undo), new Pill('Mute', toggleMute))
  pills.forEach((p, i) => { p.position.set(0, -i * 0.06, 0); hud.add(p) })

  if (run) setRun(run)
  else { say('Demo complex (backend offline)'); updateLabel() }
}

function setRun(r: Run) {
  run = r
  const ok = r.state !== 'BIND_FAIL' && r.state !== 'BINDING'
  say(`Run ${r.id} · ESM2-${r.data.model} · score ${r.data.score.toFixed(2)} · ${r.state}`)
  const fresh = r.id !== currentRunId || (ok && !lastRunOk)
  currentRunId = r.id; lastRunOk = ok
  if (fresh && ok && !docking && dockT >= 1 && !binderGrabbed()) {
    dockT = 0; docking = true
    binderFree = false
    binderGroup.quaternion.identity()
  }
  updateLabel()
}

function updateLabel() {
  const g = `Geometry ${geom.score.toFixed(2)}`
  if (!run) { label.setText(binderGrabbed() ? `${g} · local heuristic` : binderFree ? `Demo complex · ${g}` : 'Demo complex · no run'); return }
  const ok = run.state !== 'BIND_FAIL' && run.state !== 'BINDING'
  const esm = `${ok ? 'Binding' : 'No binding'} ${run.data.score.toFixed(2)} · ESM2-${run.data.model}`
  if (binderGrabbed()) label.setText(`${g} · local heuristic`)
  else if (binderFree) label.setText(`${esm} · ${g}`)
  else if (lastCommitScore !== null) label.setText(`${esm} · committed ${lastCommitScore.toFixed(2)}`)
  else label.setText(esm)
}

// ---------------------------------------------------------------- websocket (auto-reconnect)
function connectWs() {
  let ws: WebSocket
  try { ws = new WebSocket(WS_URL.replace('client=web', 'client=xr-viewer')) } catch { setTimeout(connectWs, 2000); return }
  let retried = false
  const retry = () => { if (!retried) { retried = true; setTimeout(connectWs, 2000) } }
  ws.onopen = async () => { const r = await fetchRun(); if (r) setRun(r) }
  ws.onclose = retry
  ws.onerror = retry
  ws.onmessage = async e => {
    const m = JSON.parse(e.data)
    if (m.type === 'state' && (m.state === 'BIND_OK' || m.state === 'BIND_FAIL')) {
      try { setRun(await (await fetch(`${API}/runs/${m.run_id}`)).json()) } catch { /* keep current */ }
    } else if (m.type === 'commit' && (!run || m.run_id === run.id)) {
      if (typeof m.geometry_score === 'number') lastCommitScore = m.geometry_score
      updateLabel()
    } else if (m.type === 'state' && run && m.run_id === run.id) {
      run = { ...run, state: m.state }
      updateLabel()
    }
  }
}
connectWs()

// ---------------------------------------------------------------- input
const raycaster = new THREE.Raycaster()
const tmpV = new THREE.Vector3(), tmpV2 = new THREE.Vector3(), tmpQ = new THREE.Quaternion()
const controllers: THREE.Group[] = []

function pillUnderRay(origin: THREE.Vector3, dir: THREE.Vector3): Pill | null {
  if (!pills.length) return null
  raycaster.camera = camera
  raycaster.set(origin, dir)
  const hit = raycaster.intersectObjects(pills, false)[0]
  return hit ? (hit.object as Pill) : null
}

function controllerRay(ctrl: THREE.Object3D) {
  ctrl.getWorldPosition(tmpV)
  tmpV2.set(0, 0, -1).applyQuaternion(ctrl.getWorldQuaternion(tmpQ))
  return { origin: tmpV, dir: tmpV2 }
}

function insideBinder(worldPos: THREE.Vector3): boolean {
  if (!binder) return false
  const local = binderGroup.worldToLocal(worldPos.clone())
  return local.distanceTo(binder.centre) < binder.radius + 0.02
}

for (let i = 0; i < 2; i++) {
  const ctrl = renderer.xr.getController(i)
  ctrl.addEventListener('selectstart', () => {
    audio.unlock()
    const { origin, dir } = controllerRay(ctrl)
    const pill = pillUnderRay(origin, dir)
    if (pill) { held[i] = 'pill'; pill.onPress(); return }
    if (insideBinder(origin) && !binderGrabbed()) {
      held[i] = 'binder'
      ctrl.attach(binderGroup)
      return
    }
    held[i] = 'molecule'
    ctrl.attach(molecule)
  })
  ctrl.addEventListener('selectend', () => {
    const was = held[i]
    held[i] = null
    if (was === 'pill') return
    if (was === 'binder') {
      molecule.attach(binderGroup)
      binderFree = true
      recomputeGeom()
      audio.setGrabTone(false, 0)
      updateLabel()
      return
    }
    scene.attach(molecule)
  })
  scene.add(ctrl)
  controllers.push(ctrl)
}

// Desktop fallback: drag to rotate, click pills.
let dragging = false, lastX = 0, lastY = 0
const pointerNdc = new THREE.Vector2()
let pointerHover: Pill | null = null
function pointerRay(e: PointerEvent): Pill | null {
  pointerNdc.set((e.clientX / innerWidth) * 2 - 1, -(e.clientY / innerHeight) * 2 + 1)
  raycaster.camera = camera
  raycaster.setFromCamera(pointerNdc, camera)
  const hit = pills.length ? raycaster.intersectObjects(pills, false)[0] : undefined
  return hit ? (hit.object as Pill) : null
}
renderer.domElement.addEventListener('pointerdown', e => {
  if (renderer.xr.isPresenting) return
  audio.unlock()
  const pill = pointerRay(e)
  if (pill) { pill.onPress(); return }
  dragging = true; lastX = e.clientX; lastY = e.clientY
})
addEventListener('pointermove', e => {
  if (renderer.xr.isPresenting) return
  if (dragging) {
    molecule.rotation.y += (e.clientX - lastX) * 0.01
    molecule.rotation.x += (e.clientY - lastY) * 0.01
    lastX = e.clientX; lastY = e.clientY
  } else {
    const p = pointerRay(e)
    if (p !== pointerHover) { pointerHover?.setHover(false); pointerHover = p; p?.setHover(true) }
  }
})
addEventListener('pointerup', () => { dragging = false })

// ---------------------------------------------------------------- entry overlay
const overlay = document.getElementById('enter')!
const overlayTitle = document.getElementById('enter-title')!
const overlaySub = document.getElementById('enter-sub')!
type Mode = 'immersive-ar' | 'immersive-vr' | 'none'
let mode: Mode = 'none'
if ('xr' in navigator && navigator.xr) {
  try {
    if (await navigator.xr.isSessionSupported('immersive-ar')) mode = 'immersive-ar'
    else if (await navigator.xr.isSessionSupported('immersive-vr')) mode = 'immersive-vr'
  } catch { mode = 'none' }
}
if (mode === 'immersive-vr') { overlayTitle.textContent = 'Enter VR'; overlaySub.textContent = 'Passthrough unavailable · immersive VR' }
if (mode === 'none') { overlayTitle.textContent = 'Open BindLab'; overlaySub.textContent = 'WebXR unavailable · desktop preview' }

let session: XRSession | null = null
async function enter() {
  audio.unlock()
  if (mode === 'none') { overlay.classList.add('hidden'); return }
  try {
    const init: XRSessionInit = mode === 'immersive-ar'
      ? { optionalFeatures: ['local-floor', 'hand-tracking', 'dom-overlay'], domOverlay: { root: document.body } }
      : { optionalFeatures: ['local-floor', 'hand-tracking'] }
    session = await navigator.xr!.requestSession(mode, init)
    session.addEventListener('end', () => { session = null; overlay.classList.remove('hidden') })
    await renderer.xr.setSession(session)
    overlay.classList.add('hidden')
  } catch (e) {
    say(`XR session failed: ${e}`)
    overlayTitle.textContent = 'XR unavailable · tap for desktop preview'
    mode = 'none'
  }
}
overlay.addEventListener('click', () => { void enter() })

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight
  camera.updateProjectionMatrix()
  renderer.setSize(innerWidth, innerHeight)
})

// ---------------------------------------------------------------- frame loop
const haloColor = new THREE.Color()
const clock = new THREE.Clock()
let lastLabelScore = -1
let lastClashT = 0

renderer.setAnimationLoop(() => {
  const dt = clock.getDelta()
  const t = clock.elapsedTime
  const presenting = renderer.xr.isPresenting

  // Docking (unchanged behaviour) unless the user has taken the binder.
  if (docking) {
    dockT = Math.min(1, dockT + dt / 1.8)
    if (dockT >= 1) docking = false
  }
  if (!binderFree && !binderGrabbed()) {
    const ease = 1 - Math.pow(1 - dockT, 3)
    binderGroup.position.copy(dockDir).multiplyScalar(DOCK_OFFSET * SCALE * (1 - ease))
  }
  if (molecule.parent === scene && !dragging) molecule.rotation.y += dt * 0.25

  // Geometry score + clash while the binder is held.
  const grabbed = binderGrabbed()
  if (grabbed && binder && target && clashGrid) {
    const rel = binderRelative()
    geom = geometryScore(binder.iface, target.iface, rel, SCALE)
    audio.setGrabTone(true, geom.meanA)
    if (t - lastClashT > 0.1) {
      lastClashT = t
      if (clashGrid.anyWithin(binder.all, rel, CLASH_A * SCALE)) audio.buzz()
    }
    if (Math.abs(geom.score - lastLabelScore) > 0.005) { lastLabelScore = geom.score; updateLabel() }
  }

  // Score halo + label follow the binder / molecule and face the camera.
  const shownScore = grabbed || binderFree ? geom.score : (run ? run.data.score : 0.5)
  if (binder) {
    binderGroup.updateWorldMatrix(true, false)
    halo.position.copy(binder.centre).applyMatrix4(binderGroup.matrixWorld)
    halo.quaternion.copy(camera.quaternion)
    halo.material.color.copy(scoreColor(shownScore, haloColor))
    halo.material.opacity = 0.35 + 0.2 * Math.sin(t * 2.2) + (grabbed ? 0.15 : 0)
  }
  molecule.getWorldPosition(tmpV)
  label.position.set(tmpV.x, tmpV.y - moleculeRadius - 0.06, tmpV.z)
  // Pills hang below-right of the molecule, in camera-right direction.
  tmpV2.set(1, 0, 0).applyQuaternion(camera.quaternion)
  hud.position.copy(tmpV).addScaledVector(tmpV2, moleculeRadius + 0.12)
  hud.position.y -= moleculeRadius * 0.4

  // Residue tags: reveal when a hand is near the molecule (or always on desktop).
  let near = !presenting
  let hover: Pill | null = null
  if (presenting) {
    for (const ctrl of controllers) {
      if (!ctrl.visible) continue
      const { origin, dir } = controllerRay(ctrl)
      if (origin.distanceTo(tmpV) < moleculeRadius + TAG_REACH) near = true
      hover = hover ?? pillUnderRay(origin, dir)
    }
    pills.forEach(p => p.setHover(p === hover))
  }
  for (const s of tags) fadeOpacity(s.material, near ? 1 : 0, dt)

  renderer.render(scene, camera)
})

load().catch(e => { say(`Failed to load: ${e}`); label.setText('Failed to load structure') })

// ?debug=1 exposes a few hooks for scripted smoke tests without a headset.
if (params.get('debug') === '1') {
  Object.assign(window, {
    __bindlab: {
      commit, undo, toggleMute,
      pose: () => currentPose(),
      committed: () => committed.length,
      label: () => label.text,
      geometry: () => {
        if (!binder || !target) return null
        return geometryScore(binder.iface, target.iface, binderRelative(), SCALE)
      },
      nudge: (dx: number, dy: number, dz: number) => { binderGroup.position.add(new THREE.Vector3(dx, dy, dz)); binderFree = true; recomputeGeom(); updateLabel() },
      ready: () => !!binder,
    },
  })
}

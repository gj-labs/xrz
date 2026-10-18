// WebAudio cues for the XR viewer: a distance-pitched grab tone, a commit chime and a clash buzz.
// Everything is created lazily from a user gesture so autoplay policies are satisfied.

export class XrAudio {
  muted = false
  private ctx: AudioContext | null = null
  private master: GainNode | null = null
  private osc: OscillatorNode | null = null
  private toneGain: GainNode | null = null
  private lastBuzz = 0

  /** Call from any user gesture (overlay tap, pinch). Safe to call repeatedly. */
  unlock() {
    if (!this.ctx) {
      const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      if (!Ctor) return
      this.ctx = new Ctor()
      this.master = this.ctx.createGain()
      this.master.gain.value = this.muted ? 0 : 1
      this.master.connect(this.ctx.destination)
    }
    if (this.ctx.state === 'suspended') void this.ctx.resume()
  }

  toggleMute(): boolean {
    this.muted = !this.muted
    if (this.master && this.ctx) this.master.gain.setTargetAtTime(this.muted ? 0 : 1, this.ctx.currentTime, 0.02)
    return this.muted
  }

  /** Continuous soft tone while the binder is held; 220 Hz at 4 Å rising to 660 Hz at 12 Å. */
  setGrabTone(active: boolean, meanDistA: number) {
    if (!this.ctx || !this.master) return
    const t = this.ctx.currentTime
    if (active) {
      if (!this.osc) {
        this.osc = this.ctx.createOscillator()
        this.osc.type = 'triangle'
        this.toneGain = this.ctx.createGain()
        this.toneGain.gain.value = 0
        this.osc.connect(this.toneGain).connect(this.master)
        this.osc.start()
        this.toneGain.gain.setTargetAtTime(0.06, t, 0.08)
      }
      const k = Math.min(1, Math.max(0, (meanDistA - 4) / 8))
      this.osc.frequency.setTargetAtTime(220 + k * 440, t, 0.05)
    } else if (this.osc && this.toneGain) {
      const osc = this.osc, g = this.toneGain
      g.gain.setTargetAtTime(0, t, 0.05)
      osc.stop(t + 0.3)
      osc.onended = () => { osc.disconnect(); g.disconnect() }
      this.osc = null; this.toneGain = null
    }
  }

  /** Two-note rising chime for Commit. */
  chime() {
    this.blip(660, 0, 0.12, 'sine', 0.08)
    this.blip(880, 0.13, 0.18, 'sine', 0.08)
  }

  /** Low 90 Hz buzz when a binder atom clashes with the target; rate-limited. */
  buzz() {
    if (!this.ctx) return
    const now = performance.now()
    if (now - this.lastBuzz < 350) return
    this.lastBuzz = now
    this.blip(90, 0, 0.15, 'sawtooth', 0.07)
  }

  private blip(freq: number, delay: number, dur: number, type: OscillatorType, gain: number) {
    if (!this.ctx || !this.master) return
    const t0 = this.ctx.currentTime + delay
    const osc = this.ctx.createOscillator()
    const g = this.ctx.createGain()
    osc.type = type
    osc.frequency.value = freq
    g.gain.setValueAtTime(0, t0)
    g.gain.linearRampToValueAtTime(gain, t0 + 0.01)
    g.gain.exponentialRampToValueAtTime(0.0005, t0 + dur)
    osc.connect(g).connect(this.master)
    osc.start(t0)
    osc.stop(t0 + dur + 0.02)
    osc.onended = () => { osc.disconnect(); g.disconnect() }
  }
}
